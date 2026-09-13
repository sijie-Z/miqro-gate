import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive, ref } from 'vue';
import NextProfileView from '@/views/next/NextProfileView.vue';

const push = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push }),
}));

// #440: the flag must be REACTIVE (like the real store's ref-backed computed) —
// the previous plain-property mock never invalidated the component's computed,
// masking the dead-code redirect.
const mustChangePassword = ref(false);
const authMock = reactive({
  user: {
    username: 'demo2_user',
    displayName: 'Demo 用户',
    role: 'USER',
    sessionExpiresAt: '2026-09-04T00:00:00Z',
  },
  get mustChangePassword() {
    return mustChangePassword.value;
  },
  changePassword: vi.fn(async () => {
    mustChangePassword.value = false;
  }),
});

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => authMock,
}));

describe('NextProfileView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    push.mockResolvedValue(undefined);
    mustChangePassword.value = false;
    authMock.user.role = 'USER';
  });

  function mountView() {
    return mount(NextProfileView, { global: { plugins: [createPinia()] } });
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
    expect(wrapper.text()).toContain('资料');
    expect(wrapper.find('[data-testid="account-username"]').text()).toBe('demo2_user');
    expect(wrapper.find('[data-testid="current-password"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="forced-password"]').exists()).toBe(false);
  });

  it('shows the forced password banner and redirects after change', async () => {
    mustChangePassword.value = true;

    const wrapper = mountView();
    expect(wrapper.find('[data-testid="forced-password"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('设置新密码');

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
    await setField(wrapper, 'current-password', 'old-pass');
    await setField(wrapper, 'new-password', 'StrongPass2026!');
    await setField(wrapper, 'confirm-password', 'Different2026!');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('[data-testid="field-error"]').text()).toContain('两次输入的新密码不一致');
    expect(authMock.changePassword).not.toHaveBeenCalled();
  });
});
