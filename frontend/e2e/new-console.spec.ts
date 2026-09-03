import { test, expect, type Page } from '@playwright/test';

/**
 * New-console pilot (/app-new/*, UI U0) — functional smoke over the v2 pages:
 * shell guards, keys create-cascade basics, real radix-vue menu interactions
 * (rotate/revoke through the confirm gate), usage quota/summary/records with
 * paging, admin user list + temp-password dialog gating. API fully mocked.
 * Screenshots land in test-results/baseline/ for the vision review loop.
 */

const ADMIN_USER = {
  id: '0190-0000-0000-0001',
  username: 'root',
  displayName: 'Root Admin',
  role: 'SYSTEM_ADMIN',
  mustChangePassword: false,
};

const REGULAR_USER = {
  id: '0190-0000-0000-0009',
  username: 'demo2_user',
  displayName: 'Demo 用户',
  role: 'USER',
  mustChangePassword: false,
};

const GRANTS = {
  projects: [{ id: '0190-0000-0000-00a1', code: 'LIVE', name: 'LIVE 项目', projectTag: 'live' }],
  grants: [
    {
      id: '0190-0000-0000-00b1',
      projectId: '0190-0000-0000-00a1',
      providerProductId: 'deepseek-v4',
      models: ['deepseek-v4-flash', 'deepseek-v4.1'],
    },
  ],
  purposes: ['CLAUDE_CODE', 'CLAUDE_DESKTOP', 'CODEX', 'CUSTOM'],
};

const KEYS = [
  {
    id: '0190-0000-0000-0002',
    name: 'claude-code-main',
    display: 'mqk_live_…8f2a',
    displayPrefix: 'mqk_live_abcdefghijklmnopqrstuv',
    lastFour: '8f2a',
    purpose: 'CLAUDE_CODE',
    status: 'ACTIVE',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4-flash'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-08-01T00:00:00Z',
    lastUsedAt: '2026-08-26T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0003',
    name: 'codex-tools',
    display: 'mqk_live_…1b4c',
    displayPrefix: 'mqk_live_uvwxyz',
    lastFour: '1b4c',
    purpose: 'CODEX',
    status: 'ACTIVE',
    cachePolicy: 'ENABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4.1'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-08-05T00:00:00Z',
    lastUsedAt: null,
  },
  {
    id: '0190-0000-0000-0004',
    name: 'claude-code-legacy',
    display: 'mqk_live_…7c21',
    displayPrefix: 'mqk_live_oldkeyprefix',
    lastFour: '7c21',
    purpose: 'CLAUDE_CODE',
    status: 'ROTATING',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4-flash'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-07-12T00:00:00Z',
    lastUsedAt: '2026-09-01T12:30:00Z',
  },
  {
    id: '0190-0000-0000-0005',
    name: 'glm-agent-main',
    display: 'mqk_live_…90f3',
    displayPrefix: 'mqk_live_glmagent00',
    lastFour: '90f3',
    purpose: 'CUSTOM',
    status: 'ACTIVE',
    cachePolicy: 'ENABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['glm-5.1'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-08-14T09:15:00Z',
    lastUsedAt: '2026-09-03T02:05:00Z',
  },
  {
    id: '0190-0000-0000-0006',
    name: 'desktop-sync',
    display: 'mqk_live_…4ba9',
    displayPrefix: 'mqk_live_desktopkey',
    lastFour: '4ba9',
    purpose: 'CLAUDE_DESKTOP',
    status: 'ACTIVE',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4-flash', 'deepseek-v4.1'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-08-20T14:00:00Z',
    lastUsedAt: '2026-09-02T18:44:00Z',
  },
  {
    id: '0190-0000-0000-0007',
    name: 'retired-qa-key',
    display: 'mqk_live_…e08d',
    displayPrefix: 'mqk_live_retiredqa0',
    lastFour: 'e08d',
    purpose: 'CUSTOM',
    status: 'REVOKED',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4-flash'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-06-01T00:00:00Z',
    revokedAt: '2026-08-28T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0008',
    name: 'suspended-tools',
    display: 'mqk_live_…c5d7',
    displayPrefix: 'mqk_live_suspended00',
    lastFour: 'c5d7',
    purpose: 'CODEX',
    status: 'DISABLED',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['deepseek-v4.1'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-07-30T10:00:00Z',
    lastUsedAt: '2026-08-25T16:20:00Z',
  },
  {
    id: '0190-0000-0000-000a',
    name: 'kimi-coding-daily',
    display: 'mqk_live_…22f6',
    displayPrefix: 'mqk_live_kimicoding',
    lastFour: '22f6',
    purpose: 'CLAUDE_CODE',
    status: 'ACTIVE',
    cachePolicy: 'DISABLED',
    projectId: '0190-0000-0000-00a1',
    projectTag: 'live',
    modelIds: ['kimi-k2.5'],
    baseUrl: 'https://gateway.test.internal',
    createdAt: '2026-09-01T08:00:00Z',
    lastUsedAt: '2026-09-03T09:12:00Z',
  },
];

const GROUPS = [
  {
    groupKey: 'live',
    label: 'LIVE 项目',
    requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 120_000, output: 40_000, cacheRead: 8_000, cacheCreation: 15_000 },
    cost: { upstreamPaid: '0.320000', gatewayObserved: '0.002000' },
  },
  {
    groupKey: 'coding-plan',
    label: '编码计划',
    requests: { upstream: 9, coalesced: 0, l1Hit: 1, l2Hit: 0 },
    tokens: { input: 64_000, output: 21_000, cacheRead: 0, cacheCreation: 2_400 },
    cost: { upstreamPaid: '0.152000', gatewayObserved: '0.001100' },
  },
  {
    groupKey: 'agent-exp',
    label: '智能体实验',
    requests: { upstream: 6, coalesced: 1, l1Hit: 2, l2Hit: 0 },
    tokens: { input: 41_000, output: 12_000, cacheRead: 1_200, cacheCreation: 3_800 },
    cost: { upstreamPaid: '0.090000', gatewayObserved: '0.000700' },
  },
  {
    groupKey: 'qa-suite',
    label: 'QA 回归',
    requests: { upstream: 4, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 18_000, output: 9_600, cacheRead: 0, cacheCreation: 900 },
    cost: { upstreamPaid: '0.048000', gatewayObserved: '0.000300' },
  },
  {
    groupKey: 'docs-writer',
    label: '文档写作',
    requests: { upstream: 3, coalesced: 0, l1Hit: 1, l2Hit: 0 },
    tokens: { input: 9_500, output: 7_200, cacheRead: 400, cacheCreation: 600 },
    cost: { upstreamPaid: '0.034000', gatewayObserved: '0.000200' },
  },
  {
    groupKey: 'support-triage',
    label: '工单初筛',
    requests: { upstream: 2, coalesced: 0, l1Hit: 0, l2Hit: 0 },
    tokens: { input: 6_200, output: 2_100, cacheRead: 0, cacheCreation: 300 },
    cost: { upstreamPaid: '0.016000', gatewayObserved: '0.000100' },
  },
];

