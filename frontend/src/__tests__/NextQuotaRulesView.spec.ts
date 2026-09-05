import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextQuotaRulesView from '@/views/next/NextQuotaRulesView.vue';
import * as api from '@/api';
import type {QuotaDefaultTemplateView} from '@/types/api';
import type { QuotaRuleView } from '@/types/generated-api';

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
    expect(wrapper.text()).toContain('超限');
    expect(wrapper.text()).toContain('预警');
    expect(wrapper.text()).toContain('已停用');
    expect(wrapper.text()).toContain('1,100,000');
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
      limitValue: 2000000,
      warnPercent: 90,
      status: 'ACTIVE',
    });
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
