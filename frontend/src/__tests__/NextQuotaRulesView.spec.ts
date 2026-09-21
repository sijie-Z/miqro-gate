import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextQuotaRulesView from '@/views/next/NextQuotaRulesView.vue';
import * as api from '@/api';
import type { QuotaDefaultTemplateView, QuotaRuleView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listQuotaRules: vi.fn(),
  listUsers: vi.fn(),
  listProjects: vi.fn(),
  getQuotaDefaultTemplate: vi.fn(),
  putQuotaRule: vi.fn(),
  deleteQuotaRule: vi.fn(),
  putQuotaDefaultTemplate: vi.fn(),
  enableQuotaDefaultTemplate: vi.fn(),
  disableQuotaDefaultTemplate: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
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

const rule = (overrides: Partial<QuotaRuleView> = {}): QuotaRuleView => ({
  id: 'r1',
  scopeType: 'USER',
  scopeId: 'u1',
  scopeName: 'alice',
  scopeTag: 'alice',
  metric: 'TOKENS',
  period: 'MONTHLY',
  action: 'ALERT',
  limitValue: 1_000_000,
  warnPercent: 80,
  status: 'ACTIVE',
  used: 1_100_000,
  usedPct: 110,
  level: 'EXCEEDED',
  windowFrom: '2026-09-01T00:00:00Z',
  windowTo: '2026-09-30T23:59:59Z',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  version: 1,
  ...overrides,
});

const templateState: QuotaDefaultTemplateView = {
  enabled: true,
  metric: 'TOKENS',
  period: 'MONTHLY',
  limitValue: 500_000,
  version: 2,
  updatedAt: '2026-09-01T00:00:00Z',
};

describe('NextQuotaRulesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listQuotaRules.mockResolvedValue([
      rule(),
      rule({
        id: 'r2',
        scopeType: 'PROJECT',
        scopeId: 'p1',
        scopeName: 'Core AI',
        scopeTag: 'CORE',
        metric: 'REQUESTS',
        period: 'DAILY',
        level: 'WARNING',
        usedPct: 85,
        status: 'DISABLED',
      }),
      rule({
        id: 'r3',
        scopeName: 'bob',
        scopeTag: 'bob',
        metric: 'COST',
        period: 'YEARLY',
        limitValue: 100,
        used: 92.5,
        usedPct: 92.5,
        level: 'NEAR_LIMIT',
      }),
    ]);
    mockApi.listUsers.mockResolvedValue([
      {
        id: 'u1',
        username: 'alice',
        displayName: 'Alice',
        role: 'USER',
        status: 'ACTIVE',
        mustChangePassword: false,
        createdAt: '2026-08-01T00:00:00Z',
      },
    ]);
    mockApi.listProjects.mockResolvedValue([
      {
        id: 'p1',
        code: 'CORE',
        name: 'Core AI',
        status: 'ACTIVE',
        projectTag: 'core-ai',
        createdAt: '2026-08-01T00:00:00Z',
      },
    ]);
    mockApi.getQuotaDefaultTemplate.mockResolvedValue(templateState);
  });

  function mountView() {
    return mount(NextQuotaRulesView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders rules with watermark, level and template state', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="quota-rules-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('alice');
    expect(wrapper.text()).toContain('Token 用量 · 每月 · 限额 500,000');
    expect(wrapper.find('[data-testid="quota-template-state"]').text()).toContain('已启用');
    expect(wrapper.find('[data-testid="quota-template-hint"]').text()).toContain('停用也不会删除');
    expect(wrapper.text()).toContain('超限');
    expect(wrapper.text()).toContain('预警');
    // #683: the fixed 90% tier and the COST metric render distinctly.
    expect(wrapper.text()).toContain('即将超限');
    expect(wrapper.text()).toContain('成本（¥）');
    expect(wrapper.text()).toContain('每年');
    expect(wrapper.text()).toContain('¥92.5 / ¥100');
    expect(wrapper.text()).toContain('已停用');
    expect(wrapper.text()).toContain('1,100,000');
  });

  it('#943: leaves a COST watermark unmarked while its window is fully priced', async () => {
    const wrapper = mountView();
    await flushPromises();

    // The fixture's COST row carries no pricingStatus (the API omits it for a
    // COMPLETE window), so the marker must not cry wolf on a figure that is whole.
    expect(wrapper.find('[data-testid="quota-cost-unpriced"]').exists()).toBe(false);
  });

  it('#943: marks a COST watermark as a lower bound when its window could not be fully priced', async () => {
    mockApi.listQuotaRules.mockResolvedValue([
      rule({
        id: 'r1',
        metric: 'COST',
        limitValue: 1,
        used: 0,
        usedPct: 0,
        level: 'NORMAL',
        pricingStatus: 'UNAVAILABLE',
        unpriced: { unpricedEvents: 1, unavailableEvents: 1 },
      }),
      rule({ id: 'r2', metric: 'TOKENS' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const marks = wrapper.findAll('[data-testid="quota-cost-unpriced"]');
    // Exactly the COST row: the tokens row counts its tokens in full however the
    // window happens to be priced, so a cost caveat there would be noise.
    expect(marks).toHaveLength(1);
    expect(marks[0]!.element.closest('.next-quota__bar-row')?.textContent).toContain('¥0 / ¥1');
  });

  it('creates a quota rule for a user through the inline form', async () => {
    mockApi.putQuotaRule.mockResolvedValue(rule({ id: 'r9', scopeName: 'alice' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-create-open"]').trigger('click');
    // scope type USER default; pick user option then metric/period defaults.
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text() === 'alice')!
      .trigger('click');
    await wrapper.find('[data-testid="quota-limit"]').setValue('2000000');
    await wrapper.find('[data-testid="quota-warn"]').setValue('90');
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putQuotaRule).toHaveBeenCalledWith({
      scopeType: 'USER',
      scopeId: 'u1',
      metric: 'TOKENS',
      period: 'DAILY',
      action: 'ALERT',
      limitValue: 2000000,
      warnPercent: 90,
      status: 'ACTIVE',
    });
  });

  it('renders the exceeded action and creates a REJECT rule (#684)', async () => {
    mockApi.listQuotaRules.mockResolvedValue([
      rule({ id: 'r4', action: 'REJECT', limitValue: 5000, used: 6000, usedPct: 120 }),
    ]);
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('超限拒绝');

    mockApi.putQuotaRule.mockResolvedValue(rule({ id: 'r9', scopeName: 'alice' }));
    await wrapper.find('[data-testid="quota-rule-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text() === 'alice')!
      .trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text() === '超限拒绝请求（429）')!
      .trigger('click');
    await wrapper.find('[data-testid="quota-limit"]').setValue('500000');
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putQuotaRule).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: 'REJECT', limitValue: 500000 }),
    );
  });

  it('validates limit input', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text() === 'alice')!
      .trigger('click');
    await wrapper.find('[data-testid="quota-limit"]').setValue('-5');
    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('限额必须是正整数');
    expect(mockApi.putQuotaRule).not.toHaveBeenCalled();
  });

  it('configures the default template and toggles it', async () => {
    mockApi.putQuotaDefaultTemplate.mockResolvedValue(templateState);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="quota-template-open"]').trigger('click');
    await wrapper.find('[data-testid="template-limit"]').setValue('800000');
    await wrapper.find('[data-testid="template-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putQuotaDefaultTemplate).toHaveBeenCalledWith({
      metric: 'TOKENS',
      period: 'MONTHLY',
      limitValue: 800000,
    });

    await wrapper.find('[data-testid="quota-template-toggle"]').trigger('click');
    await flushPromises();
    expect(mockApi.disableQuotaDefaultTemplate).toHaveBeenCalled();
  });

  it('deletes a rule through the confirmation gate', async () => {
    mockApi.deleteQuotaRule.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-delete"]').trigger('click');
    await flushPromises();

    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.deleteQuotaRule).toHaveBeenCalledWith('r1');
  });
});
