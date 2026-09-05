import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextTeamsView from '@/views/next/NextTeamsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {MemberView} from '@/types/api';
import type { Team } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listTeams: vi.fn(),
  createTeam: vi.fn(),
  listTeamMembers: vi.fn(),
  removeTeamMember: vi.fn(),
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

describe('NextTeamsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listTeams.mockResolvedValue([
      team(),
      team({ id: 't2', name: 'qa', status: 'DISABLED' }),
    ]);
    document.body.innerHTML = '';
  });

  function mountView() {
    return mount(NextTeamsView, { global: { plugins: [createPinia()] } });
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
});
