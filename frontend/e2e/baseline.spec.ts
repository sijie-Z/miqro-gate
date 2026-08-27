import { test, expect, type Page } from '@playwright/test';

/**
 * Visual baseline (frontend-design.md §9): login + authenticated shell at the
 * four required viewports. API routes are mocked so no backend is needed; the
 * screenshots land in test-results/baseline/.
 */

const VIEWPORTS = [
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1280x800', width: 1280, height: 800 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '390x844', width: 390, height: 844 },
] as const;

const ADMIN_USER = {
  id: '0190-0000-0000-0001',
  username: 'root',
  displayName: 'Root Admin',
  role: 'SYSTEM_ADMIN',
  mustChangePassword: false,
};

const ADMIN_USERS = [
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

const KEYS = [
  {
    id: '0190-0000-0000-0002',
    name: 'claude-code-main',
    display: 'mqk_live_…8f2a',
    purpose: 'CLAUDE_CODE',
    status: 'ACTIVE',
    projectTag: 'core-ai',
    modelIds: ['claude-3-7-sonnet'],
    createdAt: '2026-08-01T00:00:00Z',
    lastUsedAt: '2026-08-26T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0003',
    name: 'codex-tools',
    display: 'mqk_live_…1b4c',
    purpose: 'CODEX',
    status: 'ACTIVE',
    projectTag: 'tools',
    modelIds: ['gpt-5.2'],
    createdAt: '2026-08-05T00:00:00Z',
    lastUsedAt: null,
  },
];

/** Mocks the control-plane API so the shell renders without a backend. */
async function mockApi(page: Page, admin = false) {
  await page.route('**/api/v1/auth/me', async (route) => {
    if (admin) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(ADMIN_USER),
      });
    } else {
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    }
  });
  await page.route('**/api/v1/me/virtual-keys', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(KEYS) }),
  );
  await page.route('**/api/v1/me/usage/**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ groups: [], totals: {} }),
    }),
  );
  await page.route('**/api/v1/me/grants', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ projects: [], grants: [], purposes: [] }),
    }),
  );
  await page.route('**/api/v1/admin/subscriptions', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0021',
          providerProductId: '0190-0000-0000-0020',
          productName: 'DeepSeek PAYG',
          name: 'Main',
          billingMode: 'PAYG',
          planScope: 'PERSONAL',
          subscriptionPrice: null,
          currency: 'USD',
          quotaTotal: 1000000,
          quotaUnit: 'TOKENS',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/users', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ADMIN_USERS),
    }),
  );
  await page.route('**/api/v1/admin/provider-products', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0020',
          providerSlug: 'deepseek',
          providerName: 'DeepSeek',
          productCode: 'deepseek-payg-api',
          displayName: 'DeepSeek PAYG',
          billingMode: 'PAYG',
          protocols: '["messages"]',
          baseUrlHost: 'api.deepseek.com',
          implementationStatus: 'VERIFIED',
          balanceAuthority: 'OFFICIAL_API',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/subscriptions', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0021',
          providerProductId: '0190-0000-0000-0020',
          productName: 'DeepSeek PAYG',
          name: 'Main',
          billingMode: 'PAYG',
          planScope: 'PERSONAL',
          subscriptionPrice: null,
          currency: 'USD',
          quotaTotal: null,
          quotaUnit: null,
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
      ]),
    }),
  );
}

for (const viewport of VIEWPORTS) {
  test(`login page baseline at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await mockApi(page, false);
    await page.goto('/login');
    await page.waitForLoadState('networkidle');
    await expect(page.getByTestId('login-submit')).toBeVisible();
    await page.screenshot({
      path: `test-results/baseline/login-${viewport.name}.png`,
      fullPage: true,
    });
  });

  test(`authenticated shell baseline at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await mockApi(page, true);
    await page.goto('/app/keys');
    await page.waitForLoadState('networkidle');
    // The shell is fully rendered: header, nav, page title.
    await expect(page.getByTestId('page-title')).toBeVisible();
    await expect(page.getByText('MiQroKey').first()).toBeVisible();

    if (viewport.width >= 768) {
      await expect(page.getByTestId('shell-nav')).toBeVisible();
    } else {
      // Mobile: nav collapses into a drawer behind the toggle.
      await expect(page.getByTestId('nav-toggle')).toBeVisible();
      await page.getByTestId('nav-toggle').click();
      await expect(page.getByTestId('shell-nav-drawer')).toBeVisible();
      await page.keyboard.press('Escape');
      // Wait out the close animation so the baseline never captures a
      // half-closed drawer (TDesign animates ~300ms).
      await expect(page.getByTestId('shell-nav-drawer')).not.toBeVisible();
    }
    await page.screenshot({
      path: `test-results/baseline/shell-${viewport.name}.png`,
      fullPage: true,
    });
  });
}

