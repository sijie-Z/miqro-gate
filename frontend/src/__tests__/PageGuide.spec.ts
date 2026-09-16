import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import PageGuide from '@/ui/PageGuide.vue';
import {
  CONSUMERS_GUIDE,
  CREDENTIALS_GUIDE,
  GRANTS_GUIDE,
  KEYS_GUIDE,
  PROJECTS_GUIDE,
  PROVIDERS_GUIDE,
  type PageGuideContent,
} from '@/content/pageGuides';

const guide: PageGuideContent = {
  title: '接入一家新供应商',
  steps: [
    { title: '登记订阅', desc: '第一步说明。', to: '/app/plans', toText: '前往「订阅」' },
    { title: '录入凭证', desc: '第二步说明。' },
    {
      title: '拉取模型目录',
      desc: '第三步说明。',
      docHref: 'https://example.com/doc.md',
      docText: '查看接入文档',
    },
    { title: '授权给项目', desc: '第四步说明。' },
  ],
};

const STATE_KEY = 'miqrokey.page-guide.test-page';

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/app/plans', component: { template: '<div />' } },
    ],
  });
}

function mountGuide() {
  return mount(PageGuide, {
    props: { guide, storageKey: 'test-page' },
    global: { plugins: [makeRouter()] },
  });
}

describe('UiPageGuide', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('renders the numbered steps with cross-page and documentation links', () => {
    const wrapper = mountGuide();

    expect(wrapper.find('[data-testid="page-guide"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('使用指引');
    expect(wrapper.text()).toContain('接入一家新供应商');
    expect(wrapper.findAll('.ui-page-guide__step')).toHaveLength(4);

    const links = wrapper.findAll('a');
    const plans = links.find((a) => a.text().includes('前往「订阅」'))!;
    expect(plans.attributes('href')).toBe('/app/plans');
    const doc = links.find((a) => a.text().includes('查看接入文档'))!;
    expect(doc.attributes('href')).toBe('https://example.com/doc.md');
    expect(doc.attributes('target')).toBe('_blank');
    expect(doc.attributes('rel')).toContain('noopener');
  });

  it('collapses to a slim bar and remembers the state per page', async () => {
    const wrapper = mountGuide();
    await wrapper.find('[data-testid="page-guide-collapse"]').trigger('click');

    expect(wrapper.find('.ui-page-guide--collapsed').exists()).toBe(true);
    expect(wrapper.findAll('.ui-page-guide__step')).toHaveLength(0);
    expect(localStorage.getItem(STATE_KEY)).toBe('collapsed');

    const reminder = mountGuide();
    expect(reminder.find('.ui-page-guide--collapsed').exists()).toBe(true);

    await reminder.find('[data-testid="page-guide-expand"]').trigger('click');
    expect(reminder.findAll('.ui-page-guide__step')).toHaveLength(4);
    expect(localStorage.getItem(STATE_KEY)).toBe('expanded');
  });

  it('hides for good after 不再显示', async () => {
    const wrapper = mountGuide();
    await wrapper.find('[data-testid="page-guide-hide"]').trigger('click');
    expect(wrapper.find('[data-testid="page-guide"]').exists()).toBe(false);
    expect(localStorage.getItem(STATE_KEY)).toBe('hidden');

    const reminder = mountGuide();
    expect(reminder.find('[data-testid="page-guide"]').exists()).toBe(false);
  });
});

describe('page-guide content', () => {
  const guides = [
    PROVIDERS_GUIDE,
    CREDENTIALS_GUIDE,
    CONSUMERS_GUIDE,
    KEYS_GUIDE,
    GRANTS_GUIDE,
    PROJECTS_GUIDE,
  ];

  it('keeps every guide at 3–4 steps with imperative titles and non-empty copy', () => {
    for (const item of guides) {
      expect(item.title.length).toBeGreaterThan(0);
      expect(item.steps.length).toBeGreaterThanOrEqual(3);
      expect(item.steps.length).toBeLessThanOrEqual(4);
      for (const step of item.steps) {
        expect(step.title.length).toBeGreaterThan(0);
        expect(step.desc.length).toBeGreaterThan(0);
        // A cross-page link always travels with its label (and vice versa).
        expect(Boolean(step.to)).toBe(Boolean(step.toText));
        expect(Boolean(step.docHref)).toBe(Boolean(step.docText));
      }
    }
  });

  it('points every cross-page link at an in-app /app route', () => {
    for (const item of guides) {
      for (const step of item.steps) {
        if (step.to) expect(step.to).toMatch(/^\/app\//);
        if (step.docHref) expect(step.docHref).toMatch(/^https:\/\/github\.com\//);
      }
    }
  });
});
