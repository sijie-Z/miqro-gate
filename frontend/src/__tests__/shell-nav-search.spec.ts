/**
 * Rail menu search + top route progress bar (Vben parity: 菜单搜索 / 顶部进度条).
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import NewShell from '@/components/NewShell.vue';
import { initPreferences, setPreference } from '@/preferences';
import { useAuthStore } from '@/stores/auth';

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true });
}

const StubView = defineComponent({ name: 'StubView', template: '<div />' });
const REGULAR_NAV = ['overview', 'keys', 'usage', 'skills', 'model-approvals', 'profile', 'help'];

async function mountShell() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.user = {
    id: 'u-1',
    username: 'demo',
    displayName: 'Demo',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: false,
    sessionExpiresAt: '2026-12-31T00:00:00Z',
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: REGULAR_NAV.map((name) => ({ path: `/app/${name}`, name, component: StubView })),
  });
  await router.push('/app/keys');
  await router.isReady();
  const wrapper = mount(NewShell, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return wrapper;
}

describe('rail menu search', () => {
  beforeEach(() => {
    localStorage.clear();
    initPreferences();
    setViewportWidth(1280);
    setPreference('collapsed', false);
  });

  it('renders the search field and filters nav items live', async () => {
    const wrapper = await mountShell();
    const input = wrapper.find('[data-testid="shell-nav-search"]');
    expect(input.exists()).toBe(true);

    await input.setValue('密钥');
    const labels = wrapper.findAll('.new-shell__nav-label').map((el) => el.text());
    expect(labels).toContain('我的密钥');
    expect(labels).not.toContain('总览');
    expect(labels).not.toContain('技能库');
  });

  it('shows the empty hint when nothing matches', async () => {
    const wrapper = await mountShell();
    await wrapper.find('[data-testid="shell-nav-search"]').setValue('zzz-不存在');
    expect(wrapper.text()).toContain('无匹配菜单');
    expect(wrapper.findAll('.new-shell__nav-item').length).toBe(0);
  });

  it('clearing the query restores every item', async () => {
    const wrapper = await mountShell();
    const input = wrapper.find('[data-testid="shell-nav-search"]');
    await input.setValue('密钥');
    await input.setValue('');
    expect(wrapper.findAll('.new-shell__nav-item').length).toBe(REGULAR_NAV.length);
  });

  it('renders the route progress bar element', async () => {
    const wrapper = await mountShell();
    expect(wrapper.find('.new-shell__progress').exists()).toBe(true);
  });
});
