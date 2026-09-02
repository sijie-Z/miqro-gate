import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import TDesign from 'tdesign-vue-next';
import AdminAgentsView from '@/views/AdminAgentsView.vue';
import * as api from '@/api';
import type { AgentView, CredentialView, UsageSummary } from '@/types/api';

vi.mock('@/api', () => ({
  adminListAgents: vi.fn(),
  adminCreateAgent: vi.fn(),
  adminDisableAgent: vi.fn(),
  adminAgentUsage: vi.fn(),
  listCredentials: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** See KeysView.spec.ts — popup positioning is not app logic in jsdom. */
const PopupStub = defineComponent({
  name: 'TPopup',
  inheritAttrs: false,
  setup(_, { slots, expose }) {
    expose({
      update: () => {},
      getOverlay: () => null,
      getOverlayState: () => ({ hover: false }),
      getPopper: () => null,
      close: () => {},
    });
    return () => h('div', { class: 't-popup-stub' }, [slots.default?.(), slots.content?.()]);
  },
});

const agent = (overrides: Partial<AgentView> = {}): AgentView => ({
  id: '0190-0000-0000-0000-0000000000d1',
  name: 'miqro-forge',
  description: 'Internal coding agent',
  credentialId: '0190-0000-0000-0000-0000000000e1',
  credentialName: 'Forge Cred',
  providerProductId: '0190-0000-0000-0000-0000000000f1',
  providerProductName: 'DeepSeek PAYG',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const credential = (overrides: Partial<CredentialView> = {}): CredentialView => ({
  id: '0190-0000-0000-0000-0000000000e1',
  name: 'Forge Cred',
  subscriptionId: '0190-0000-0000-0000-0000000000a1',
  status: 'ACTIVE',
  activeVersionId: 'v1',
  fingerprintPrefix: '1d740c88',
  lastValidatedAt: null,
  lastValidationError: null,
  version: 1,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const usageSummary: UsageSummary = {
  groupBy: 'project',
  groups: [],
  totals: {
    groupKey: 'total',
    label: '合计',
    requests: { upstream: 5, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 100, output: 50, cacheRead: 0, cacheCreation: 0 },
    cost: {
      upstreamPaid: '0.0010',
      gatewayObserved: '0.0010',
      projectAllocated: '0.0010',
      savedByGatewayCache: '0.0000',
    },
  },
};

function mountView() {
  return mount(AdminAgentsView, {
    global: {
      plugins: [TDesign, createPinia()],
      stubs: { TPopup: PopupStub },
    },
  });
}

describe('AdminAgentsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListAgents.mockResolvedValue([]);
    mockApi.listCredentials.mockResolvedValue([credential()]);
  });

  it('renders the agent table with derived product names', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="agents-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('miqro-forge');
    expect(wrapper.text()).toContain('Forge Cred');
    expect(wrapper.text()).toContain('DeepSeek PAYG');
    expect(wrapper.text()).toContain('Active');
  });

  it('creates an agent with name and credential', async () => {
    mockApi.adminCreateAgent.mockResolvedValue(agent());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-create-open"]').trigger('click');
    await wrapper.find('[data-testid="agent-create-name"] input').setValue('miqro-forge');

    const option = wrapper.findAll('.t-select-option').find((o) => o.text().includes('Forge Cred'));
    expect(option, 'credential options should render').toBeTruthy();
    await option!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="agent-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateAgent).toHaveBeenCalledWith({
      name: 'miqro-forge',
      description: undefined,
      credentialId: '0190-0000-0000-0000-0000000000e1',
    });
  });

  it('shows per-agent usage in the dialog', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminAgentUsage.mockResolvedValue(usageSummary);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-usage"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminAgentUsage).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000d1');
    expect(wrapper.find('[data-testid="agent-usage-grid"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('5');
    expect(wrapper.text()).toContain('100');
    expect(wrapper.text()).toContain('50');
  });

  it('disables an agent after confirming the dialog', async () => {
    mockApi.adminListAgents.mockResolvedValue([agent()]);
    mockApi.adminDisableAgent.mockResolvedValue(agent({ status: 'DISABLED' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="agent-disable"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('禁用'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.adminDisableAgent).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000d1');
  });
});
