import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextTeamsView from '@/views/next/NextTeamsView.vue';
import * as api from '@/api';
import { UiSelect } from '@/ui';
import { toastState } from '@/ui/toast';
import type { AdminUser, MemberView, Team } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listTeams: vi.fn(),
  createTeam: vi.fn(),
  listTeamMembers: vi.fn(),
  removeTeamMember: vi.fn(),
  addTeamMember: vi.fn(),
  listUsers: vi.fn(),
}));

const mockApi = vi.mocked(api);

const team = (overrides: Partial<Team> = {}): Team => ({
  id: 't1',
  name: 'platform-sre',
  description: '平台稳定性值守',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const member = (overrides: Partial<MemberView> = {}): MemberView => ({
  userId: 'u1',
  username: 'alice',
  displayName: 'Alice',
  createdAt: '2026-08-02T00:00:00Z',
  ...overrides,
});

const user = (overrides: Partial<AdminUser> = {}): AdminUser => ({
  id: 'u2',
  username: 'bob',
  displayName: 'Bob',
  role: 'USER',
  status: 'ACTIVE',
  ...overrides,
});

describe('NextTeamsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listTeams.mockResolvedValue([
      team(),
      team({ id: 't2', name: 'qa', status: 'DISABLED' }),
    ]);
    mockApi.listUsers.mockResolvedValue([]);
    document.body.innerHTML = '';
  });

  function mountView() {
    return mount(NextTeamsView, { global: { plugins: [createPinia()] } });
  }

  /** Hint under a UiInput — the testid falls through to the <input> itself. */
  function hintOf(wrapper: ReturnType<typeof mountView>, testid: string): string {
    const field = wrapper.find(`[data-testid="${testid}"]`).element.closest('.ui-field');
    expect(field, `${testid} should sit inside a field`).toBeTruthy();
    return field!.querySelector('.ui-field__hint')?.textContent?.trim() ?? '';
  }

  it('renders teams with Chinese statuses and member action', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="teams-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('platform-sre');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('停用');
  });

  it('creates a team and reloads the list', async () => {
    mockApi.createTeam.mockResolvedValue(team({ id: 't9' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listTeams as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="team-create-open"]').trigger('click');
    await wrapper.find('[data-testid="team-create-name"]').setValue('data-plat');
    await wrapper.find('[data-testid="team-create-description"]').setValue('数据平台团队');
    await wrapper.find('[data-testid="team-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createTeam).toHaveBeenCalledWith({
      name: 'data-plat',
      description: '数据平台团队',
    });
    expect((mockApi.listTeams as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });

  it('states the team name rule on the create form (#657)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="team-create-open"]').trigger('click');
    await flushPromises();

    // teams.name is varchar(200) NOT NULL — the rule belongs next to the field.
    expect(hintOf(wrapper, 'team-create-name')).toContain('必填');
    expect(hintOf(wrapper, 'team-create-name')).toContain('最长 200 个字符');
  });

  it('opens the member drawer and removes a member after confirmation', async () => {
    mockApi.listTeamMembers.mockResolvedValue([member()]);
    mockApi.removeTeamMember.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="team-members-open"]').trigger('click');
    await flushPromises();

    // Drawer teleports into <body>.
    const drawer = document.querySelector('[data-testid="team-members-drawer"]');
    expect(drawer).toBeTruthy();
    expect(document.body.textContent).toContain('alice');

    (document.querySelector('[data-testid="team-member-remove"]') as HTMLButtonElement).click();
    await flushPromises();
    const confirmText = document.body.textContent ?? '';
    expect(confirmText).toContain('移除成员');

    // Click the danger confirm button inside the gating dialog (the drawer's
    // 移除 row action is also labelled 移除, so match the danger variant).
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '移除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.removeTeamMember).toHaveBeenCalledWith('t1', 'u1');
  });

  it('adds a member from the picker (#551)', async () => {
    mockApi.listTeamMembers.mockResolvedValue([member()]);
    mockApi.addTeamMember.mockResolvedValue(undefined);
    mockApi.listUsers.mockResolvedValue([
      user({ id: 'u1', username: 'alice', displayName: 'Alice' }),
      user({ id: 'u2', username: 'bob', displayName: 'Bob' }),
      user({ id: 'u3', username: 'carol', status: 'DISABLED' }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="team-members-open"]').trigger('click');
    await flushPromises();

    // Only bob is joinable: alice is already a member, carol is disabled.
    const select = wrapper.findComponent(UiSelect);
    expect(select.exists()).toBe(true);
    select.vm.$emit('update:modelValue', 'u2');
    await flushPromises();

    (document.querySelector('[data-testid="team-member-add"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.addTeamMember).toHaveBeenCalledWith('t1', 'u2');
    expect((mockApi.listTeamMembers as ReturnType<typeof vi.fn>).mock.calls.length).toBe(2);
  });
});
