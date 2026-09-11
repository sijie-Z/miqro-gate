import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminSkillsView from '@/views/next/NextAdminSkillsView.vue';
import * as api from '@/api';
import type { SkillView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminListSkills: vi.fn(),
  adminListSkillRevisions: vi.fn(),
  adminActivateSkillRevision: vi.fn(),
  adminUploadSkill: vi.fn(),
  adminArchiveSkill: vi.fn(),
  adminSetSkillAccess: vi.fn(),
  listProjects: vi.fn(),
  listTeams: vi.fn(),
}));

const mockApi = vi.mocked(api);

const skill = (overrides: Partial<SkillView> = {}): SkillView => ({
  id: 's1',
  name: 'web-scraper',
  description: 'Scrapes public web pages into markdown.',
  version: '1.0.0',
  author: 'Platform Team',
  license: 'MIT',
  tags: ['scraping'],
  contentSha256: 'aa'.repeat(32),
  contentBytes: 2048,
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

describe('NextAdminSkillsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListSkills.mockResolvedValue([]);
    mockApi.listProjects.mockResolvedValue([]);
    mockApi.listTeams.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextAdminSkillsView, { global: { plugins: [createPinia()] } });
  }

  it('renders skills with Chinese statuses and sizes', async () => {
    mockApi.adminListSkills.mockResolvedValue([
      skill(),
      skill({ id: 's2', name: 'old-tool', status: 'ARCHIVED', contentBytes: 2097152 }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="admin-skills-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('web-scraper');
    expect(wrapper.text()).toContain('v1.0.0');
    expect(wrapper.text()).toContain('2 KB');
    expect(wrapper.text()).toContain('2.0 MB');
    expect(wrapper.text()).toContain('已发布');
    expect(wrapper.text()).toContain('已归档');
  });

  it('uploads a zip with the entered version', async () => {
    mockApi.adminUploadSkill.mockResolvedValue(skill());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-upload-open"]').trigger('click');
    await wrapper.find('[data-testid="skill-upload-version"]').setValue('1.2.0');

    const file = new File(['zip-content'], 'web-scraper.zip', { type: 'application/zip' });
    const input = wrapper.find('[data-testid="skill-upload-file"]');
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
    await input.trigger('change');
    await flushPromises();

    await wrapper.find('[data-testid="skill-upload-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUploadSkill).toHaveBeenCalledWith('1.2.0', file);
  });

  it('archives a skill through the confirm gate', async () => {
    mockApi.adminListSkills.mockResolvedValue([skill()]);
    mockApi.adminArchiveSkill.mockResolvedValue(skill({ status: 'ARCHIVED' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-archive"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '归档' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.adminArchiveSkill).toHaveBeenCalledWith('s1');
  });

  it('saves access scopes from the dialog checkboxes', async () => {
    mockApi.adminListSkills.mockResolvedValue([skill()]);
    mockApi.listProjects.mockResolvedValue([
      {
        id: 'p1',
        code: 'CORE',
        name: 'Core AI',
        status: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);
    mockApi.listTeams.mockResolvedValue([
      {
        id: 't1',
        name: 'Platform',
        status: 'ACTIVE',
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);
    mockApi.adminSetSkillAccess.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-access"]').trigger('click');
    await flushPromises();

    const check = (id: string) => {
      const el = document.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
      el.checked = true;
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    check('skill-access-project');
    await flushPromises();
    // The team box stays unchecked — scopes must only contain the project.
    const team = document.querySelectorAll('[data-testid="skill-access-team"]');
    expect(team).toHaveLength(1);
    expect((team[0] as HTMLInputElement).checked).toBe(false);

    const save = document.querySelector('[data-testid="skill-access-save"]') as HTMLButtonElement;
    save.click();
    await flushPromises();

    expect(mockApi.adminSetSkillAccess).toHaveBeenCalledWith('s1', [
      { scopeType: 'PROJECT', scopeId: 'p1' },
    ]);
  });

  it('lists revisions and rolls back through the confirm gate (I14)', async () => {
    mockApi.adminListSkills.mockResolvedValue([skill()]);
    mockApi.adminListSkillRevisions.mockResolvedValue([
      {
        id: 'r2',
        skillId: 's1',
        revision: 2,
        version: '1.1.0',
        contentBytes: 2048,
        createdAt: '2026-09-01T10:00:00Z',
        activatedAt: '2026-09-01T10:00:00Z',
      },
      {
        id: 'r1',
        skillId: 's1',
        revision: 1,
        version: '1.0.0',
        contentBytes: 1024,
        createdAt: '2026-08-01T10:00:00Z',
      },
    ]);
    mockApi.adminActivateSkillRevision.mockResolvedValue({
      revision: 1,
      activatedAt: '2026-09-11T10:00:00Z',
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-revisions"]').trigger('click');
    await flushPromises();
    expect(mockApi.adminListSkillRevisions).toHaveBeenCalledWith('s1');
    // Dialogs teleport to body.
    expect(document.body.textContent).toContain('当前版本');
    expect(document.body.textContent).toContain('r1');

    (document.querySelector('[data-testid="skill-rollback"]') as HTMLButtonElement).click();
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '回滚' && b.className.includes('ui-btn--primary'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.adminActivateSkillRevision).toHaveBeenCalledWith('s1', 1);
  });
});
