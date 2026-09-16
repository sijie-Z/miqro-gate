import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminRetentionLogsView from '@/views/next/NextAdminRetentionLogsView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({
  retentionLogs: vi.fn(),
  exportRetentionLogsCsv: vi.fn(),
  getRetentionConfig: vi.fn(),
  putRetentionConfig: vi.fn(),
}));
const mockApi = vi.mocked(api);

const row = {
  eventId: 'e1b7d2f0-1111-2222-3333-444455556666',
  userId: '4cbddb41-b04a-4cc9-88b0-ed631428f56e',
  userName: '演示用户',
  virtualKeyId: '9155d880-e2db-4412-9999-000011112222',
  wireProtocol: 'OPENAI_CHAT',
  direction: 'OUTPUT',
  gatewayRequestId: '884504c2-c315-4b0d-9f45-20cb5891b528',
  occurredAt: '2026-09-15T09:52:08.409771Z',
  textCharCount: 16,
  truncated: false,
  dataMd5: '243c2e5c1f0d9a0b1c2d3e4f5a6b7c8d',
  text: 'RETENTION-E2E-OK',
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
      <button v-for="opt in props.options" :key="opt.value" type="button" class="stub-option" @click="pick(opt.value)">
        {{ opt.label }}
      </button>
    </div>
  `,
});

describe('NextAdminRetentionLogsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.retentionLogs.mockResolvedValue([row]);
    mockApi.getRetentionConfig.mockResolvedValue({ enabled: true, maxContentBytes: 524288 });
    mockApi.putRetentionConfig.mockResolvedValue({ enabled: true, maxContentBytes: 524288 });
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() });
    document.body.innerHTML = '';
  });

  function mountView() {
    return mount(NextAdminRetentionLogsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders decrypted rows with direction labels and previews', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="retention-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('输出');
    expect(wrapper.text()).toContain('演示用户');
    expect(wrapper.text()).toContain('RETENTION-E2E-OK');
    expect(wrapper.text()).toContain('243c2e5c'); // md5 prefix
  });

  it('opens the detail dialog with the full text', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(`[data-testid="retention-view-${row.eventId}"]`).trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('留痕详情');
    expect(document.querySelector('[data-testid="retention-full-text"]')?.textContent).toContain(
      'RETENTION-E2E-OK',
    );
  });

  it('exports CSV and reports the row count', async () => {
    mockApi.exportRetentionLogsCsv.mockResolvedValue({ csv: 'a,b\n1,2\n3,4', truncated: false });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="retention-export"]').trigger('click');
    await flushPromises();

    expect(mockApi.exportRetentionLogsCsv).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="retention-export-notice"]').text()).toContain('已导出 2 行');
  });

  it('rejects a non-UUID user filter before calling the API', async () => {
    const wrapper = mountView();
    await flushPromises();
    mockApi.retentionLogs.mockClear();

    await wrapper
      .find(
        '[data-testid="retention-user-filter"] input, input[data-testid="retention-user-filter"]',
      )
      .setValue('not-a-uuid');
    await wrapper.find('[data-testid="retention-refresh"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="retention-user-error"]').text()).toContain('UUID');
    expect(mockApi.retentionLogs).not.toHaveBeenCalled();
  });

  it('renders the capture settings and saves via PUT (#688)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const card = wrapper.find('[data-testid="retention-config-card"]');
    expect(card.exists()).toBe(true);
    const toggle = wrapper.find('[data-testid="retention-config-enabled"]');
    expect((toggle.element as HTMLInputElement).checked).toBe(true);
    const cap = wrapper.find('[data-testid="retention-config-max-bytes"]');
    expect((cap.element as HTMLInputElement).value).toBe('524288');
    expect(card.text()).toContain('工具调用载荷、系统提示词与推理链仍不采集');

    await toggle.setValue(false);
    await cap.setValue('1048576');
    await wrapper.find('[data-testid="retention-config-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putRetentionConfig).toHaveBeenCalledWith({
      enabled: false,
      maxContentBytes: 1048576,
    });
    expect(wrapper.find('[data-testid="retention-config-notice"]').text()).toContain('已保存');
  });

  it('rejects an out-of-range cap before calling the API (#688)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="retention-config-max-bytes"]').setValue('100');
    await wrapper.find('[data-testid="retention-config-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putRetentionConfig).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="retention-config-error"]').text()).toContain('1024');
  });
});
