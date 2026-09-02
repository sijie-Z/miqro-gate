import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminQuotaRulesView from '@/views/AdminQuotaRulesView.vue';
import * as api from '@/api';
import type { AdminUser, Project, QuotaRuleView } from '@/types/api';

vi.mock('@/api', () => ({
  listQuotaRules: vi.fn(),
  listUsers: vi.fn(),
  listProjects: vi.fn(),
  putQuotaRule: vi.fn(),
  deleteQuotaRule: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** Inline TPopup stub — t-select panels otherwise rely on popper timing (see ModelApprovalsView.spec). */
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

const user = (overrides: Partial<AdminUser> = {}): AdminUser => ({
  id: '0190-0000-0000-0000-0000000000d1',
  username: 'zhangsan',
  displayName: '张三',
  role: 'USER',
  status: 'ACTIVE',
  mustChangePassword: false,
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const project = (overrides: Partial<Project> = {}): Project => ({
  id: '0190-0000-0000-0000-0000000000b1',
  code: 'P1',
  name: 'Project One',
  status: 'ACTIVE',
  ...overrides,
});

const rule = (overrides: Partial<QuotaRuleView> = {}): QuotaRuleView => ({
  id: '0190-0000-0000-0000-0000000000e1',
  scopeType: 'USER',
  scopeId: user().id,
  scopeName: '张三',
  scopeTag: 'zhangsan',
  metric: 'TOKENS',
  period: 'MONTHLY',
  limitValue: 100000,
  warnPercent: 80,
  status: 'ACTIVE',
  used: 125000,
  usedPct: 125,
  level: 'EXCEEDED',
  windowFrom: '2026-09-01T00:00:00Z',
  windowTo: '2026-10-01T00:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  version: 0,
  ...overrides,
});

function mountView() {
  return mount(AdminQuotaRulesView, {
    global: { plugins: [TDesign, createPinia()], stubs: { TPopup: PopupStub } },
  });
}

describe('AdminQuotaRulesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listUsers.mockResolvedValue([user()]);
    mockApi.listProjects.mockResolvedValue([project()]);
    mockApi.listQuotaRules.mockResolvedValue([]);
  });

  it('renders rules with scope, watermark and level', async () => {
    mockApi.listQuotaRules.mockResolvedValue([rule()]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('张三');
    expect(wrapper.text()).toContain('zhangsan');
    expect(wrapper.text()).toContain('Token 用量');
    expect(wrapper.text()).toContain('每月');
    expect(wrapper.text()).toContain('125,000');
    expect(wrapper.text()).toContain('125');
    expect(wrapper.text()).toContain('超限');
  });

  it('creates a rule for a selected user scope', async () => {
    mockApi.putQuotaRule.mockResolvedValue(rule({ used: 0, usedPct: 0, level: 'NORMAL' }));

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-create-open"]').trigger('click');
    await flushPromises();

    // Pick the user from the t-select (popup stubbed inline).
    const scopeSelect = wrapper.find('[data-testid="quota-scope"]');
    const options = scopeSelect.findAll('.t-select-option');
    const target = options.find((o) => o.text().includes('张三'));
    expect(target, 'scope option should render via the popup stub').toBeTruthy();
    await target!.trigger('click');
    await flushPromises();

    const limitInput = wrapper.find('[data-testid="quota-limit"] input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(limitInput.element, '100000');
    limitInput.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putQuotaRule).toHaveBeenCalledWith({
      scopeType: 'USER',
      scopeId: user().id,
      metric: 'TOKENS',
      period: 'DAILY',
      limitValue: 100000,
      warnPercent: 80,
      status: 'ACTIVE',
    });
  });

  it('edits an existing rule keeping its key tuple', async () => {
    mockApi.listQuotaRules.mockResolvedValue([rule()]);
    mockApi.putQuotaRule.mockResolvedValue(rule({ limitValue: 200000 }));

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-edit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('编辑配额规则');
    const warnInput = wrapper.find('[data-testid="quota-warn"] input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(warnInput.element, '90');
    warnInput.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putQuotaRule).toHaveBeenCalledWith({
      scopeType: 'USER',
      scopeId: user().id,
      metric: 'TOKENS',
      period: 'MONTHLY',
      limitValue: 100000,
      warnPercent: 90,
      status: 'ACTIVE',
    });
  });

  it('validates the limit before saving', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-create-open"]').trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="quota-rule-save"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请选择配额对象');
    expect(mockApi.putQuotaRule).not.toHaveBeenCalled();
  });

  it('deletes after confirming the dialog', async () => {
    mockApi.listQuotaRules.mockResolvedValue([rule()]);
    mockApi.deleteQuotaRule.mockResolvedValue(undefined);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="quota-rule-delete"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('删除'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.deleteQuotaRule).toHaveBeenCalledWith(rule().id);
  });
});