const USAGE_SUMMARY = {
  groupBy: 'project',
  groups: GROUPS,
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 36, coalesced: 3, l1Hit: 8, l2Hit: 1 },
    tokens: { input: 258_700, output: 91_900, cacheRead: 9_600, cacheCreation: 23_000 },
    cost: { upstreamPaid: '0.660000', gatewayObserved: '0.004400' },
  },
};

const QUOTA_RULES = [
  {
    id: '0190-0000-0000-00d1',
    scopeType: 'USER',
    scopeId: '0190-0000-0000-0009',
    scopeName: 'demo2_user',
    scopeTag: 'demo2_user',
    metric: 'TOKENS',
    period: 'MONTHLY',
    limitValue: 2_000_000,
    warnPercent: 80,
    status: 'ACTIVE',
    used: 160_000,
    usedPct: 8,
    level: 'NORMAL',
    windowFrom: '2026-09-01T00:00:00Z',
    windowTo: '2026-09-30T23:59:59Z',
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    version: 1,
  },
];

const USERS = [
  {
    id: '0190-0000-0000-0010',
    username: 'root',
    displayName: 'Root Admin',
    role: 'SYSTEM_ADMIN',
    status: 'ACTIVE',
    mustChangePassword: false,
    createdAt: '2026-06-28T03:14:00Z',
  },
  {
    id: '0190-0000-0000-0011',
    username: 'alice',
    displayName: 'Alice Wang',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: true,
    createdAt: '2026-07-19T09:47:00Z',
  },
  {
    id: '0190-0000-0000-0012',
    username: 'bob',
    displayName: 'Bob Chen',
    role: 'USER',
    status: 'DISABLED',
    mustChangePassword: false,
    createdAt: '2026-08-10T16:05:00Z',
  },
  {
    id: '0190-0000-0000-0013',
    username: 'demo2_user',
    displayName: 'Demo 用户',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: false,
    lastLoginAt: '2026-09-03T01:22:00Z',
    createdAt: '2026-08-28T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0014',
    username: 'carol',
    displayName: 'Carol',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: false,
    lastLoginAt: '2026-09-02T06:40:00Z',
    createdAt: '2026-08-15T11:23:00Z',
  },
  {
    id: '0190-0000-0000-0015',
    username: 'dave',
    displayName: 'Dave',
    role: 'USER',
    status: 'LOCKED',
    mustChangePassword: false,
    lastLoginAt: '2026-08-30T11:05:00Z',
    createdAt: '2026-08-20T00:00:00Z',
  },
];