test('overview page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/overview');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('overview-stats')).toBeVisible();
  await expect(page.getByTestId('overview-stats')).toContainText('Virtual Key');
  await expect(page.getByTestId('overview-usage')).toBeVisible();
  await page.screenshot({ path: 'test-results/baseline/overview-1440x900.png', fullPage: true });
});

test('admin users page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/users');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('users-table')).toBeVisible();
  await expect(page.getByTestId('users-table')).toContainText('alice');
  await expect(page.locator('.mk-status--success').first()).toHaveText('Active');
  await page.screenshot({ path: 'test-results/baseline/admin-users-1440x900.png', fullPage: true });
});

test('admin providers page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/providers');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('products-table')).toBeVisible();
  await expect(page.getByTestId('products-table')).toContainText('api.deepseek.com');
  await page.screenshot({
    path: 'test-results/baseline/admin-providers-1440x900.png',
    fullPage: true,
  });
});

test('regular users are redirected away from admin routes (G5.5)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, false); // 401 on /auth/me -> redirected to login
  await page.goto('/app/users');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('login-submit')).toBeVisible();
});

test('a visible focus ring exists for keyboard navigation (G5.5)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/keys');
  await page.waitForLoadState('networkidle');
  const outline = await page.evaluate(() => {
    const sheet = [...document.styleSheets].flatMap((s) => {
      try {
        return [...s.cssRules];
      } catch {
        return [];
      }
    });
    return sheet.some((r) => /:focus-visible/.test(r.selectorText ?? ''));
  });
  expect(outline).toBe(true);
});

test('key actions: rotate and revoke flows render from the kebab menu', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/keys');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('keys-table')).toBeVisible();

  // The kebab menu exposes rotate and revoke (danger grouped with divider).
  await page.getByTestId('key-actions').first().click();
  await expect(page.getByTestId('key-rotate').first()).toBeVisible();
  await expect(page.getByTestId('key-revoke').first()).toBeVisible();
  await page.keyboard.press('Escape');

  // Status label uses the compact mk-status styling (dot + short label).
  await expect(page.locator('.mk-status--success').first()).toHaveText('Active');
});

test('forbidden aesthetics are absent from the rendered shell', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/keys');
  await page.waitForLoadState('networkidle');

  const violations = await page.evaluate(() => {
    // In the production bundle all CSS is one same-origin file, so the audit
    // scopes to the application's own design rules: :root (tokens) and
    // .mk-* selectors. Element Plus vendor rules are not part of the tokens
    // the spec governs.
    const sheet = [...document.styleSheets].flatMap((s) => {
      try {
        return [...s.cssRules];
      } catch {
        return [];
      }
    });
    const own = sheet.filter((r) => {
      const selector = (r as CSSStyleRule).selectorText ?? '';
      // Brand icon chips (.mk-brand-chip) and the cost donut (.mk-donut) carry
      // the only permitted gradients under the 2026-08-27 direction
      // (frontend-design.md §4.1); surfaces stay flat.
      if (selector.includes('.mk-brand-chip') || selector.includes('.mk-donut')) {
        return false;
      }
      return selector === ':root' || selector.includes('.mk-') || selector.includes('--miqrokey');
    });
    const sanitized = own.map((r) => {
      const selector = (r as CSSStyleRule).selectorText ?? '';
      if (selector === ':root') {
        // The chip palette (--miqrokey-chip-*) is the sanctioned purple
        // source; strip those declarations from the audit surface.
        return r.cssText
          .split(';')
          .filter((decl) => !/--miqrokey-chip-|--miqrokey-shadow-card/.test(decl))
          .join(';');
      }
      return r.cssText;
    });
    const text = sanitized.join;
    return {
      gradients: /linear-gradient|radial-gradient|conic-gradient/.test(text),
      purple: /#7c3aed|#8b5cf6|#a855f7|#6d28d9|#9333ea|purple/i.test(text),
    };
  });
  // Brand icon chips and the cost donut legitimately use gradients under the
  // 2026-08-27 Tencent-console direction (frontend-design.md §4.1): gradients
  // may only appear on .mk-brand-chip and .mk-donut, never on surfaces.
  expect(violations.gradients).toBe(false);
  expect(violations.purple).toBe(false);
});
