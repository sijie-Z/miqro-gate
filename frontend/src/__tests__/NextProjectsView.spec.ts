import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProjectsView from '@/views/next/NextProjectsView.vue';
import * as api from '@/api';
import type { MemberView, Project } from '@/types/api';

vi.mock('@/api', () => ({
  listProjects: vi.fn(),
  createProject: vi.fn(),
  listProjectMembers: vi.fn(),
  removeProjectMember: vi.fn(),
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

describe('NextProjectsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listProjects.mockResolvedValue([
      project(),
      project({ id: 'p2', code: 'QA', name: 'QA 回归', projectTag: undefined, status: 'DISABLED' }),
    ]);
    document.body.innerHTML = '';
  });

  function mountView() {
    return mount(NextProjectsView, { global: { plugins: [createPinia()] } });
  }

  it('renders projects with tags and Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="projects-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.text()).toContain('core-ai');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('停用');
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
});
