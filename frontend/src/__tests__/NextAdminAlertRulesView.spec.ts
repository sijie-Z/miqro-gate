import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminAlertRulesView from '@/views/next/NextAdminAlertRulesView.vue';
import * as api from '@/api';
import type { AlertRule } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listAlertRules: vi.fn(),
  createAlertRule: vi.fn(),
  updateAlertRule: vi.fn(),
  deleteAlertRule: vi.fn(),
  listWebhooks: vi.fn(),
  listQuotaRules: vi.fn(),
  listProjects: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
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
    <div class="ui-select-stub" :data-options="props.options.map((o) => o.value).join(',')">
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

const rule = (overrides: Partial<AlertRule> = {}): AlertRule => ({
  id: 'r1',
  name: 'usage-missing',
  type: 'USAGE_MISSING_RATE',
  scopeJson: undefined,
  threshold: 0.5,
  dedupeMinutes: 60,
  enabled: true,
  webhookEndpointId: undefined,
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

describe('NextAdminAlertRulesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listAlertRules.mockResolvedValue([]);
    mockApi.listWebhooks.mockResolvedValue([]);
    mockApi.listQuotaRules.mockResolvedValue([]);
    mockApi.listProjects.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextAdminAlertRulesView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders rules with Chinese type/status labels', async () => {
    mockApi.listAlertRules.mockResolvedValue([
      rule(),
      rule({ id: 'r2', name: 'budget-watch', type: 'BUDGET_THRESHOLD', enabled: false }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="rules-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('usage-missing');
    expect(wrapper.text()).toContain('usage 缺失率');
    expect(wrapper.text()).toContain('预算水位');
    expect(wrapper.text()).toContain('已启用');
    expect(wrapper.text()).toContain('已停用');
  });

  it('creates a threshold rule with a webhook endpoint', async () => {
    mockApi.listWebhooks.mockResolvedValue([
      {
        id: 'w1',
        name: 'ops-alerts',
        url: 'https://alerts.internal/hook',
        enabled: true,
        createdAt: '2026-09-01T00:00:00Z',
      },
    ]);
    mockApi.createAlertRule.mockResolvedValue(rule());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-create-open"]').trigger('click');
    await wrapper.find('[data-testid="rule-create-name"]').setValue('upstream-errors');
    // Pick the 上游错误率 type from the stub options (first stub = type).
    const typeStub = wrapper.findAll('.ui-select-stub')[0];
    await typeStub
      .findAll('.stub-option')
      .find((o) => o.text() === '上游错误率')!
      .trigger('click');
    await wrapper.find('[data-testid="rule-create-threshold"]').setValue('0.05');
    await wrapper.find('[data-testid="rule-create-dedupe"]').setValue('30');
    const webhookStub = wrapper
      .findAll('.ui-select-stub')
      .find((s) => s.text().includes('Webhook 端点'))!;
    await webhookStub.findAll('.stub-option')[0].trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createAlertRule).toHaveBeenCalledWith({
      name: 'upstream-errors',
      type: 'UPSTREAM_ERROR_RATE',
      threshold: 0.05,
      dedupeMinutes: 30,
      webhookEndpointId: 'w1',
      scopeJson: undefined,
    });
  });

  it('creates a quota-watermark rule with scopeJson', async () => {
    mockApi.listQuotaRules.mockResolvedValue([
      {
        id: 'q1',
        scopeType: 'USER',
        scopeId: 'u1',
        scopeName: 'Alice',
        scopeTag: 'alice',
        metric: 'TOKENS',
        period: 'MONTHLY',
        limitValue: 1000000,
        warnPercent: 80,
        status: 'ACTIVE',
        used: 0,
        usedPct: 0,
        level: 'NORMAL',
        windowFrom: '2026-09-01T00:00:00Z',
        windowTo: '2026-10-01T00:00:00Z',
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
        version: 1,
      },
    ]);
    mockApi.createAlertRule.mockResolvedValue(rule({ type: 'QUOTA_THRESHOLD' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-create-open"]').trigger('click');
    await wrapper.find('[data-testid="rule-create-name"]').setValue('alice-quota');
    const typeStub = wrapper.findAll('.ui-select-stub')[0];
    await typeStub
      .findAll('.stub-option')
      .find((o) => o.text() === '配额水位')!
      .trigger('click');
    await flushPromises();
    const quotaStub = wrapper.find('[data-testid="rule-quota-select"]');
    expect(quotaStub.exists()).toBe(true);
    expect(quotaStub.text()).toContain('Alice（Token·月）');
    await quotaStub.findAll('.stub-option')[0].trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createAlertRule).toHaveBeenCalledWith({
      name: 'alice-quota',
      type: 'QUOTA_THRESHOLD',
      threshold: 0.5,
      dedupeMinutes: 60,
      webhookEndpointId: undefined,
      scopeJson: JSON.stringify({ quotaRuleId: 'q1' }),
    });
  });

  it('hides threshold inputs and pins threshold 1 for approval rules', async () => {
    mockApi.createAlertRule.mockResolvedValue(rule({ type: 'MODEL_APPROVAL_SUBMITTED' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-create-open"]').trigger('click');
    const typeStub = wrapper.findAll('.ui-select-stub')[0];
    await typeStub
      .findAll('.stub-option')
      .find((o) => o.text() === '模型审批 · 提交')!
      .trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="rule-create-threshold"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="rule-approval-hint"]').exists()).toBe(true);
    await wrapper.find('[data-testid="rule-create-name"]').setValue('approval-notify');
    await wrapper.find('[data-testid="rule-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createAlertRule).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'MODEL_APPROVAL_SUBMITTED', threshold: 1 }),
    );
  });

  it('deletes a rule through the confirm gate', async () => {
    mockApi.listAlertRules.mockResolvedValue([rule()]);
    mockApi.deleteAlertRule.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="rule-delete"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.deleteAlertRule).toHaveBeenCalledWith('r1');
  });
});
