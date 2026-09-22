import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextSkillsView from '@/views/next/NextSkillsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { SkillView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listSkills: vi.fn(),
  downloadSkill: vi.fn(),
}));

const mockApi = vi.mocked(api);

const skill = (overrides: Partial<SkillView> = {}): SkillView => ({
  id: '0190-0000-0000-0001',
  name: 'commit-msg-lint',
  version: '1.2.0',
  description: '规范 git 提交信息并自动生成变更提示的技能包。',
  tags: ['git', 'workflow'],
  author: 'platform',
  license: 'MIT',
  contentBytes: 204_800,
  ...overrides,
});

describe('NextSkillsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listSkills.mockResolvedValue([
      skill(),
      skill({ id: '2', name: 'review-helper', version: '0.4.1', tags: ['review'] }),
    ]);
  });

  function mountView() {
    return mount(NextSkillsView, { global: { plugins: [createPinia()] } });
  }

  it('renders skill cards with tags, meta and download buttons', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="skill-grid"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="skill-card"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('commit-msg-lint');
    expect(wrapper.text()).toContain('v1.2.0');
    expect(wrapper.text()).toContain('git');
    expect(wrapper.text()).toContain('200 KB');
    expect(wrapper.text()).toContain('MIT');
  });

  it('downloads a skill and reports success', async () => {
    mockApi.downloadSkill.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-download"]').trigger('click');
    await flushPromises();

    expect(mockApi.downloadSkill).toHaveBeenCalledWith('0190-0000-0000-0001', 'commit-msg-lint');
    expect(toastState.items.some((t) => t.message.includes('commit-msg-lint'))).toBe(true);
  });

  it('maps a forbidden download to friendly guidance', async () => {
    const { ApiError } = await import('@/api/http');
    mockApi.downloadSkill.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 403,
        code: 'SKILL_DOWNLOAD_FORBIDDEN',
        title: 'Forbidden',
        requestId: 'req-1',
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-download"]').trigger('click');
    await flushPromises();

    expect(toastState.items.some((t) => t.message.includes('未授权'))).toBe(true);
  });

  it('I9: filters by keyword and tags, showing examples and the creator', async () => {
    mockApi.listSkills.mockResolvedValue([
      skill({
        id: '1',
        name: 'web-scraper',
        tags: ['git', 'workflow', 'web', 'tools'],
        examples: ['抓取 example.com 并转 markdown'],
        createdByName: 'Admin',
      }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="skill-search"]').setValue('SCRAPER');
    await wrapper.find('[data-testid="skill-search-submit"]').trigger('click');
    await flushPromises();
    expect(mockApi.listSkills).toHaveBeenLastCalledWith('SCRAPER', []);

    await wrapper.find('[data-testid="skill-tag-git"]').trigger('click');
    await flushPromises();
    expect(mockApi.listSkills).toHaveBeenLastCalledWith('SCRAPER', ['git']);

    const card = wrapper.find('[data-testid="skill-card"]');
    expect(card.text()).toContain('创建人 Admin');
    expect(card.text()).toContain('示例：抓取 example.com 并转 markdown');
    // Four tags render as three chips plus a remainder badge.
    expect(card.text()).toContain('+1');
  });

  // PH58: load() had no response-sequence guard, so a slow answer for an older
  // keyword could land after a newer search and repaint the grid with rows that
  // no longer match the search box. Neither the search button nor the tag chips
  // are disabled while a request is in flight, so two answers really can be
  // outstanding at once.
  it('drops a skills response that a newer search has already superseded', async () => {
    const pending: Array<{ q: string; resolve: (v: SkillView[]) => void }> = [];
    mockApi.listSkills.mockImplementation(
      (q?: string) =>
        new Promise<SkillView[]>((resolve) => {
          pending.push({ q: String(q ?? ''), resolve });
        }),
    );

    const wrapper = mountView();
    await flushPromises();
    expect(pending).toHaveLength(1); // the mount request is still open

    // the user types a new keyword and searches again while the first is in flight
    await wrapper.find('[data-testid="skill-search"]').setValue('SECOND');
    await wrapper.find('[data-testid="skill-search-submit"]').trigger('click');
    expect(pending).toHaveLength(2);
    expect(pending.map((p) => p.q)).toEqual(['', 'SECOND']);

    // the newer request answers first …
    pending[1]!.resolve([skill({ id: 'new', name: '新窗口技能' })]);
    await flushPromises();
    expect(wrapper.find('[data-testid="skill-grid"]').text()).toContain('新窗口技能');

    // … then the abandoned one dribbles in and must not win
    pending[0]!.resolve([skill({ id: 'old', name: '旧窗口技能' })]);
    await flushPromises();
    const grid = wrapper.find('[data-testid="skill-grid"]').text();
    expect(grid).toContain('新窗口技能');
    expect(grid, 'the abandoned first search repainted the grid').not.toContain('旧窗口技能');
  });
});
