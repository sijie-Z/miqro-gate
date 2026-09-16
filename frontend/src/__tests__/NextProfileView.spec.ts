import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive, ref } from 'vue';
import NextProfileView from '@/views/next/NextProfileView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { UsageSummary, VirtualKeyView } from '@/types/generated-api';

const push = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push }),
}));

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  usageSummary: vi.fn(),
  logoutOtherSessions: vi.fn(),
}));

const mockApi = vi.mocked(api);

// #440: the flag must be REACTIVE (like the real store's ref-backed computed) —
// the previous plain-property mock never invalidated the component's computed,
// masking the dead-code redirect.
const mustChangePassword = ref(false);
const authMock = reactive({
  user: {
    username: 'demo2_user',
    displayName: 'Demo 用户',
    role: 'USER',
    status: 'ACTIVE',
    sessionExpiresAt: '2026-09-04T00:00:00Z',
    lastLoginAt: '2026-09-03T08:30:00Z',
  },
  get mustChangePassword() {
    return mustChangePassword.value;
  },
  fetchMe: vi.fn(async () => {}),
  changePassword: vi.fn(async () => {
    mustChangePassword.value = false;
  }),
});

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authMock,
}));

const activeKey = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView =>
  ({
    id: '0190-0001',
    name: 'claude-code-main',
    status: 'ACTIVE',
    ...overrides,
  }) as VirtualKeyView;

const summary: UsageSummary = {
  groupBy: 'project',
  groups: [],
  totals: {
    requests: { upstream: 1200, coalesced: 100, l1Hit: 400, l2Hit: 300 },
    tokens: { input: 2400, output: 600 },
    cost: { upstreamPaid: 12.34 },
  },
};

describe('NextProfileView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    push.mockResolvedValue(undefined);
    mustChangePassword.value = false;
    authMock.user.role = 'USER';
    mockApi.listVirtualKeys.mockResolvedValue([activeKey()]);
    mockApi.usageSummary.mockResolvedValue(summary);
  });

  function mountView() {
    return mount(NextProfileView, {
      global: {
        plugins: [createPinia()],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    });
  }

  async function setField(wrapper: ReturnType<typeof mountView>, testid: string, value: string) {
    const input = wrapper.find(`[data-testid="${testid}"]`);
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(input.element, value);
    input.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
  }

  it('renders account facts and password form', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('资料');
    expect(wrapper.find('[data-testid="account-username"]').text()).toBe('demo2_user');
    expect(wrapper.find('[data-testid="current-password"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="forced-password"]').exists()).toBe(false);
  });

  it('renders the identity header and the monthly snapshot (#597)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const identity = wrapper.find('[data-testid="profile-identity"]');
    expect(identity.exists()).toBe(true);
    expect(identity.text()).toContain('Demo 用户');
    expect(identity.text()).toContain('@demo2_user');
    expect(identity.text()).toContain('普通用户');

    const snapshot = wrapper.find('[data-testid="profile-snapshot"]');
    expect(snapshot.text()).toContain('可用虚拟密钥');
    expect(snapshot.text()).toContain('2.0k'); // 1200+100+400+300 requests
    expect(snapshot.text()).toContain('3.0k'); // 2400+600 tokens
    expect(snapshot.text()).toContain('12.34');

    // Design pass: every stat renders its tinted icon chip, and each card
    // head carries its small icon.
    expect(snapshot.findAll('.next-profile__stat-chip').length).toBe(4);
    expect(wrapper.find('.next-profile__stat-chip--blue').exists()).toBe(true);
    expect(wrapper.findAll('.next-profile__card-icon').length).toBe(3);

    // Session facts come from the refreshed /auth/me payload.
    expect(wrapper.find('[data-testid="last-login"]').text()).not.toBe('—');
  });

  it('degrades the snapshot to em dashes when the stats APIs fail', async () => {
    mockApi.listVirtualKeys.mockRejectedValue(new Error('network down'));
    mockApi.usageSummary.mockRejectedValue(new Error('network down'));

    const wrapper = mountView();
    await flushPromises();

    const snapshot = wrapper.find('[data-testid="profile-snapshot"]');
    expect(snapshot.text()).toContain('—');
    expect(wrapper.find('[data-testid="profile-snapshot-error"]').exists()).toBe(true);
    // The rest of the page still renders.
    expect(wrapper.find('[data-testid="account-username"]').text()).toBe('demo2_user');
  });

  it('signs out of other sessions through the confirm dialog', async () => {
    mockApi.logoutOtherSessions.mockResolvedValue({
      message: 'Other sessions have been revoked.',
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="logout-others"]').trigger('click');
    await flushPromises();

    // The dialog teleports to body — query the real DOM.
    const confirm = document.querySelector(
      '[data-testid="logout-others-confirm"]',
    ) as HTMLButtonElement;
    expect(confirm).toBeTruthy();
    confirm.click();
    await flushPromises();

    expect(mockApi.logoutOtherSessions).toHaveBeenCalledTimes(1);
  });

  it('surfaces sign-out-others failures inside the dialog', async () => {
    mockApi.logoutOtherSessions.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'boom',
        requestId: 'req-9',
        title: 'Internal Server Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="logout-others"]').trigger('click');
    await flushPromises();
    const confirm = document.querySelector(
      '[data-testid="logout-others-confirm"]',
    ) as HTMLButtonElement;
    confirm.click();
    await flushPromises();

    const error = document.querySelector('[data-testid="logout-others-error"]');
    expect(error?.textContent).toContain('req-9');
  });

  it('shows the forced password banner and redirects after change', async () => {
    mustChangePassword.value = true;

    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="forced-password"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('设置新密码');
    // Forced mode keeps the minimal layout: no snapshot / identity header.
    expect(wrapper.find('[data-testid="profile-identity"]').exists()).toBe(false);
    expect(mockApi.listVirtualKeys).not.toHaveBeenCalled();

    await setField(wrapper, 'current-password', 'TempPass2026!');
    await setField(wrapper, 'new-password', 'StrongPass2026!');
    await setField(wrapper, 'confirm-password', 'StrongPass2026!');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(authMock.changePassword).toHaveBeenCalledWith('TempPass2026!', 'StrongPass2026!');
    expect(push).toHaveBeenCalledWith('/app-new/keys');
  });

  it('blocks a mismatched confirmation', async () => {
    const wrapper = mountView();
    await flushPromises();
    await setField(wrapper, 'current-password', 'old-pass');
    await setField(wrapper, 'new-password', 'StrongPass2026!');
    await setField(wrapper, 'confirm-password', 'Different2026!');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('[data-testid="field-error"]').text()).toContain('两次输入的新密码不一致');
    expect(authMock.changePassword).not.toHaveBeenCalled();
  });
});
