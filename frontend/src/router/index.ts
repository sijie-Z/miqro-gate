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
      path: '/login-new',
      name: 'login-next',
      component: () => import('@/views/next/NextLoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/app-new',
      component: () => import('@/components/NewShell.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/app-new/keys',
        },
        {
          path: 'keys',
          name: 'next-keys',
          component: () => import('@/views/next/NextKeysView.vue'),
          meta: { title: '我的 Key' },
        },
        {
          path: 'usage',
          name: 'next-usage',
          component: () => import('@/views/next/NextUsageView.vue'),
          meta: { title: '用量' },
        },
        {
          path: 'users',
          name: 'next-users',
          component: () => import('@/views/next/NextUsersView.vue'),
          meta: adminMeta('用户'),
        },
      ],
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
          meta: { title: '我的 Key' },
        },
        {
          path: 'usage',
          name: 'usage',
          component: () => import('@/views/UsageView.vue'),
          meta: { title: '用量' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '资料' },
        },
        {
          path: 'skills',
          name: 'skills',
          component: () => import('@/views/SkillHubView.vue'),
          meta: { title: '技能库' },
        },
        {
          path: 'model-approvals',
          name: 'model-approvals',
          component: () => import('@/views/ModelApprovalsView.vue'),
          meta: { title: '模型申请' },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/AdminUsersView.vue'),
          meta: adminMeta('用户'),
        },
        {
          path: 'teams',
          name: 'teams',
          component: () => import('@/views/AdminTeamsView.vue'),
          meta: adminMeta('团队'),
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/AdminProjectsView.vue'),
          meta: adminMeta('项目'),
        },
        {
          path: 'grants',
          name: 'grants',
          component: () => import('@/views/AdminGrantsView.vue'),
          meta: adminMeta('授权'),
        },
        {
          path: 'approval-center',
          name: 'approval-center',
          component: () => import('@/views/AdminModelApprovalsView.vue'),
          meta: adminMeta('审批中心'),
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/AdminProvidersView.vue'),
          meta: adminMeta('供应商'),
        },
        {
          path: 'plans',
          name: 'plans',
          component: () => import('@/views/AdminPlansView.vue'),
          meta: adminMeta('订阅'),
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
          meta: adminMeta('上游凭证'),
        },
        {
          path: 'cost',
          name: 'cost',
          component: () => import('@/views/AdminCostView.vue'),
          meta: adminMeta('成本报表'),
        },
        {
          path: 'quota-rules',
          name: 'quota-rules',
          component: () => import('@/views/AdminQuotaRulesView.vue'),
          meta: adminMeta('配额规则'),
        },
        {
          path: 'roi',
          name: 'roi',
          component: () => import('@/views/AdminRoiView.vue'),
          meta: adminMeta('缓存收益'),
        },
        {
          path: 'admin-usage',
          name: 'admin-usage',
          component: () => import('@/views/AdminUsageView.vue'),
          meta: adminMeta('用量报表'),
        },
        {
          path: 'exports',
          name: 'exports',
          component: () => import('@/views/AdminExportsView.vue'),
          meta: adminMeta('导出任务'),
        },
        {
          path: 'deletions',
          name: 'deletions',
          component: () => import('@/views/AdminDeletionsView.vue'),
          meta: adminMeta('用量删除'),
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
          meta: adminMeta('技能库管理'),
        },
        {
          path: 'agents',
          name: 'agents',
          component: () => import('@/views/AdminAgentsView.vue'),
          meta: adminMeta('智能体'),
        },
        {
          path: 'services',
          name: 'services',
          component: () => import('@/views/AdminServicesView.vue'),
          meta: adminMeta('服务管理'),
        },
        {
          path: 'configs',
          name: 'configs',
          component: () => import('@/views/AdminConfigsView.vue'),
          meta: adminMeta('全局配置'),
        },
        {
          path: 'mcp-services',
          name: 'mcp-services',
          component: () => import('@/views/AdminMcpServicesView.vue'),
          meta: adminMeta('MCP 服务'),
        },
        {
          path: 'webhooks',
          name: 'webhooks',
          component: () => import('@/views/AdminWebhooksView.vue'),
          meta: adminMeta('Webhook 端点'),
        },
        {
          path: 'alert-rules',
          name: 'alert-rules',
          component: () => import('@/views/AdminAlertRulesView.vue'),
          meta: adminMeta('告警规则'),
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/AdminAuditView.vue'),
          meta: adminMeta('审计日志'),
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
      if (auth.mustChangePassword) return '/app/profile';
      return to.name === 'login-next' ? '/app-new/keys' : '/app/keys';
    }
    return true;
  }

  if (!auth.isAuthenticated) {
    return {
      name: to.path.startsWith('/app-new') || to.name === 'login-next' ? 'login-next' : 'login',
      query: { redirect: to.fullPath },
    };
  }

  // Password must be changed before anything else.
  if (auth.mustChangePassword && to.name !== 'profile') {
    return { name: 'profile' };
  }

  // Admin routes are SYSTEM_ADMIN-only; regular users are redirected to the
  // keys page (never to the admin route, which would render a 403 page).
  if (to.meta.requiresAdmin && auth.user?.role !== 'SYSTEM_ADMIN') {
    return to.path.startsWith('/app-new') ? { name: 'next-keys' } : { name: 'keys' };
  }

  return true;
});

export default router;
