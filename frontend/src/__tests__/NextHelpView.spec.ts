import { afterEach, describe, expect, it } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import NextHelpView from '@/views/next/NextHelpView.vue';

enableAutoUnmount(afterEach);

async function mountHelp() {
  const wrapper = mount(NextHelpView);
  await flushPromises();
  await nextTick();
  return wrapper;
}

describe('NextHelpView (#869)', () => {
  it('bundles the handbook docs and renders the index by default', async () => {
    const wrapper = await mountHelp();
    const content = wrapper.find('[data-testid="help-content"]');
    expect(content.exists()).toBe(true);
    // The README is the landing document.
    expect(content.html()).toContain('MiQroGate 使用手册');
    // All six documents are offered in the side nav.
    expect(wrapper.find('[data-testid="help-doc-quickstart"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="help-doc-user-guide"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="help-doc-admin-guide"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="help-doc-developer-guide"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="help-doc-faq"]').exists()).toBe(true);
  });

  it('switches documents and rebuilds the table of contents', async () => {
    const wrapper = await mountHelp();
    await wrapper.find('[data-testid="help-doc-quickstart"]').trigger('click');
    await flushPromises();
    await nextTick();

    const content = wrapper.find('[data-testid="help-content"]');
    expect(content.html()).toContain('快速上手');
    expect(wrapper.findAll('.next-help__toc-item').length).toBeGreaterThan(2);
  });

  it('rewrites relative doc links to GitHub and targets them at a new tab', async () => {
    const wrapper = await mountHelp();
    const links = wrapper.findAll('[data-testid="help-content"] a');
    expect(links.length).toBeGreaterThan(0);
    // Exact-value assertion (a substring check is both weaker and a CodeQL
    // "incomplete URL sanitization" anti-pattern).
    const expected =
      'https://github.com/sijie-Z/miqro-gate/blob/develop/docs/user-guide/quickstart.md';
    const docLink = links.find((a) => a.attributes('href') === expected);
    expect(docLink, 'quickstart.md should be rewritten to its GitHub blob URL').toBeTruthy();
    expect(docLink!.attributes('target')).toBe('_blank');
    expect(docLink!.attributes('rel')).toBe('noopener');
  });
});
