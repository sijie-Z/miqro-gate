import { test, expect, type Page } from '@playwright/test';

/**
 * 模型广场 + 试调台 (#1201, 腾讯「AI 能力市场」对位) — functional smoke over the
 * two new self-service pages. Every boundary is mocked: the console API for the
 * plaza data, the gateway's own /v1/models and /v1/chat/completions for the
 * playground. The pasted key must never leave page memory.
 */

const REGULAR_USER = {
  id: '0190-0000-0000-0009',
  username: 'demo2_user',
  displayName: 'Demo 用户',
  role: 'USER',
  mustChangePassword: false,
  lastLoginAt: '2026-09-14T02:12:00Z',
  sessionExpiresAt: '2026-09-16T05:01:41Z',
};

const PLAZA = {
  models: [
    {
      modelId: 'deepseek-v4-flash',
      displayName: 'DeepSeek V4 Flash',
      contextWindow: 128000,
      maxOutputTokens: 8192,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      price: {
        inputPerMillion: 0.14,
        outputPerMillion: 0.28,
        cacheReadPerMillion: null,
        cacheCreationPerMillion: null,
        currency: 'USD',
      },
      keys: [{ id: 'k1', name: 'claude-code-main', display: 'mqk_live_…8f2a' }],
    },
    {
      modelId: 'glm-5.1',
      displayName: 'GLM 5.1',
      contextWindow: 200000,
      maxOutputTokens: null,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      price: null,
      keys: [{ id: 'k1', name: 'claude-code-main', display: 'mqk_live_…8f2a' }],
    },
  ],
  requestable: [
    {
      modelId: 'deepseek-v4.1',
      displayName: 'DeepSeek V4.1',
      contextWindow: 128000,
      maxOutputTokens: null,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      keyId: 'k1',
      keyName: 'claude-code-main',
    },
  ],
};

async function mockSession(page: Page) {
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: REGULAR_USER }));
}

test('模型广场 lists usable models with prices and the approvable section', async ({ page }) => {
  await mockSession(page);
  await page.route('**/api/v1/me/plaza/models', (route) => route.fulfill({ json: PLAZA }));

  await page.goto('/app/plaza');
  await expect(page.getByTestId('plaza-table')).toBeVisible();
  await expect(page.getByTestId('plaza-table')).toContainText('deepseek-v4-flash');
  await expect(page.getByTestId('plaza-table')).toContainText('0.14 USD');
  await expect(page.getByTestId('plaza-table')).toContainText('128K');
  await expect(page.getByTestId('plaza-table')).toContainText('claude-code-main');
  await expect(page.getByTestId('plaza-requestable-table')).toBeVisible();
  await expect(page.getByTestId('plaza-requestable-table')).toContainText('deepseek-v4.1');

  // Row action deep-links into the playground with the model preselected.
  await page.getByTestId('plaza-try-deepseek-v4-flash').click();
  await expect(page).toHaveURL(/\/app\/playground\?model=deepseek-v4-flash/);
});

test('模型广场 explains an empty plaza instead of two blank tables', async ({ page }) => {
  await mockSession(page);
  await page.route('**/api/v1/me/plaza/models', (route) =>
    route.fulfill({ json: { models: [], requestable: [] } }),
  );

  await page.goto('/app/plaza');
  await expect(page.getByTestId('plaza-empty')).toBeVisible();
  await expect(page.getByTestId('plaza-table')).toHaveCount(0);
});

test("试调台 reads the key's models and sends one real call over /v1/", async ({ page }) => {
  await mockSession(page);
  await page.route('**/api/v1/me/plaza/models', (route) => route.fulfill({ json: PLAZA }));

  let modelsAuth = '';
  await page.route('**/v1/models', (route) => {
    modelsAuth = route.request().headers()['authorization'] ?? '';
    return route.fulfill({
      json: { object: 'list', data: [{ id: 'deepseek-v4-flash' }, { id: 'glm-5.1' }] },
    });
  });
  let chatBody: Record<string, unknown> | null = null;
  let chatAuth = '';
  await page.route('**/v1/chat/completions', (route) => {
    chatAuth = route.request().headers()['authorization'] ?? '';
    chatBody = route.request().postDataJSON() as Record<string, unknown>;
    return route.fulfill({
      json: {
        choices: [{ message: { content: '你好，这是一次真实调用。' } }],
        usage: { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 },
      },
    });
  });

  await page.goto('/app/playground');
  await page.locator('[data-testid="playground-key"]').fill('mqk_live_demo_secret');
  await page.getByTestId('playground-load-models').click();
  await expect(page.getByTestId('playground-models-count')).toContainText('2');

  // Pick the model through the real select, write a prompt, send.
  await page.getByTestId('playground-model').click();
  await page.getByRole('option', { name: 'deepseek-v4-flash' }).click();
  await page.getByTestId('playground-prompt').fill('用一句话解释 API 网关');
  await page.getByTestId('playground-send').click();

  await expect(page.getByTestId('playground-reply')).toContainText('一次真实调用');
  const meta = page.getByTestId('playground-meta');
  await expect(meta).toContainText('输入 12 tokens');
  await expect(meta).toContainText('输出 8 tokens');
  await expect(meta).toContainText('USD');

  // The pasted key rode the Authorization header of both gateway calls.
  expect(modelsAuth).toBe('Bearer mqk_live_demo_secret');
  expect(chatAuth).toBe('Bearer mqk_live_demo_secret');
  expect(chatBody?.model).toBe('deepseek-v4-flash');
  expect(chatBody?.max_tokens).toBe(512);

  // …and stayed out of browser storage (页面内存 only, 不保存).
  const storage = await page.evaluate(() =>
    JSON.stringify({
      local: { ...localStorage },
      session: { ...sessionStorage },
    }),
  );
  expect(storage).not.toContain('mqk_live_demo_secret');
});

test('试调台 surfaces the gateway envelope on rejection', async ({ page }) => {
  await mockSession(page);
  await page.route('**/api/v1/me/plaza/models', (route) => route.fulfill({ json: PLAZA }));
  await page.route('**/v1/models', (route) =>
    route.fulfill({ json: { object: 'list', data: [{ id: 'deepseek-v4-flash' }] } }),
  );
  await page.route('**/v1/chat/completions', (route) =>
    route.fulfill({
      status: 403,
      json: {
        error: {
          type: 'model_not_allowed',
          message: "Model 'glm-5.1' is not allowed for this virtual key",
        },
      },
    }),
  );

  await page.goto('/app/playground');
  await page.locator('[data-testid="playground-key"]').fill('mqk_live_demo_secret');
  await page.getByTestId('playground-load-models').click();
  await expect(page.getByTestId('playground-models-count')).toContainText('1');
  await page.getByTestId('playground-model').click();
  await page.getByRole('option', { name: 'deepseek-v4-flash' }).click();
  await page.getByTestId('playground-prompt').fill('hello');
  await page.getByTestId('playground-send').click();

  await expect(page.getByTestId('playground-error')).toContainText('not allowed');
  await expect(page.getByTestId('playground-error')).toContainText('model_not_allowed');
});
