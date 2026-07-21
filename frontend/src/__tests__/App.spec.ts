import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { createRouter, createWebHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import App from '@/App.vue';
import HomeView from '@/views/HomeView.vue';

describe('App', () => {
  it('renders the home view via router', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/', name: 'home', component: HomeView }],
    });

    router.push('/');
    await router.isReady();

    const wrapper = mount(App, {
      global: {
        plugins: [router, ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('MiQroKey Gateway');
  });
});
