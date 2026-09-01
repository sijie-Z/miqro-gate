import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import SkillHubView from '@/views/SkillHubView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { SkillView } from '@/types/api';

vi.mock('@/api', () => ({
  listSkills: vi.fn(),
  downloadSkill: vi.fn(),
}));

const mockApi = vi.mocked(api);

const skill = (overrides: Partial<SkillView> = {}): SkillView => ({
  id: '0190-0000-0000-0000-0000000000c1',
  name: 'web-scraper',
  description: 'Scrapes public web pages into markdown.',
  version: '1.0.0',
  author: 'Platform Team',
  license: 'MIT',
  tags: ['scraping', 'web'],
  contentSha256: 'aa'.repeat(32),
  contentBytes: 2048,
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(SkillHubView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('SkillHubView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listSkills.mockResolvedValue([]);
  });

  it('renders the skill catalog with metadata', async () => {
    mockApi.listSkills.mockResolvedValue([skill()]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="skill-grid"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('web-scraper');
    expect(wrapper.text()).toContain('v1.0.0');
    expect(wrapper.text()).toContain('Scrapes public web pages into markdown.');
    expect(wrapper.text()).toContain('scraping');
    expect(wrapper.text()).toContain('Platform Team');
    expect(wrapper.text()).toContain('2 KB');
  });

  it('downloads a skill package with its name', async () => {
    mockApi.listSkills.mockResolvedValue([skill()]);
    mockApi.downloadSkill.mockResolvedValue(undefined);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-download"]').trigger('click');
    await flushPromises();

    expect(mockApi.downloadSkill).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000c1',
      'web-scraper',
    );
  });

  it('shows a friendly message when the download is not granted', async () => {
    mockApi.listSkills.mockResolvedValue([skill()]);
    mockApi.downloadSkill.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: 'Forbidden',
        status: 403,
        code: 'SKILL_DOWNLOAD_FORBIDDEN',
        detail: '当前账号无该技能的下载授权。',
        requestId: 'req-1',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-download"]').trigger('click');
    await flushPromises();

    expect(mockApi.downloadSkill).toHaveBeenCalledTimes(1);
  });

  it('shows an empty state', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('技能目录还是空的');
  });
});
