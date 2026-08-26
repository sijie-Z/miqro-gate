import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/app',
      component: () => import('@/components/AppShell.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/app/keys',
        },
        {
          path: 'keys',
          name: 'keys',
          component: () => import('@/views/KeysView.vue'),
          meta: { title: 'Virtual Keys' },
        },
        {
          path: 'usage',
          name: 'usage',
          component: () => import('@/views/UsageView.vue'),
          meta: { title: 'Usage' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: 'Profile' },
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: 'Providers' },
        },
        {
          path: 'plans',
          name: 'plans',
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: 'Plans' },
        },
        {
          path: 'credentials',
          name: 'credentials',
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: 'Credentials' },
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: 'Audit' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: 'Settings' },
        },
      ],
    },
    {
      path: '/',
      redirect: '/app/keys',
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/app/keys',
    },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (!auth.loaded) {
    await auth.fetchMe();
  }

  if (to.meta.public) {
    if (auth.isAuthenticated) {
      return auth.mustChangePassword ? '/app/profile' : '/app/keys';
    }
    return true;
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  // Password must be changed before anything else.
  if (auth.mustChangePassword && to.name !== 'profile') {
    return { name: 'profile' };
  }

  return true;
});

export default router;
