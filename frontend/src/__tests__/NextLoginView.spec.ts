import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextLoginView from '@/views/next/NextLoginView.vue';
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

/** New-console login card (UI U0): mode switch, register payload and client
 *  guardrails. Real login flow is covered by e2e and the auth store spec. */
describe('NextLoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    push.mockResolvedValue(undefined);
  });

  function mountView() {
    return mount(NextLoginView, { global: { plugins: [createPinia()] } });
  }

  async function setField(wrapper: ReturnType<typeof mountView>, testid: string, value: string) {
    const input = wrapper.find(`${testid}`);
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(input.element, value);
    input.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
  }

  it('switches between login and register modes', async () => {
    const wrapper = mountView();
    expect(wrapper.find('[data-testid="tab-register"]').exists()).toBe(true);

    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('创建账号');
    expect(wrapper.find('[data-testid="register-display-name"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="register-confirm"]').exists()).toBe(true);

    await wrapper.find('[data-testid="tab-login"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('登录 MiQroGate');
    expect(wrapper.find('[data-testid="register-confirm"]').exists()).toBe(false);
  });

  it('requires both fields on login', async () => {
    const wrapper = mountView();
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(wrapper.find('[data-testid="login-error"]').text()).toContain('请输入账号和密码');
    expect(mockApi.login).not.toHaveBeenCalled();
  });

  it('registers with nickname and lands on the new console keys page', async () => {
    mockApi.register.mockResolvedValue({ id: 'u9' });

    const wrapper = mountView();
    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();

    await setField(wrapper, '[data-testid="login-username"]', 'newbie');
    await setField(wrapper, '[data-testid="register-display-name"]', '新同学');
    await setField(wrapper, '[data-testid="login-password"]', 'StrongPass2026!');
    await setField(wrapper, '[data-testid="register-confirm"]', 'StrongPass2026!');

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(mockApi.register).toHaveBeenCalledWith('newbie', '新同学', 'StrongPass2026!');
    expect(push).toHaveBeenCalledWith('/app-new/keys');
  });

  it('blocks registration when the password confirmation differs', async () => {
    const wrapper = mountView();
    await wrapper.find('[data-testid="tab-register"]').trigger('click');
    await flushPromises();

    await setField(wrapper, '[data-testid="login-username"]', 'newbie');
    await setField(wrapper, '[data-testid="login-password"]', 'StrongPass2026!');
    await setField(wrapper, '[data-testid="register-confirm"]', 'Different2026!');

    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('[data-testid="login-error"]').text()).toContain('两次输入的密码不一致');
    expect(mockApi.register).not.toHaveBeenCalled();
  });

  it('toggles password visibility', async () => {
    const wrapper = mountView();
    await setField(wrapper, '[data-testid="login-password"]', 'hunter2');
    const input = wrapper.find('[data-testid="login-password"]');
    expect(input.attributes('type')).toBe('password');

    await wrapper.find('[data-testid="password-toggle"]').trigger('click');
    await flushPromises();
    expect(input.attributes('type')).toBe('text');
  });
});
