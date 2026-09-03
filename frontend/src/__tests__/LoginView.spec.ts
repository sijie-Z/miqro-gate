import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import LoginView from '@/views/LoginView.vue';
import * as api from '@/api';

const push = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push }),
}));

vi.mock('@/api', () => ({
  login: vi.fn(),
  register: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** Auth-card interaction tests (self-registration, F-REG): the mode switch, the
 * register payload and client-side guardrails. Login flow itself is covered by
 * e2e and the auth store spec. */
describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    push.mockResolvedValue(undefined);
  });

  function mountView() {
    return mount(LoginView, { global: { plugins: [TDesign, createPinia()] } });
  }

  it('switches between login and register modes', async () => {
    const wrapper = mountView();
    expect(wrapper.text()).toContain('登录');
    expect(wrapper.find('[data-testid="tab-register"]').exists()).toBe(true);

    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('.login-title').text()).toContain('创建账号');
    expect(wrapper.find('[data-testid="register-display-name"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="register-confirm"]').exists()).toBe(true);

    await wrapper.find('[data-testid="tab-login"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.login-title').text()).toContain('登录');
    expect(wrapper.find('[data-testid="register-confirm"]').exists()).toBe(false);
  });

  it('registers with nickname and signs the user in', async () => {
    mockApi.register.mockResolvedValue({
      id: 'u9',
      username: 'newbie',
      displayName: '新同学',
      role: 'USER',
      mustChangePassword: false,
      sessionExpiresAt: '2026-08-26T00:00:00Z',
    });

    const wrapper = mountView();
    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();

    const setVal = async (testid: string, value: string) => {
      const input = wrapper.find(`${testid} input`);
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(input.element, value);
      input.element.dispatchEvent(new Event('input', { bubbles: true }));
      await flushPromises();
    };
    await setVal('[data-testid="login-username"]', 'newbie');
    await setVal('[data-testid="register-display-name"]', '新同学');
    await setVal('[data-testid="login-password"]', 'StrongPass2026!');
    await setVal('[data-testid="register-confirm"]', 'StrongPass2026!');

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(mockApi.register).toHaveBeenCalledWith('newbie', '新同学', 'StrongPass2026!');
    expect(push).toHaveBeenCalledWith('/app/overview');
  });

  it('blocks registration when the password confirmation differs', async () => {
    const wrapper = mountView();
    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();

    const setVal = async (testid: string, value: string) => {
      const input = wrapper.find(`${testid} input`);
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLInputElement.prototype,
        'value',
      )?.set;
      setter?.call(input.element, value);
      input.element.dispatchEvent(new Event('input', { bubbles: true }));
      await flushPromises();
    };
    await setVal('[data-testid="login-username"]', 'newbie');
    await setVal('[data-testid="login-password"]', 'StrongPass2026!');
    await setVal('[data-testid="register-confirm"]', 'Different2026!');

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('两次输入的密码不一致');
    expect(mockApi.register).not.toHaveBeenCalled();
  });
});