async function mockSession(page: Page, user: typeof REGULAR_USER) {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(user) }),
  );
}

async function mockPilotApi(page: Page) {
  await page.route('**/api/v1/me/grants', (route) => route.fulfill({ json: GRANTS }));
  await page.route('**/api/v1/me/virtual-keys', (route) => route.fulfill({ json: KEYS }));
  await page.route('**/api/v1/me/quota-rules', (route) => route.fulfill({ json: QUOTA_RULES }));
  await page.route('**/api/v1/me/usage/summary*', (route) =>
    route.fulfill({ json: USAGE_SUMMARY }),
  );
  await page.route('**/api/v1/me/usage/records*', async (route) => {
    const url = new URL(route.request().url());
    const pageNo = Number(url.searchParams.get('page') ?? 1);
    const record = {
      occurredAt: `2026-09-0${pageNo}T08:00:00Z`,
      modelId: 'deepseek-v4-flash',
      cacheLevel: 'UPSTREAM',
      inputTokens: 1024,
      outputTokens: 512,
      totalTokens: 1536,
      latencyMs: 480,
      upstreamStatusCode: 200,
      providerRequestId: `req_${pageNo}`,
      gatewayRequestId: `gw-${pageNo}`,
      isComplete: true,
      usageMissing: false,
      virtualKeyId: '0190-0000-0000-0002',
    };
    const size = Number(url.searchParams.get('size') ?? 20);
    const items = Array.from({ length: 8 }, (_, i) => ({
      ...record,
      occurredAt: `2026-09-0${Math.min(pageNo, 3)}T0${i + 1}:20:00Z`,
      providerRequestId: `req_${pageNo}_${i}`,
      gatewayRequestId: `gw-${pageNo}-${i}`,
    }));
    await route.fulfill({ json: { items, page: pageNo, size, total: 45 } });
  });
  await page.route('**/api/v1/admin/users*', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ json: USERS });
    }
    return route.fulfill({
      json: {
        user: {
          id: '0190-0000-0000-0021',
          username: 'newbie',
          displayName: '新同学',
          role: 'USER',
          status: 'ACTIVE',
          mustChangePassword: true,
          createdAt: '2026-09-03T00:00:00Z',
        },
        temporaryPassword: 'TempPass2026!',
      },
    });
  });
}

test('new login page renders login and register modes', async ({ page }) => {
  // Public page — the session probe must read unauthenticated or the guard
  // would redirect straight into the console.
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ status: 401, json: {} }));
  await page.goto('/login-new');
  await expect(page.getByTestId('login-panel')).toBeVisible();
  await expect(page.getByTestId('tab-register')).toBeVisible();
  await page.getByTestId('tab-register').click();
  await expect(page.getByTestId('register-display-name')).toBeVisible();
  await page.getByTestId('tab-login').click();
  await page.screenshot({ path: 'test-results/baseline/next-login-1440x900.png' });
});

