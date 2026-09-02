import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import TDesign from 'tdesign-vue-next';
import AdminSkillsView from '@/views/AdminSkillsView.vue';
import * as api from '@/api';
import type { SkillView } from '@/types/api';

vi.mock('@/api', () => ({
  adminListSkills: vi.fn(),
  adminUploadSkill: vi.fn(),
  adminArchiveSkill: vi.fn(),
  adminSetSkillAccess: vi.fn(),
  listProjects: vi.fn(),
  listTeams: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** See KeysView.spec.ts — popup positioning is not app logic in jsdom. */
const PopupStub = defineComponent({
  name: 'TPopup',
  inheritAttrs: false,
  setup(_, { slots, expose }) {
    expose({
      update: () => {},
      getOverlay: () => null,
      getOverlayState: () => ({ hover: false }),
      getPopper: () => null,
      close: () => {},
    });
    return () => h('div', { class: 't-popup-stub' }, [slots.default?.(), slots.content?.()]);
  },
});

const skill = (overrides: Partial<SkillView> = {}): SkillView => ({
  id: '0190-0000-0000-0000-0000000000c1',
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

function mountView() {
  return mount(AdminSkillsView, {
    global: {
      plugins: [TDesign, createPinia()],
      stubs: { TPopup: PopupStub },
    },
  });
}

describe('AdminSkillsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListSkills.mockResolvedValue([]);
    mockApi.listProjects.mockResolvedValue([]);
    mockApi.listTeams.mockResolvedValue([]);
  });

  it('renders the skill table with statuses', async () => {
    mockApi.adminListSkills.mockResolvedValue([
      skill(),
      skill({ name: 'old-tool', status: 'ARCHIVED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="admin-skills-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('web-scraper');
    expect(wrapper.text()).toContain('v1.0.0');
    expect(wrapper.text()).toContain('2 KB');
    expect(wrapper.text()).toContain('Active');
    expect(wrapper.text()).toContain('Archived');
  });

  it('uploads a zip with the entered version', async () => {
    mockApi.adminUploadSkill.mockResolvedValue(skill());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-upload-open"]').trigger('click');
    await wrapper.find('[data-testid="skill-upload-version"] input').setValue('1.2.0');

    const file = new File(['zip-content'], 'web-scraper.zip', { type: 'application/zip' });
    const input = wrapper.find('[data-testid="skill-upload-file"]');
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
    await input.trigger('change');
    await flushPromises();

    await wrapper.find('[data-testid="skill-upload-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUploadSkill).toHaveBeenCalledWith('1.2.0', file);
  });

  it('archives a skill after confirming the dialog', async () => {
    mockApi.adminListSkills.mockResolvedValue([skill()]);
    mockApi.adminArchiveSkill.mockResolvedValue(skill({ status: 'ARCHIVED' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-archive"]').trigger('click');
    await flushPromises();

    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('归档'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();
    await new Promise((r) => setTimeout(r, 400));

    expect(mockApi.adminArchiveSkill).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000c1');
  });

  it('saves access scopes from the dialog', async () => {
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
    mockApi.adminSetSkillAccess.mockResolvedValue(undefined);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-access"]').trigger('click');
    await flushPromises();

    // Pick the only project option.
    const option = wrapper.findAll('.t-select-option').find((o) => o.text().includes('Core AI'));
    expect(option, 'project options should render').toBeTruthy();
    await option!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="skill-access-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminSetSkillAccess).toHaveBeenCalledWith('0190-0000-0000-0000-0000000000c1', [
      { scopeType: 'PROJECT', scopeId: 'p1' },
    ]);
  });
});
