import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

function adminMeta(title: string) {
  return { title, requiresAdmin: true };
}

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
          redirect: '/app/overview',
        },
        {
          path: 'overview',
          name: 'overview',
          component: () => import('@/views/OverviewView.vue'),
          meta: { title: '总览' },
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
          meta: adminMeta('Usage'),
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: 'Profile' },
        },
        {
          path: 'skills',
          name: 'skills',
          component: () => import('@/views/SkillHubView.vue'),
          meta: { title: 'SkillHub' },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/AdminUsersView.vue'),
          meta: adminMeta('Users'),
        },
        {
          path: 'teams',
          name: 'teams',
          component: () => import('@/views/AdminTeamsView.vue'),
          meta: adminMeta('Teams'),
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/AdminProjectsView.vue'),
          meta: adminMeta('Projects'),
        },
        {
          path: 'grants',
          name: 'grants',
          component: () => import('@/views/AdminGrantsView.vue'),
          meta: adminMeta('Grants'),
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/AdminProvidersView.vue'),
          meta: adminMeta('Providers'),
        },
        {
          path: 'plans',
          name: 'plans',
          component: () => import('@/views/AdminPlansView.vue'),
          meta: adminMeta('Plans'),
        },
        {
          path: 'prices',
          name: 'prices',
          component: () => import('@/views/AdminPricesView.vue'),
          meta: adminMeta('定价'),
        },
        {
          path: 'credentials',
          name: 'credentials',
          component: () => import('@/views/AdminCredentialsView.vue'),
          meta: adminMeta('Credentials'),
        },
        {
          path: 'cost',
          name: 'cost',
          component: () => import('@/views/AdminCostView.vue'),
          meta: adminMeta('成本报表'),
        },
        {
          path: 'admin-usage',
          name: 'admin-usage',
          component: () => import('@/views/AdminUsageView.vue'),
          meta: adminMeta('Usage'),
        },
        {
          path: 'exports',
          name: 'exports',
          component: () => import('@/views/AdminExportsView.vue'),
          meta: adminMeta('Exports'),
        },
        {
          path: 'deletions',
          name: 'deletions',
          component: () => import('@/views/AdminDeletionsView.vue'),
          meta: adminMeta('Usage Deletions'),
        },
        {
          path: 'consumers',
          name: 'consumers',
          component: () => import('@/views/AdminConsumersView.vue'),
          meta: adminMeta('API 消费者'),
        },
        {
          path: 'skillhub',
          name: 'skillhub',
          component: () => import('@/views/AdminSkillsView.vue'),
          meta: adminMeta('SkillHub 管理'),
        },
        {
          path: 'agents',
          name: 'agents',
          component: () => import('@/views/AdminAgentsView.vue'),
          meta: adminMeta('Agents'),
        },
        {
          path: 'webhooks',
          name: 'webhooks',
          component: () => import('@/views/AdminWebhooksView.vue'),
          meta: adminMeta('Webhooks'),
        },
        {
          path: 'alert-rules',
          name: 'alert-rules',
          component: () => import('@/views/AdminAlertRulesView.vue'),
          meta: adminMeta('Alert Rules'),
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/AdminAuditView.vue'),
          meta: adminMeta('Audit'),
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/AdminDeployInfoView.vue'),
          meta: adminMeta('Settings'),
        },
      ],
    },
    {
      path: '/',
      redirect: '/app/overview',
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/app/overview',
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

  // Admin routes are SYSTEM_ADMIN-only; regular users are redirected to the
  // keys page (never to the admin route, which would render a 403 page).
  if (to.meta.requiresAdmin && auth.user?.role !== 'SYSTEM_ADMIN') {
    return { name: 'keys' };
  }

  return true;
});

export default router;