test('keys page lists keys and rotates through the kebab confirm gate', async ({ page }) => {
  await mockSession(page, REGULAR_USER);
  await mockPilotApi(page);
  await page.route('**/api/v1/me/virtual-keys/0190-0000-0000-0002/rotate', (route) =>
    route.fulfill({
      json: {
        id: '0190-0000-0000-0002',
        secret: 'mqk_live_rotatedsecret',
        baseUrl: 'https://gateway.test.internal',
        display: 'mqk_live_…rotated',
        shownOnce: true,
        createdAt: '2026-09-03T00:00:00Z',
        version: 2,
      },
    }),
  );

  await page.goto('/app-new/keys');
  await expect(page.getByTestId('keys-table')).toBeVisible();
  await expect(page.getByText('mqk_live_…8f2a')).toBeVisible();
  await expect(page.getByText('可用').first()).toBeVisible();

  // Kebab → 轮换 → confirm → one-shot secret with ack gate.
  await page.getByTestId('key-actions-0190-0000-0000-0002').click();
  await page.getByRole('menuitem', { name: '轮换' }).click();
  await expect(page.getByText('轮换 Virtual Key「claude-code-main」')).toBeVisible();
  await page.getByRole('button', { name: '轮换', exact: true }).last().click();

  await expect(page.getByTestId('secret-dialog')).toBeVisible();
  await expect(page.getByTestId('secret-value')).toContainText('mqk_live_rotatedsecret');
  await expect(page.getByTestId('secret-close')).toBeDisabled();
  await page.getByTestId('secret-ack').click();
  await expect(page.getByTestId('secret-close')).toBeEnabled();
  await page.getByTestId('secret-close').click();
  await expect(page.getByTestId('secret-dialog')).toBeHidden();

  await page.screenshot({ path: 'test-results/baseline/next-keys-1440x900.png' });
});

test('revoke also walks the confirm gate and reloads the list', async ({ page }) => {
  await mockSession(page, REGULAR_USER);
  await mockPilotApi(page);
  await page.route('**/api/v1/me/virtual-keys/0190-0000-0000-0003/revoke', (route) =>
    route.fulfill({ json: { message: 'revoked' } }),
  );

  await page.goto('/app-new/keys');
  await page.getByTestId('key-actions-0190-0000-0000-0003').click();
  await page.getByRole('menuitem', { name: '吊销' }).click();
  await expect(page.getByText('吊销 Virtual Key「codex-tools」')).toBeVisible();
  await page.getByRole('button', { name: '吊销', exact: true }).last().click();
  await expect(page.getByText('Virtual Key 已吊销')).toBeVisible();
});

test('usage page shows quota, summary totals and pages the records', async ({ page }) => {
  await mockSession(page, REGULAR_USER);
  await mockPilotApi(page);

  await page.goto('/app-new/usage');
  await expect(page.getByTestId('my-quota-row').first()).toBeVisible();
  await expect(page.getByText('限额 2,000,000 · 本期用量 160,000（8%）')).toBeVisible();
  await expect(page.getByTestId('summary-totals')).toContainText('48');
  await expect(page.getByTestId('usage-chart')).toBeVisible();
  await expect(page.getByText('deepseek-v4-flash').first()).toBeVisible();

  await page.getByTestId('records-next').click();
  await expect(page.getByText('共 45 条 · 第 2 / 3 页')).toBeVisible();
  await page.screenshot({ path: 'test-results/baseline/next-usage-1440x900.png' });
});

test('admin users page renders badges and gates the temp password reveal', async ({ page }) => {
  await mockSession(page, ADMIN_USER);
  await mockPilotApi(page);

  await page.goto('/app-new/users');
  await expect(page.getByTestId('users-table')).toBeVisible();
  await expect(page.getByText('系统管理员').first()).toBeVisible();
  await expect(page.getByTestId('users-summary')).toContainText('共 6 个账号');

  // Create → temp password dialog (dismissible=false until acknowledged).
  await page.getByTestId('user-create-open').click();
  await page.getByTestId('user-create-username').fill('newbie');
  await page.getByTestId('user-create-submit').click();
  await expect(page.getByTestId('temp-password-dialog')).toBeVisible();
  await expect(page.getByTestId('temp-password')).toContainText('TempPass2026!');
  await expect(page.getByTestId('temp-password-close')).toBeDisabled();
  await page.getByTestId('temp-password-ack').click();
  await expect(page.getByTestId('temp-password-close')).toBeEnabled();
  await page.getByTestId('temp-password-close').click();
  await expect(page.getByTestId('temp-password-dialog')).toBeHidden();

  await page.screenshot({ path: 'test-results/baseline/next-users-1440x900.png' });
});

test('regular users are redirected from the admin pilot route', async ({ page }) => {
  await mockSession(page, REGULAR_USER);
  await mockPilotApi(page);

  await page.goto('/app-new/users');
  await expect(page).toHaveURL(/\/app-new\/keys/);
});
