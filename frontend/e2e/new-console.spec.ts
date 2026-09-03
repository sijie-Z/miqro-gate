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
];

const USAGE_SUMMARY = {
  groupBy: 'project',
  groups: [
    {
      groupKey: 'live',
      label: 'LIVE 项目',
      requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
      tokens: { input: 120_000, output: 40_000, cacheRead: 8_000, cacheCreation: 15_000 },
      cost: { upstreamPaid: '0.320000', gatewayObserved: '0.002000' },
    },
  ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 120_000, output: 40_000, cacheRead: 8_000, cacheCreation: 15_000 },
    cost: { upstreamPaid: '0.320000', gatewayObserved: '0.002000' },
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
    createdAt: '2026-07-01T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0011',
    username: 'alice',
    displayName: 'Alice',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: true,
    createdAt: '2026-08-01T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0012',
    username: 'bob',
    displayName: 'Bob',
    role: 'USER',
    status: 'DISABLED',
    mustChangePassword: false,
    createdAt: '2026-08-10T00:00:00Z',
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
    const items = pageNo === 1 ? [record] : [{ ...record, occurredAt: '2026-09-02T08:00:00Z' }];
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
  await expect(page.getByTestId('summary-totals')).toContainText('19');
  await expect(page.getByTestId('usage-chart')).toBeVisible();
  await expect(page.getByText('deepseek-v4-flash')).toBeVisible();

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
  await expect(page.getByTestId('users-summary')).toContainText('共 3 个账号');

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
