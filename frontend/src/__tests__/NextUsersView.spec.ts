import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextUsersView from '@/views/next/NextUsersView.vue';
import * as api from '@/api';
import type { AdminUser } from '@/types/api';

vi.mock('@/api', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUserStatus: vi.fn(),
  resetUserPassword: vi.fn(),
  revokeUserSessions: vi.fn(),
}));

const mockApi = vi.mocked(api);

const user = (overrides: Partial<AdminUser> = {}): AdminUser => ({
  id: 'u1',
  username: 'alice',
  displayName: 'Alice',
  role: 'USER',
  status: 'ACTIVE',
  mustChangePassword: false,
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

describe('NextUsersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listUsers.mockResolvedValue([
      user(),
      user({ id: 'u2', username: 'root', displayName: '', role: 'SYSTEM_ADMIN' }),
      user({ id: 'u3', username: 'locked-acc', displayName: '', status: 'LOCKED' }),
    ]);
    document.body.innerHTML = '';
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  function mountView() {
    return mount(NextUsersView, { global: { plugins: [createPinia()] } });
  }

  async function setField(wrapper: ReturnType<typeof mountView>, testid: string, value: string) {
    const input = wrapper.find(`${testid}`);
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(input.element, value);
    input.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
  }

  it('renders users with Chinese role labels and status badges', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="users-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('alice');
    expect(wrapper.text()).toContain('Alice');
    expect(wrapper.text()).toContain('系统管理员');
    expect(wrapper.find('[data-testid="users-summary"]').text()).toContain('共 3 个账号');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('锁定');
  });

  it('surfaces the load error alert with request id', async () => {
    mockApi.listUsers.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-500',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    const alert = wrapper.find('[data-testid="users-load-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('数据库不可用');
    expect(alert.text()).toContain('req-500');
  });

  it('validates username on create and blocks submission', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="user-create-open"]').trigger('click');
    await wrapper.find('[data-testid="user-create-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="user-create-error"]').text()).toContain('请输入用户名');
    expect(mockApi.createUser).not.toHaveBeenCalled();
  });

  it('creates a user and reveals the one-time temporary password (ack required)', async () => {
    mockApi.createUser.mockResolvedValue({
      user: user({ id: 'u9', username: 'newbie', displayName: '新同学', role: 'USER' }),
      temporaryPassword: 'TempPass2026!',
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="user-create-open"]').trigger('click');
    await setField(wrapper, '[data-testid="user-create-username"]', 'newbie');
    await setField(wrapper, '[data-testid="user-create-display"]', '新同学');
    await wrapper.find('[data-testid="user-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createUser).toHaveBeenCalledWith({
      username: 'newbie',
      displayName: '新同学',
      role: 'USER',
    });
    // Password dialog is teleported — read the real DOM.
    expect(document.body.textContent).toContain('TempPass2026!');
    const closeButton = document.querySelector(
      '[data-testid="temp-password-close"]',
    ) as HTMLButtonElement;
    expect(closeButton).toBeTruthy();
    expect(closeButton.disabled).toBe(true);

    (document.querySelector('[data-testid="temp-password-ack"]') as HTMLInputElement).click();
    await flushPromises();
    expect(closeButton.disabled).toBe(false);
  });

  it('reloads the list when the create form is submitted', async () => {
    mockApi.createUser.mockResolvedValue({
      user: user({ id: 'u9', username: 'newbie' }),
      temporaryPassword: 'TempPass2026!',
    });

    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listUsers as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="user-create-open"]').trigger('click');
    await setField(wrapper, '[data-testid="user-create-username"]', 'newbie');
    await wrapper.find('[data-testid="user-create-submit"]').trigger('click');
    await flushPromises();

    expect((mockApi.listUsers as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });
});
