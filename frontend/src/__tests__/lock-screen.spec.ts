import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import LockScreen from '@/components/LockScreen.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({
  login: vi.fn(),
}));

const mockApi = vi.mocked(api);

const LOGIN_RESPONSE = {
  id: 'u-1',
  username: 'root',
  displayName: 'Root',
  role: 'USER',
  mustChangePassword: false,
  sessionExpiresAt: '2026-12-31T00:00:00Z',
};

function mountLock() {
  return mount(LockScreen, {
    props: { username: 'root' },
    global: { plugins: [createPinia()], stubs: { teleport: true } },
  });
}

describe('LockScreen', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
  });

  it('renders the ticking clock, the user and the unlock form', () => {
    const wrapper = mountLock();
    expect(wrapper.text()).toMatch(/\d{2}:\d{2}:\d{2}/);
    expect(wrapper.text()).toContain('root');
    expect(wrapper.find('[data-testid="lock-password"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="lock-unlock"]').exists()).toBe(true);
  });

  it('unlocks by re-verifying the entered password', async () => {
    mockApi.login.mockResolvedValue(LOGIN_RESPONSE as never);
    const wrapper = mountLock();
    await wrapper.find('[data-testid="lock-password"]').setValue('secret-1');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(mockApi.login).toHaveBeenCalledWith('root', 'secret-1');
    expect(wrapper.emitted('unlock')).toBeTruthy();
  });

  it('shows the failure message and keeps the user locked on a bad password', async () => {
    mockApi.login.mockRejectedValue(new Error('bad credentials'));
    const wrapper = mountLock();
    await wrapper.find('[data-testid="lock-password"]').setValue('wrong');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('解锁失败');
    expect(wrapper.emitted('unlock')).toBeFalsy();
  });

  it('emits logout from the escape hatch', async () => {
    const wrapper = mountLock();
    await wrapper.find('[data-testid="lock-logout"]').trigger('click');
    expect(wrapper.emitted('logout')).toBeTruthy();
  });
});
