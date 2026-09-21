import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminAuditView from '@/views/next/NextAdminAuditView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({ auditEvents: vi.fn() }));
const mockApi = vi.mocked(api);

const event = {
  id: 'a1',
  chainPosition: 12,
  actorId: 'root',
  actorName: 'Admin',
  action: 'LOGIN_SUCCESS',
  targetType: 'USER',
  targetId: '1f8a2c34-9999-4aaa-bbbb-ccccddddeeee',
  targetName: '生产密钥',
  changeSummary: 'root 登录成功',
  createdAt: '2026-09-03T08:00:00Z',
};

/** radix-based UiSelect renders through a portal; the stub keeps options clickable. */
const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    return {
      props,
      pick: (value: unknown) => {
        emit('update:modelValue', value);
        emit('change', value);
      },
    };
  },
  template: `
    <div class="ui-select-stub">
      <button
        v-for="opt in props.options"
        :key="opt.value"
        :data-testid="'audit-opt-' + (opt.value || 'all')"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>`,
});

describe('NextAdminAuditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.auditEvents.mockResolvedValue([event]);
  });

  function mountView() {
    return mount(NextAdminAuditView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders audit events with chain positions', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="audit-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('登录成功');
    expect(wrapper.text()).toContain('root');
    expect(wrapper.text()).toContain('生产密钥');
    expect(wrapper.text()).toContain('Admin');
  });

  it('falls back to the short actor id when the name is unresolved (#484)', async () => {
    mockApi.auditEvents.mockResolvedValue([
      { ...event, actorName: undefined, actorId: '11111111-2222-3333-4444-555555555555' },
    ]);
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('11111111…');
    expect(wrapper.text()).not.toContain('11111111-2222');
  });

  it('falls back to the short target id when the name is unresolved (#389)', async () => {
    mockApi.auditEvents.mockResolvedValue([
      { ...event, targetName: undefined, targetId: '1f8a2c34-9999-4aaa-bbbb-ccccddddeeee' },
    ]);
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('1f8a2c34…');
    expect(wrapper.text()).not.toContain('1f8a2c34-9999');
  });

  it('filters by action', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="audit-action-filter"]').setValue('LOGIN_SUCCESS');
    await wrapper.find('[data-testid="audit-refresh"]').trigger('click');
    await flushPromises();
    expect(mockApi.auditEvents).toHaveBeenLastCalledWith({ action: 'LOGIN_SUCCESS' });
  });

  it('filters by resource type via the select and by time window', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="audit-opt-MCP_SERVICE"]').trigger('click');
    await wrapper.find('[data-testid="audit-from"]').setValue('2026-09-09T00:00');
    await wrapper.find('[data-testid="audit-refresh"]').trigger('click');
    await flushPromises();
    const call = mockApi.auditEvents.mock.calls.at(-1)?.[0];
    expect(call?.targetType).toBe('MCP_SERVICE');
    expect(call?.from).toBe(new Date('2026-09-09T00:00').toISOString());
  });

  it('filters by actor and rejects malformed UUIDs without a request', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="audit-actor-filter"]').setValue('not-a-uuid');
    await wrapper.find('[data-testid="audit-refresh"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="audit-actor-error"]').text()).toContain('UUID');
    expect(mockApi.auditEvents).toHaveBeenCalledTimes(1); // only the onMounted load

    const actor = '11111111-2222-3333-4444-555555555555';
    await wrapper.find('[data-testid="audit-actor-filter"]').setValue(actor);
    await wrapper.find('[data-testid="audit-refresh"]').trigger('click');
    await flushPromises();
    expect(mockApi.auditEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({ actorId: actor }),
    );
  });

  it('fills the trailing window from the quick range buttons', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-10T12:00:00'));
    try {
      const wrapper = mountView();
      await flushPromises();
      await wrapper.find('[data-testid="audit-range-7"]').trigger('click');
      await flushPromises();
      expect((wrapper.find('[data-testid="audit-from"]').element as HTMLInputElement).value).toBe(
        '2026-09-03T12:00',
      );
      expect((wrapper.find('[data-testid="audit-to"]').element as HTMLInputElement).value).toBe(
        '2026-09-10T12:00',
      );
      const call = mockApi.auditEvents.mock.calls.at(-1)?.[0];
      expect(call?.from).toBe(new Date('2026-09-03T12:00').toISOString());
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps the newer quick window when a slower older one answers last (#1231)', async () => {
    const wrapper = mountView();
    await flushPromises();

    // Second phase: two quick ranges in flight at once — 近 30 天 is issued
    // first, 近 7 天 (the user's final pick) answers first.
    type AuditEvents = Awaited<ReturnType<typeof api.auditEvents>>;
    const pending: Array<{ resolve: (v: AuditEvents) => void }> = [];
    mockApi.auditEvents.mockImplementation(
      () =>
        new Promise<AuditEvents>((resolve) => {
          pending.push({ resolve });
        }),
    );

    await wrapper.find('[data-testid="audit-range-30"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="audit-range-7"]').trigger('click');
    await flushPromises();
    expect(pending).toHaveLength(2);

    const row = (marker: string) => ({ ...event, changeSummary: marker });

    // The 近7天 answer lands first — it is the window the user selected last.
    pending[1]!.resolve([row('SEVEN-DAY-MARKER')]);
    await flushPromises();
    expect(wrapper.text()).toContain('SEVEN-DAY-MARKER');

    // The stale 近30天 answer lands second and must NOT overwrite the table.
    pending[0]!.resolve([row('THIRTY-DAY-MARKER')]);
    await flushPromises();
    expect(wrapper.text(), 'stale 30-day rows overwrote the newer 7-day window').toContain(
      'SEVEN-DAY-MARKER',
    );
    expect(wrapper.text()).not.toContain('THIRTY-DAY-MARKER');
  });
});
