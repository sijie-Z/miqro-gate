import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import TDesign from 'tdesign-vue-next';
import AdminAlertRulesView from '@/views/AdminAlertRulesView.vue';
import * as api from '@/api';
import type { AlertRule, Project, QuotaRuleView } from '@/types/api';

vi.mock('@/api', () => ({
  listAlertRules: vi.fn(),
  listWebhooks: vi.fn(),
  listProjects: vi.fn(),
  listQuotaRules: vi.fn(),
  createAlertRule: vi.fn(),
  updateAlertRule: vi.fn(),
  deleteAlertRule: vi.fn(),
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

const project: Project = {
  id: 'p1',
  code: 'CORE',
  name: 'Core AI',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
};

const quotaRule: QuotaRuleView = {
  id: 'q1',
  scopeType: 'USER',
  scopeId: 'u1',
  scopeName: '张三',
  scopeTag: 'zhangsan',
  metric: 'TOKENS',
  period: 'MONTHLY',
  limitValue: 100000,
  warnPercent: 80,
  status: 'ACTIVE',
  used: 0,
  usedPct: 0,
  level: 'NORMAL',
  windowFrom: '2026-09-01T00:00:00Z',
  windowTo: '2026-10-01T00:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  version: 0,
};

const rule = (overrides: Partial<AlertRule> = {}): AlertRule => ({
  id: 'r1',
  name: 'usage-rule',
  type: 'USAGE_MISSING_RATE',
  threshold: 0.5,
  dedupeMinutes: 60,
  enabled: true,
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminAlertRulesView, {
    global: {
      plugins: [TDesign, createPinia()],
      stubs: { TPopup: PopupStub },
    },
  });
}

describe('AdminAlertRulesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listAlertRules.mockResolvedValue([]);
    mockApi.listWebhooks.mockResolvedValue([]);
    mockApi.listProjects.mockResolvedValue([project]);
    mockApi.listQuotaRules.mockResolvedValue([quotaRule]);
  });

  it('renders a budget watermark rule with its project scope hint', async () => {
    mockApi.listAlertRules.mockResolvedValue([
      rule({
        name: '预算告警',
        type: 'BUDGET_THRESHOLD',
        scopeJson: '{"projectId":"p1"}',
        threshold: 80,
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('预算水位');
    expect(wrapper.text()).toContain('Core AI（CORE）');
    expect(wrapper.text()).toContain('80');
  });

  it('creates a budget rule only after a project is selected, sending the scope', async () => {
    mockApi.createAlertRule.mockResolvedValue(rule({ type: 'BUDGET_THRESHOLD' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-create-open"]').trigger('click');
    await wrapper.find('[data-testid="rule-create-name"] input').setValue('预算告警');

    // Pick the budget type from the type select.
    const typeOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('预算水位'));
    expect(typeOption, 'budget type option should render').toBeTruthy();
    await typeOption!.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="rule-project-select"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('阈值（水位 %）');

    // Submitting without a project is rejected client-side.
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    expect(wrapper.text()).toContain('预算水位规则必须选择项目。');
    expect(mockApi.createAlertRule).not.toHaveBeenCalled();

    // Pick the project, then submit.
    const projectOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('Core AI'));
    expect(projectOption, 'project option should render').toBeTruthy();
    await projectOption!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createAlertRule).toHaveBeenCalledWith({
      name: '预算告警',
      type: 'BUDGET_THRESHOLD',
      threshold: 0.5,
      dedupeMinutes: 60,
      webhookEndpointId: undefined,
      scopeJson: '{"projectId":"p1"}',
    });
  });

  it('renders a quota watermark rule with its rule scope hint', async () => {
    mockApi.listAlertRules.mockResolvedValue([
      rule({
        name: '配额告警',
        type: 'QUOTA_THRESHOLD',
        scopeJson: '{"quotaRuleId":"q1"}',
        threshold: 80,
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('配额水位');
    expect(wrapper.text()).toContain('张三（Token·月）');
    expect(wrapper.text()).toContain('80');
  });

  it('creates a quota rule alert only after a quota rule is selected, sending the scope', async () => {
    mockApi.createAlertRule.mockResolvedValue(rule({ type: 'QUOTA_THRESHOLD' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-create-open"]').trigger('click');
    await wrapper.find('[data-testid="rule-create-name"] input').setValue('配额告警');

    const typeOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('配额水位'));
    expect(typeOption, 'quota type option should render').toBeTruthy();
    await typeOption!.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="rule-quota-select"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('阈值（水位 %）');

    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    expect(wrapper.text()).toContain('配额水位规则必须选择配额规则。');
    expect(mockApi.createAlertRule).not.toHaveBeenCalled();

    const quotaOption = wrapper.findAll('.t-select-option').find((o) => o.text().includes('张三'));
    expect(quotaOption, 'quota rule option should render').toBeTruthy();
    await quotaOption!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createAlertRule).toHaveBeenCalledWith({
      name: '配额告警',
      type: 'QUOTA_THRESHOLD',
      threshold: 0.5,
      dedupeMinutes: 60,
      webhookEndpointId: undefined,
      scopeJson: '{"quotaRuleId":"q1"}',
    });
  });
});
