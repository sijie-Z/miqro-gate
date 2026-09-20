import { test, expect, type Page } from '@playwright/test';

/**
 * #1144 regression: leaving /app/mcp-services used to render every following
 * route into an empty outlet (`.new-shell__content` collapsed to `<!---->`)
 * until a full page reload — no console error, no error-boundary takeover.
 *
 * These tests pin the "stay on the page, then navigate away" path: the target
 * route must actually render, and a second navigation must stay healthy so a
 * one-shot flake can't pass for a fix. API surface is mocked, as in
 * new-console.spec.ts.
 */

const ADMIN = {
  id: '0190-0000-0000-0001',
  username: 'root',
  displayName: 'Root Admin',
  role: 'SYSTEM_ADMIN',
  mustChangePassword: false,
};

const MCP_SERVICES = [
  {
    id: '0190-0000-0000-1001',
    name: 'scm-mcp',
    description: '供应链查询',
    endpoint: 'https://scm.internal.example/mcp',
    transport: 'STREAMABLE_HTTP',
    status: 'ONLINE',
    healthStatus: 'HEALTHY',
    healthCheckedAt: '2026-09-20T02:00:00Z',
    checkIntervalSeconds: 30,
    checkTimeoutSeconds: 5,
    failThreshold: 3,
    recoverThreshold: 1,
    checkPath: '/health',
    backendAuthMode: 'VISITOR',
    upstreamTimeoutMs: 60000,
    checkMode: 'HEALTH_PATH',
  },
  {
    id: '0190-0000-0000-1002',
    name: 'erp-mcp',
    description: 'ERP 工具面',
    endpoint: 'https://erp.internal.example/sse',
    transport: 'SSE',
    status: 'ONLINE',
    healthStatus: 'HEALTHY',
    healthCheckedAt: '2026-09-20T02:01:00Z',
    checkIntervalSeconds: 30,
    checkTimeoutSeconds: 5,
    failThreshold: 3,
    recoverThreshold: 1,
    checkPath: '/health',
    backendAuthMode: 'API_KEY',
    upstreamTimeoutMs: 60000,
    checkMode: 'JSONRPC_INITIALIZE',
  },
  {
    id: '0190-0000-0000-1003',
    name: 'docs-mcp',
    description: '文档检索',
    endpoint: 'https://docs.internal.example/mcp',
    transport: 'STREAMABLE_HTTP',
    status: 'OFFLINE',
    healthStatus: 'UNHEALTHY',
    healthCheckedAt: '2026-09-20T01:58:00Z',
    checkIntervalSeconds: 30,
    checkTimeoutSeconds: 5,
    failThreshold: 3,
    recoverThreshold: 1,
    checkPath: '/health',
    backendAuthMode: 'VISITOR',
    upstreamTimeoutMs: 60000,
    checkMode: 'HEALTH_PATH',
  },
];

async function mockAdminSession(page: Page) {
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ADMIN) }),
  );
  await page.route('**/api/v1/admin/mcp-services', (route) =>
    route.fulfill({ json: MCP_SERVICES }),
  );
}

/** The outlet must hold a live element tree — not the empty `<!---->` node. */
async function expectOutletRendered(page: Page) {
  const content = page.locator('.new-shell__content');
  await expect(content).toBeVisible();
  const { html, childCount } = await content.evaluate((el) => ({
    html: el.innerHTML,
    childCount: el.childElementCount,
  }));
  expect(html).not.toBe('<!---->');
  expect(childCount).toBeGreaterThan(0);
  await expect(content.locator('h1').first()).toBeVisible();
}

test('leaving /app/mcp-services renders the next routes (regression #1144)', async ({ page }) => {
  await mockAdminSession(page);

  // Enter the MCP services page directly (full page load, like the report).
  await page.goto('/app/mcp-services');
  await expect(page.getByTestId('mcp-table')).toBeVisible();
  await expect(page.getByText('scm-mcp')).toBeVisible();
  await expect(page.locator('.new-shell__content').locator('h1')).toContainText('MCP 服务');

  // Sidebar navigation away — the overview page must actually render.
  await page.locator('.new-shell__nav-item', { hasText: '总览' }).click();
  await expect(page).toHaveURL(/\/app\/overview/);
  await expectOutletRendered(page);
  // Content identity, not just "something rendered": a stale first hop that
  // kept the old page mounted must fail here.
  await expect(page.locator('.new-shell__content').locator('h1')).toContainText('欢迎回来');

  // A second navigation must stay healthy (the bug used to blank every hop).
  await page.locator('.new-shell__nav-item', { hasText: '智能体' }).click();
  await expect(page).toHaveURL(/\/app\/agents/);
  await expectOutletRendered(page);
  await expect(page.locator('.new-shell__content').locator('h1')).toContainText('智能体');
});

test('control: leaving /app/mcp-access-logs also renders the next route', async ({ page }) => {
  // The source page of the original report's counter-example. If the MCP
  // services test fails while this one passes, the defect is page-specific.
  await mockAdminSession(page);
  await page.route('**/api/v1/admin/mcp-access-logs*', (route) =>
    route.fulfill({ json: { items: [], page: 1, size: 20, total: 0 } }),
  );

  await page.goto('/app/mcp-access-logs');
  await expect(page.locator('.new-shell__content').locator('h1')).toBeVisible();

  await page.locator('.new-shell__nav-item', { hasText: '总览' }).click();
  await expect(page).toHaveURL(/\/app\/overview/);
  await expectOutletRendered(page);
});
