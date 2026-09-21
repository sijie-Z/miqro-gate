import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProjectsView from '@/views/next/NextProjectsView.vue';
import * as api from '@/api';
import { UiSelect } from '@/ui';
import type { AdminUser, MemberView, Project } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  listProjectMembers: vi.fn(),
  removeProjectMember: vi.fn(),
  addProjectMember: vi.fn(),
  listUsers: vi.fn(),
  listGrants: vi.fn(),
}));

const mockApi = vi.mocked(api);

const project = (overrides: Partial<Project> = {}): Project => ({
  id: 'p1',
  code: 'CORE',
  name: 'Core AI',
  status: 'ACTIVE',
  projectTag: 'core-ai',
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

describe('NextProjectsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listProjects.mockResolvedValue([
      project(),
      project({ id: 'p2', code: 'QA', name: 'QA 回归', projectTag: undefined, status: 'DISABLED' }),
    ]);
    mockApi.listUsers.mockResolvedValue([]);
    // #657: one grant on p1, one member roster for every project.
    mockApi.listGrants.mockResolvedValue([
      {
        id: 'g1',
        projectId: 'p1',
        providerProductId: 'pp1',
        upstreamCredentialId: 'c1',
        status: 'ACTIVE',
      },
    ]);
    mockApi.listProjectMembers.mockResolvedValue([member()]);
    document.body.innerHTML = '';
  });

  function mountView() {
    return mount(NextProjectsView, { global: { plugins: [createPinia()] } });
  }

  /** Hint under a UiInput — the testid falls through to the <input> itself. */
  function hintOf(wrapper: ReturnType<typeof mountView>, testid: string): string {
    const field = wrapper.find(`[data-testid="${testid}"]`).element.closest('.ui-field');
    expect(field, `${testid} should sit inside a field`).toBeTruthy();
    return field!.querySelector('.ui-field__hint')?.textContent?.trim() ?? '';
  }

  it('renders projects with tags and Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="projects-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.text()).toContain('core-ai');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('停用');
    // #657 dependency columns: p1 has one grant; every project shows its roster size.
    const grantCounts = wrapper.findAll('[data-testid="project-grant-count"]');
    expect(grantCounts[0]!.text()).toBe('1');
    expect(grantCounts[1]!.text()).toBe('0');
    const memberCounts = wrapper.findAll('[data-testid="project-member-count"]');
    expect(memberCounts[0]!.text()).toBe('1');
  });

  it('creates a project and reloads', async () => {
    mockApi.createProject.mockResolvedValue(project({ id: 'p9' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listProjects as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="project-create-open"]').trigger('click');
    await wrapper.find('[data-testid="project-create-code"]').setValue('TOOLS');
    await wrapper.find('[data-testid="project-create-name"]').setValue('工具链');
    await wrapper.find('[data-testid="project-create-tag"]').setValue('tools');
    await wrapper.find('[data-testid="project-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createProject).toHaveBeenCalledWith({
      code: 'TOOLS',
      name: '工具链',
      projectTag: 'tools',
    });
    expect((mockApi.listProjects as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });

  it('validates required code and name', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-create-open"]').trigger('click');
    await wrapper.find('[data-testid="project-create-name"]').setValue('只有名字');
    await wrapper.find('[data-testid="project-create-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('项目代码与名称必填');
    expect(mockApi.createProject).not.toHaveBeenCalled();
  });

  it('opens the member drawer and removes a member after confirmation', async () => {
    mockApi.listProjectMembers.mockResolvedValue([member()]);
    mockApi.removeProjectMember.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-members-open"]').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="project-members-drawer"]')).toBeTruthy();
    expect(document.body.textContent).toContain('alice');

    (document.querySelector('[data-testid="project-member-remove"]') as HTMLButtonElement).click();
    await flushPromises();

    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '移除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.removeProjectMember).toHaveBeenCalledWith('p1', 'u1');
  });

  it('adds a member from the picker (#556)', async () => {
    mockApi.listProjectMembers.mockResolvedValue([member()]);
    mockApi.addProjectMember.mockResolvedValue(undefined);
    mockApi.listUsers.mockResolvedValue([
      user({ id: 'u1', username: 'alice', displayName: 'Alice' }),
      user({ id: 'u2', username: 'bob', displayName: 'Bob' }),
      user({ id: 'u3', username: 'carol', status: 'DISABLED' }),
    ]);
    const wrapper = mountView();
    await flushPromises();
    // #657: load() also counts members per project — count only the drawer's calls below.
    mockApi.listProjectMembers.mockClear();

    await wrapper.find('[data-testid="project-members-open"]').trigger('click');
    await flushPromises();

    // Only bob is joinable: alice is already a member, carol is disabled.
    const select = wrapper.findComponent(UiSelect);
    expect(select.exists()).toBe(true);
    select.vm.$emit('update:modelValue', 'u2');
    await flushPromises();

    (document.querySelector('[data-testid="project-member-add"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.addProjectMember).toHaveBeenCalledWith('p1', 'u2');
    expect((mockApi.listProjectMembers as ReturnType<typeof vi.fn>).mock.calls.length).toBe(2);
  });

  it('shows the routing-tag hint on the create form (#617)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-create-open"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="project-create-tag-hint"]').text()).toContain(
      '留空将自动从项目代码派生',
    );
  });

  it('states the project code and name rules on the create form (#657)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-create-open"]').trigger('click');
    await flushPromises();

    // Rules the backend enforces (AdminOrgService#createProject, projects.code
    // / projects.name widths) must be readable before submitting, not only in
    // the 409 body.
    expect(hintOf(wrapper, 'project-create-code')).toContain('同一租户内唯一');
    expect(hintOf(wrapper, 'project-create-code')).toContain('最长 64 个字符');
    expect(hintOf(wrapper, 'project-create-name')).toContain('最长 200 个字符');
  });

  it('edits a project name and routing tag (#617)', async () => {
    mockApi.updateProject.mockResolvedValue(project({ name: '改后名称', projectTag: 'new-tag' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-edit-open"]').trigger('click');
    await flushPromises();
    expect(document.querySelector('[data-testid="project-edit-dialog"]')).toBeTruthy();
    const nameInput = document.querySelector(
      '[data-testid="project-edit-name"]',
    ) as HTMLInputElement;
    const tagInput = document.querySelector('[data-testid="project-edit-tag"]') as HTMLInputElement;
    expect(nameInput.value).toBe('Core AI');
    expect(tagInput.value).toBe('core-ai');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;

    // Clearing an existing tag is rejected locally — no API call.
    setter?.call(tagInput, '');
    tagInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="project-edit-save"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(document.querySelector('[data-testid="project-edit-error"]')?.textContent).toContain(
      '不能清空',
    );
    expect(mockApi.updateProject).not.toHaveBeenCalled();

    // A real change goes through PATCH and reloads the list.
    setter?.call(nameInput, '改后名称');
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    setter?.call(tagInput, 'new-tag');
    tagInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    const callsBefore = (mockApi.listProjects as ReturnType<typeof vi.fn>).mock.calls.length;
    (document.querySelector('[data-testid="project-edit-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.updateProject).toHaveBeenCalledWith('p1', {
      name: '改后名称',
      projectTag: 'new-tag',
    });
    expect((mockApi.listProjects as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });

  it('#1160: a failed user read in the member drawer is visible, not silent', async () => {
    mockApi.listProjectMembers.mockResolvedValue([]);
    mockApi.listUsers.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-users',
        title: 'Error',
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="project-members-open"]').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="project-members-drawer"]');
    expect(drawer, 'member drawer should render').toBeTruthy();
    // Before the fix the drawer said nothing at all when this read failed — the
    // picker was simply empty and 没有可加入… was suppressed by `usersLoaded`.
    const error = document.querySelector('[data-testid="project-users-error"]');
    expect(error, 'the failed user read must be visible').toBeTruthy();
    expect(error!.textContent).toContain('数据库不可用');
    const retry = document.querySelector(
      '[data-testid="project-users-retry"]',
    ) as HTMLButtonElement;
    expect(retry, 'a retry entry must exist').toBeTruthy();

    // Retry goes through the same loader.
    mockApi.listUsers.mockResolvedValue([user({ id: 'u2', username: 'bob' })]);
    retry.click();
    await flushPromises();

    expect(mockApi.listUsers).toHaveBeenCalledTimes(2);
    expect(document.querySelector('[data-testid="project-users-error"]')).toBeNull();
  });

  it('#PH69: a failed member load must not leave the drawer claiming「还没有成员」', async () => {
    mockApi.listProjectMembers.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-members',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="project-members-open"]').trigger('click');
    await flushPromises();

    const drawer = document.querySelector('[data-testid="project-members-drawer"]');
    expect(drawer).toBeTruthy();
    // Before the fix the drawer's only signal was a toast, gone after
    // DURATION_ERROR (7 s) while the drawer stays open. What is left is an
    // assertion about the project's roster that the failed read never
    // established. #1160 — 加载中 → 失败 → 空 → 有数据 — and the sibling users
    // list above in this same drawer already honours it.
    expect(drawer!.textContent).not.toContain('还没有成员');
    expect(drawer!.textContent).toContain('数据库不可用');
    expect(drawer!.textContent).toContain('req-members');

    // And the failure carries a way out, instead of 关掉抽屉再点开一次.
    const retry = document.querySelector(
      '[data-testid="project-members-drawer"] [data-testid="table-load-retry"]',
    );
    expect(retry, 'the members table must offer a retry').toBeTruthy();
    mockApi.listProjectMembers.mockResolvedValue([member()]);
    (retry as HTMLButtonElement).click();
    await flushPromises();

    const after = document.querySelector('[data-testid="project-members-drawer"]');
    expect(after!.textContent).toContain('alice');
    expect(after!.textContent).not.toContain('数据库不可用');
  });
});
