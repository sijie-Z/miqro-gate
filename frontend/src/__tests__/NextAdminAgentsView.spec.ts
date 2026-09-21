import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminAgentsView from '@/views/next/NextAdminAgentsView.vue';
import * as api from '@/api';
import type { AgentView, UsageSummary } from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminListAgents: vi.fn(),
  adminCreateAgent: vi.fn(),
  adminDisableAgent: vi.fn(),
  adminEnableAgent: vi.fn(),
  adminUpdateAgent: vi.fn(),
  adminDeleteAgent: vi.fn(),
  adminAgentUsage: vi.fn(),
  listCredentials: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    function pick(value: unknown) {
      emit('update:modelValue', value);
      emit('change', value);
    }
    return { pick, props };
  },
  template: `
    <div class="ui-select-stub">
      <label v-if="props.label">{{ props.label }}</label>
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>
  `,
});

const agent = (overrides: Partial<AgentView> = {}): AgentView => ({
  id: 'a1',
  name: 'forge-agent',
  description: 'Forge 集成出口',
  credentialId: 'c1',
  credentialName: 'anthropic-main',
  providerProductId: 'pr1',
  providerProductName: 'Anthropic PAYG',
  status: 'ACTIVE',
  version: 0,
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const summary = (overrides: Partial<UsageSummary['totals']> = {}): UsageSummary => ({
  groupBy: 'virtual_key',
  groups: [],
  totals: {
    requests: { upstream: 120, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 240000, output: 120000, cacheRead: 0, cacheCreation: 0 },
    cost: {
      upstreamPaid: 0.0123,
      gatewayObserved: 0.0123,
      projectAllocated: 0.0123,
      savedByGatewayCache: 0,
    },
    ...overrides,
  },
});

describe('NextAdminAgentsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListAgents.mockResolvedValue([]);
    mockApi.listCredentials.mockResolvedValue([]);
  });

  function mountView(options: { attach?: boolean } = {}) {
    return mount(NextAdminAgentsView, {
      attachTo: options.attach ? document.body : undefined,
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  /** Open a row's 「更多」 menu — radix portals it into document.body. */
  async function openRowMenu(wrapper: ReturnType<typeof mountView>) {
    await wrapper.find('[data-testid="agent-actions-a1"]').trigger('click');
    await flushPromises();
  }

  function menuItem(testid: string): HTMLElement {
    const el = document.body.querySelector<HTMLElement>(`[data-testid="${testid}"]`);
    if (!el) throw new Error(`menu item ${testid} missing`);
    return el;
  }

  it('marks the allocated cost as partial when pricing is incomplete (#857)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminAgentUsage.mockResolvedValue(
      summary({ pricingStatus: 'UNAVAILABLE', unpriced: { unpricedEvents: 1 } } as never),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-usage"]').trigger('click');
    await flushPromises();

    const chip = document.querySelector('[data-testid="agent-cost-caveat"]');
    expect(chip, 'caveat chip should render').toBeTruthy();
    expect(chip!.textContent).toContain('未定价');
  });

  it('leaves the allocated cost unmarked when every event was priced', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminAgentUsage.mockResolvedValue(summary({ pricingStatus: 'COMPLETE' } as never));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-usage"]').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="agent-cost-caveat"]')).toBeNull();
  });
  it('renders agents with Chinese statuses', async () => {
    mockApi.adminListAgents.mockResolvedValue([
      agent(),
      agent({ id: 'a2', name: 'codex-agent', status: 'DISABLED' }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="agents-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('forge-agent');
    expect(wrapper.text()).toContain('anthropic-main');
    expect(wrapper.text()).toContain('Anthropic PAYG');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('已禁用');
  });

  it('degrades gracefully when an agent has no createdAt (#PH20-B)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent({ createdAt: undefined })]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="agents-table"]').text()).not.toContain('NaN');
  });

  it('does not render a null createdAt as the epoch date (#PH20-B)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent({ createdAt: null as unknown as string })]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="agents-table"]').text()).not.toContain('1970-01-01');
  });

  it('creates an agent bound to an ACTIVE credential', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.listCredentials.mockResolvedValue([
      {
        id: 'c1',
        name: 'anthropic-main',
        subscriptionId: 's1',
        status: 'ACTIVE',
        activeVersionId: 'v1',
        fingerprintPrefix: 'a1b2',
        lastValidatedAt: undefined,
        lastValidationError: undefined,
        version: 1,
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-01T00:00:00Z',
      },
      {
        id: 'c2',
        name: 'retired-key',
        subscriptionId: 's1',
        status: 'DISABLED',
        activeVersionId: 'v1',
        fingerprintPrefix: 'c3d4',
        lastValidatedAt: undefined,
        lastValidationError: undefined,
        version: 1,
        createdAt: '2026-07-01T00:00:00Z',
        updatedAt: '2026-07-01T00:00:00Z',
      },
    ]);
    mockApi.adminCreateAgent.mockResolvedValue(agent());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-create-open"]').trigger('click');
    await wrapper.find('[data-testid="agent-create-name"]').setValue('miqro-forge');
    // Only the ACTIVE credential is offered.
    const options = wrapper.findAll('.stub-option');
    expect(options).toHaveLength(1);
    expect(options[0]!.text()).toBe('anthropic-main');
    await options[0]!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="agent-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateAgent).toHaveBeenCalledWith({
      name: 'miqro-forge',
      description: undefined,
      credentialId: 'c1',
    });
  });

  it('disables an agent through the confirm gate', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminDisableAgent.mockResolvedValue(agent({ status: 'DISABLED' }));
    const wrapper = mountView({ attach: true });
    await flushPromises();

    // 禁用 moved behind the row's 「更多」 menu in #824 — same gate, new path.
    await openRowMenu(wrapper);
    menuItem('agent-disable').click();
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '禁用' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.adminDisableAgent).toHaveBeenCalledWith('a1');
  });

  it('shows per-agent usage stats in the dialog', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminAgentUsage.mockResolvedValue(summary());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-usage"]').trigger('click');
    await flushPromises();

    const grid = document.querySelector('[data-testid="agent-usage-grid"]');
    expect(grid, 'usage grid should render').toBeTruthy();
    expect(grid!.textContent).toContain('120');
    expect(grid!.textContent).toContain('240,000');
    expect(grid!.textContent).toContain('120,000');
    expect(grid!.textContent).toContain('¥0.0123');
  });

  it('enables a disabled agent from the row menu (#824)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent({ status: 'DISABLED' })]);
    mockApi.adminEnableAgent.mockResolvedValue(agent({ status: 'ACTIVE' }));
    const wrapper = mountView({ attach: true });
    await flushPromises();

    await openRowMenu(wrapper);
    menuItem('agent-enable').click();
    await flushPromises();

    expect(mockApi.adminEnableAgent).toHaveBeenCalledWith('a1');
  });

  it('renames through the dialog, echoing the row version as the lock token (#824)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent({ version: 3 })]);
    mockApi.adminUpdateAgent.mockResolvedValue(agent({ name: 'forge-renamed', version: 4 }));
    const wrapper = mountView({ attach: true });
    await flushPromises();

    await openRowMenu(wrapper);
    menuItem('agent-rename').click();
    await flushPromises();

    const input = document.body.querySelector<HTMLInputElement>(
      '[data-testid="agent-rename-name"]',
    );
    if (!input) throw new Error('rename input missing');
    input.value = 'forge-renamed';
    input.dispatchEvent(new Event('input'));
    await flushPromises();

    const submit = document.body.querySelector<HTMLElement>('[data-testid="agent-rename-submit"]');
    if (!submit) throw new Error('rename submit missing');
    submit.click();
    await flushPromises();

    expect(mockApi.adminUpdateAgent).toHaveBeenCalledWith('a1', {
      name: 'forge-renamed',
      description: 'Forge 集成出口',
      version: 3,
    });
  });

  it('deletes only after the confirmation, and says it is irreversible (#824)', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminDeleteAgent.mockResolvedValue(undefined);
    const wrapper = mountView({ attach: true });
    await flushPromises();

    await openRowMenu(wrapper);
    menuItem('agent-delete').click();
    await flushPromises();

    // The confirm dialog states the consequence before anything is deleted.
    expect(mockApi.adminDeleteAgent).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('删除不可恢复');

    const confirm = Array.from(document.body.querySelectorAll<HTMLElement>('button')).find((b) =>
      b.textContent?.includes('删除'),
    );
    if (!confirm) throw new Error('confirm button missing');
    confirm.click();
    await flushPromises();

    expect(mockApi.adminDeleteAgent).toHaveBeenCalledWith('a1');
  });
});
