import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextSkillsView from '@/views/next/NextSkillsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {} from '@/types/api';
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
});
