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
      component: () => import('@/views/next/NextLoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/login-new',
      redirect: '/login',
    },
    {
      path: '/app',
      component: () => import('@/components/NewShell.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          redirect: '/app/overview',
        },
        {
          path: 'overview',
          name: 'overview',
          component: () => import('@/views/next/NextOverviewView.vue'),
          meta: { title: '总览' },
        },
        {
          path: 'keys',
          name: 'keys',
          component: () => import('@/views/next/NextKeysView.vue'),
          meta: { title: '我的 Key' },
        },
        {
          path: 'usage',
          name: 'usage',
          component: () => import('@/views/next/NextUsageView.vue'),
          meta: { title: '用量' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/next/NextProfileView.vue'),
          meta: { title: '资料' },
        },
        {
          path: 'skills',
          name: 'skills',
          component: () => import('@/views/next/NextSkillsView.vue'),
          meta: { title: '技能库' },
        },
        {
          path: 'model-approvals',
          name: 'model-approvals',
          component: () => import('@/views/next/NextModelApprovalsView.vue'),
          meta: { title: '模型申请' },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/next/NextUsersView.vue'),
          meta: adminMeta('用户'),
        },
        {
          path: 'teams',
          name: 'teams',
          component: () => import('@/views/next/NextTeamsView.vue'),
          meta: adminMeta('团队'),
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/next/NextProjectsView.vue'),
          meta: adminMeta('项目'),
        },
        {
          path: 'grants',
          name: 'grants',
          component: () => import('@/views/next/NextGrantsView.vue'),
          meta: adminMeta('授权'),
        },
        {
          path: 'approval-center',
          name: 'approval-center',
          component: () => import('@/views/next/NextApprovalCenterView.vue'),
          meta: adminMeta('审批中心'),
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/next/NextProvidersView.vue'),
          meta: adminMeta('供应商'),
        },
        {
          path: 'plans',
          name: 'plans',
          component: () => import('@/views/next/NextPlansView.vue'),
          meta: adminMeta('订阅'),
        },
        {
          path: 'prices',
          name: 'prices',
          component: () => import('@/views/next/NextPricesView.vue'),
          meta: adminMeta('定价'),
        },
        {
          path: 'credentials',
          name: 'credentials',
          component: () => import('@/views/next/NextCredentialsView.vue'),
          meta: adminMeta('上游凭证'),
        },
        {
          path: 'cost',
          name: 'cost',
          component: () => import('@/views/next/NextCostView.vue'),
          meta: adminMeta('成本报表'),
        },
        {
          path: 'quota-rules',
          name: 'quota-rules',
          component: () => import('@/views/next/NextQuotaRulesView.vue'),
          meta: adminMeta('配额规则'),
        },
        {
          path: 'roi',
          name: 'roi',
          component: () => import('@/views/next/NextRoiView.vue'),
          meta: adminMeta('缓存收益'),
        },
        {
          path: 'admin-usage',
          name: 'admin-usage',
          component: () => import('@/views/next/NextAdminUsageView.vue'),
          meta: adminMeta('用量报表'),
        },
        {
          path: 'exports',
          name: 'exports',
          component: () => import('@/views/next/NextAdminExportsView.vue'),
          meta: adminMeta('导出任务'),
        },
        {
          path: 'deletions',
          name: 'deletions',
          component: () => import('@/views/next/NextAdminDeletionsView.vue'),
          meta: adminMeta('用量删除'),
        },
        {
          path: 'consumers',
          name: 'consumers',
          component: () => import('@/views/next/NextAdminConsumersView.vue'),
          meta: adminMeta('API 消费者'),
        },
        {
          path: 'skillhub',
          name: 'skillhub',
          component: () => import('@/views/next/NextAdminSkillsView.vue'),
          meta: adminMeta('技能库管理'),
        },
        {
          path: 'agents',
          name: 'agents',
          component: () => import('@/views/next/NextAdminAgentsView.vue'),
          meta: adminMeta('智能体'),
        },
        {
          path: 'services',
          name: 'services',
          component: () => import('@/views/next/NextAdminServicesView.vue'),
          meta: adminMeta('服务管理'),
        },
        {
          path: 'configs',
          name: 'configs',
          component: () => import('@/views/next/NextAdminConfigsView.vue'),
          meta: adminMeta('全局配置'),
        },
        {
          path: 'mcp-services',
          name: 'mcp-services',
          component: () => import('@/views/next/NextAdminMcpServicesView.vue'),
          meta: adminMeta('MCP 服务'),
        },
        {
          path: 'webhooks',
          name: 'webhooks',
          component: () => import('@/views/next/NextAdminWebhooksView.vue'),
          meta: adminMeta('Webhook 端点'),
        },
        {
          path: 'alert-rules',
          name: 'alert-rules',
          component: () => import('@/views/next/NextAdminAlertRulesView.vue'),
          meta: adminMeta('告警规则'),
        },
        {
          path: 'audit',
          name: 'audit',
          component: () => import('@/views/next/NextAdminAuditView.vue'),
          meta: adminMeta('审计日志'),
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/next/NextSettingsView.vue'),
          meta: adminMeta('Settings'),
        },
      ],
    },
    {
      // Pilot prefix retired in U1-2 — old pilot URLs keep working via redirect.
      path: '/app-new/:pathMatch(.*)*',
      redirect: (to) => '/app' + to.path.slice('/app-new'.length),
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
      return '/app/keys';
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
