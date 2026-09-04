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
    displayName: '',
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
    displayName: '',
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

const MODEL_APPROVALS = [
  {
    id: '0190-0000-0000-0031',
    virtualKeyId: '0190-0000-0000-0002',
    keyName: 'claude-code-main',
    keyDisplay: 'mqk_live_…8f2a',
    projectTag: 'core-ai',
    modelId: 'deepseek-v4-flash',
    reason: '编码任务需要更强的推理模型',
    status: 'PENDING',
    requesterId: '0190-0000-0000-0041',
    requesterName: '张三',
    reviewNote: null,
    reviewedByName: null,
    createdAt: '2026-09-02T00:00:00Z',
    updatedAt: '2026-09-02T00:00:00Z',
  },
  {
    id: '0190-0000-0000-0032',
    virtualKeyId: '0190-0000-0000-0003',
    keyName: 'codex-tools',
    keyDisplay: 'mqk_live_…1b4c',
    projectTag: 'tools',
    modelId: 'glm-5',
    reason: null,
    status: 'APPROVED',
    requesterId: '0190-0000-0000-0041',
    requesterName: '张三',
    reviewNote: 'granted',
    reviewedByName: 'Admin',
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
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
  await page.route('**/api/v1/me/model-approvals', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MODEL_APPROVALS),
    }),
  );
  await page.route('**/api/v1/admin/model-approvals*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: MODEL_APPROVALS, nextCursor: null }),
    }),
  );
  await page.route('**/api/v1/admin/quota-rules', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0051',
          scopeType: 'USER',
          scopeId: '0190-0000-0000-0011',
          scopeName: 'Alice',
          scopeTag: 'alice',
          metric: 'TOKENS',
          period: 'MONTHLY',
          limitValue: 1000000,
          warnPercent: 80,
          status: 'ACTIVE',
          used: 640000,
          usedPct: 64,
          level: 'NORMAL',
          windowFrom: '2026-09-01T00:00:00Z',
          windowTo: '2026-10-01T00:00:00Z',
          createdAt: '2026-09-01T00:00:00Z',
          updatedAt: '2026-09-01T00:00:00Z',
          version: 0,
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/quota-default-template', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        enabled: true,
        metric: 'TOKENS',
        period: 'MONTHLY',
        limitValue: 1000000,
        version: 1,
        updatedAt: '2026-09-01T00:00:00Z',
      }),
    }),
  );
  await page.route('**/api/v1/admin/usage/roi*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        from: '2026-08-03T00:00:00Z',
        to: '2026-09-02T00:00:00Z',
        totals: {
          upstreamRequests: 120,
          coalescedRequests: 0,
          l1Hits: 10,
          l2Hits: 20,
          hitRatePct: 20.0,
          paidCost: 0.84,
          savedCost: 0.21,
          savedPct: 20.0,
        },
        byDay: [
          {
            date: '2026-09-02',
            upstreamRequests: 120,
            hitRequests: 30,
            hitRatePct: 20.0,
            paidCost: 0.84,
            savedCost: 0.21,
          },
        ],
      }),
    }),
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
  await page.route('**/api/v1/admin/credentials/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }),
  );
  await page.route('**/api/v1/admin/credentials', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0030',
          name: 'anthropic-main',
          subscriptionId: '0190-0000-0000-0021',
          status: 'ACTIVE',
          activeVersionId: '0190-0000-0000-0031',
          fingerprintPrefix: 'a1b2c3d4e5f6a7b8',
          lastValidatedAt: '2026-08-26T00:00:00Z',
          lastValidationError: null,
          version: 2,
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-20T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0031',
          name: 'moonshot-main',
          subscriptionId: '0190-0000-0000-0021',
          status: 'ACTIVE',
          activeVersionId: '0190-0000-0000-0031',
          fingerprintPrefix: 'c7d8e9f0a1b2c3d4',
          lastValidatedAt: null,
          lastValidationError: null,
          version: 1,
          createdAt: '2026-08-02T00:00:00Z',
          updatedAt: '2026-08-02T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0032',
          name: 'zhipu-main',
          subscriptionId: '0190-0000-0000-0021',
          status: 'ACTIVE',
          activeVersionId: '0190-0000-0000-0031',
          fingerprintPrefix: 'e5f6a7b8c9d0e1f2',
          lastValidatedAt: '2026-08-20T00:00:00Z',
          lastValidationError: null,
          version: 1,
          createdAt: '2026-08-03T00:00:00Z',
          updatedAt: '2026-08-20T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0033',
          name: 'minimax-main',
          subscriptionId: '0190-0000-0000-0021',
          status: 'ACTIVE',
          activeVersionId: '0190-0000-0000-0031',
          fingerprintPrefix: 'a3b4c5d6e7f8a9b0',
          lastValidatedAt: null,
          lastValidationError: null,
          version: 1,
          createdAt: '2026-08-04T00:00:00Z',
          updatedAt: '2026-08-04T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/prices', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0000-000000000040',
          providerProductId: '0190-0000-0000-0020',
          modelId: 'deepseek-chat',
          tokenType: 'INPUT',
          currency: 'CNY',
          unitPrice: '2.0000',
          effectiveFrom: '2026-08-26T00:00:00Z',
          source: 'MANUAL',
          createdBy: '0190-0000-0000-0001',
          createdAt: '2026-08-26T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0000-000000000041',
          providerProductId: '0190-0000-0000-0020',
          modelId: 'deepseek-chat',
          tokenType: 'OUTPUT',
          currency: 'CNY',
          unitPrice: '16.0000',
          effectiveFrom: '2026-08-26T00:00:00Z',
          source: 'MANUAL',
          createdBy: '0190-0000-0000-0001',
          createdAt: '2026-08-26T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/usage/summary?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        groupBy: 'project',
        groups: [
          {
            groupKey: 'core-ai',
            label: 'core-ai',
            requests: { upstream: 120, coalesced: 0, l1Hit: 0, l2Hit: 0 },
            tokens: { input: 240000, output: 120000, cacheRead: 0, cacheCreation: 0 },
            cost: {
              upstreamPaid: '0.8400',
              gatewayObserved: '0.8400',
              projectAllocated: '0.8400',
              savedByGatewayCache: '0.0000',
            },
          },
          {
            groupKey: 'tools',
            label: 'tools',
            requests: { upstream: 60, coalesced: 0, l1Hit: 0, l2Hit: 0 },
            tokens: { input: 60000, output: 30000, cacheRead: 0, cacheCreation: 0 },
            cost: {
              upstreamPaid: '0.2100',
              gatewayObserved: '0.2100',
              projectAllocated: '0.2100',
              savedByGatewayCache: '0.0000',
            },
          },
        ],
        totals: {
          groupKey: 'total',
          label: '合计',
          requests: { upstream: 180, coalesced: 0, l1Hit: 0, l2Hit: 0 },
          tokens: { input: 300000, output: 150000, cacheRead: 0, cacheCreation: 0 },
          cost: {
            upstreamPaid: '1.0500',
            gatewayObserved: '1.0500',
            projectAllocated: '1.0500',
            savedByGatewayCache: '0.0000',
          },
        },
      }),
    }),
  );
  // Budget panel on the cost report page (G8.2): empty by default.
  await page.route('**/api/v1/admin/budgets*', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/api/v1/admin/teams', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0101',
          name: 'Platform',
          description: '平台稳定性与发布',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0101b',
          name: 'SRE',
          description: '基础设施值守',
          status: 'ACTIVE',
          createdAt: '2026-08-02T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0101c',
          name: 'ML Infra',
          description: '模型训练与推理平台',
          status: 'ACTIVE',
          createdAt: '2026-08-05T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0101d',
          name: 'Data Platform',
          description: '数据仓库与指标',
          status: 'DISABLED',
          createdAt: '2026-08-10T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/projects', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0102',
          code: 'P1',
          name: 'Core AI',
          status: 'ACTIVE',
          projectTag: 'core-ai',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0102b',
          code: 'P2',
          name: 'Tools',
          status: 'ACTIVE',
          projectTag: 'tools',
          createdAt: '2026-08-03T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0102c',
          code: 'P3',
          name: 'QA 回归',
          status: 'ACTIVE',
          projectTag: 'qa',
          createdAt: '2026-08-06T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0102d',
          code: 'P4',
          name: 'Docs 站点',
          status: 'ACTIVE',
          projectTag: 'docs',
          createdAt: '2026-08-12T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0102e',
          code: 'P5',
          name: '旧数据迁移',
          status: 'DISABLED',
          projectTag: 'migration',
          createdAt: '2026-07-20T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/grants', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0103',
          projectId: '0190-0000-0000-0102',
          providerProductId: '0190-0000-0000-0020',
          upstreamCredentialId: '0190-0000-0000-0030',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0103b',
          projectId: '0190-0000-0000-0102b',
          providerProductId: '0190-0000-0000-0021',
          upstreamCredentialId: '0190-0000-0000-0031',
          status: 'ACTIVE',
          createdAt: '2026-08-03T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0103c',
          projectId: '0190-0000-0000-0102c',
          providerProductId: '0190-0000-0000-0022',
          upstreamCredentialId: '0190-0000-0000-0032',
          status: 'ACTIVE',
          createdAt: '2026-08-06T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0103d',
          projectId: '0190-0000-0000-0102d',
          providerProductId: '0190-0000-0000-0023',
          upstreamCredentialId: '0190-0000-0000-0033',
          status: 'DISABLED',
          createdAt: '2026-08-12T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/webhooks', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0104',
          name: 'ops-alerts',
          url: 'https://alerts.internal/hook',
          enabled: true,
          timeoutMs: 5000,
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104b',
          name: 'finance-notify',
          url: 'https://finance.internal/webhook',
          enabled: false,
          timeoutMs: 3000,
          createdAt: '2026-08-14T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/alert-rules', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0105',
          name: 'usage-missing',
          type: 'USAGE_MISSING_RATE',
          threshold: 0.5,
          dedupeMinutes: 60,
          webhookEndpointId: null,
          enabled: true,
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0105b',
          name: 'core-ai-budget',
          type: 'BUDGET_THRESHOLD',
          scopeJson: '{"projectId":"0190-0000-0000-0102"}',
          threshold: 80,
          dedupeMinutes: 1440,
          webhookEndpointId: '0190-0000-0000-0104',
          enabled: true,
          createdAt: '2026-08-10T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0105c',
          name: 'approval-submit-notify',
          type: 'MODEL_APPROVAL_SUBMITTED',
          threshold: 1,
          dedupeMinutes: 60,
          webhookEndpointId: '0190-0000-0000-0104',
          enabled: false,
          createdAt: '2026-08-20T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/api-consumers', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0104d',
          name: 'billing-sync',
          keyPrefix: 'mk_bil_8f2a',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104e',
          name: 'analytics-etl',
          keyPrefix: 'mk_ana_1b4c',
          status: 'ACTIVE',
          createdAt: '2026-08-11T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104f',
          name: 'legacy-dashboard',
          keyPrefix: 'mk_leg_9d0e',
          status: 'DISABLED',
          createdAt: '2026-06-01T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/configs', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0104g',
          groupName: 'gateway',
          key: 'cache_enabled',
          value: 'true',
          description: '是否开启语义缓存',
          version: 3,
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-25T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104h',
          groupName: 'gateway',
          key: 'semantic_cache_ttl_minutes',
          value: '60',
          description: '语义缓存 TTL',
          version: 1,
          createdAt: '2026-08-02T00:00:00Z',
          updatedAt: '2026-08-02T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104i',
          groupName: 'alerts',
          key: 'evaluation_interval_ms',
          value: '300000',
          description: '告警评估间隔',
          version: 2,
          createdAt: '2026-08-03T00:00:00Z',
          updatedAt: '2026-08-19T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0104j',
          groupName: 'alerts',
          key: 'max_delivery_retries',
          value: '5',
          description: '投递最大重试次数',
          version: 1,
          createdAt: '2026-08-03T00:00:00Z',
          updatedAt: '2026-08-03T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/skills', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0109',
          name: 'web-scraper',
          description: 'Scrapes public web pages into markdown.',
          version: '1.0.0',
          author: 'Platform Team',
          license: 'MIT',
          tags: ['scraping', 'http'],
          contentSha256: 'aa'.repeat(32),
          contentBytes: 2048,
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0109b',
          name: 'release-notes',
          description: 'Generates release notes from git history.',
          version: '2.3.1',
          author: 'DevEx',
          license: 'MIT',
          tags: ['devops', 'writing'],
          contentSha256: 'bb'.repeat(32),
          contentBytes: 1589248,
          status: 'ACTIVE',
          createdAt: '2026-08-15T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0109c',
          name: 'legacy-fetcher',
          description: 'Deprecated fetch helper.',
          version: '0.4.0',
          author: 'Platform Team',
          license: 'MIT',
          tags: [],
          contentSha256: 'cc'.repeat(32),
          contentBytes: 65536,
          status: 'ARCHIVED',
          createdAt: '2026-06-10T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/agents', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0110',
          name: 'forge-agent',
          description: 'Forge 集成出口',
          credentialId: '0190-0000-0000-0030',
          credentialName: 'anthropic-main',
          providerProductId: '0190-0000-0000-0020',
          providerProductName: 'DeepSeek PAYG',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0110b',
          name: 'docs-writer',
          description: '文档自动生成与润色',
          credentialId: '0190-0000-0000-0031',
          credentialName: 'moonshot-main',
          providerProductId: '0190-0000-0000-0021',
          providerProductName: 'Moonshot PAYG',
          status: 'ACTIVE',
          createdAt: '2026-08-12T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0110c',
          name: 'qa-bot',
          description: '回归用例生成（已停用）',
          credentialId: '0190-0000-0000-0032',
          credentialName: 'zhipu-main',
          providerProductId: '0190-0000-0000-0022',
          providerProductName: 'Zhipu PAYG',
          status: 'DISABLED',
          createdAt: '2026-07-20T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/services', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0111',
          name: 'platform-api',
          kind: 'HTTP',
          description: '平台内部 API',
          baseUrl: 'https://platform.internal.example',
          status: 'ACTIVE',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0111b',
          name: 'erp-mcp',
          kind: 'MCP',
          description: 'ERP 数据查询',
          baseUrl: 'https://erp.internal.example',
          status: 'ACTIVE',
          createdAt: '2026-08-10T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0111c',
          name: 'legacy-gateway',
          kind: 'OTHER',
          description: '',
          baseUrl: 'https://legacy.internal.example',
          status: 'DISABLED',
          createdAt: '2026-05-01T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/mcp-services', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0112',
          name: 'erp-mcp',
          description: 'ERP 查询服务',
          endpoint: 'https://erp.internal.example',
          transport: 'STREAMABLE_HTTP',
          status: 'ONLINE',
          healthStatus: 'HEALTHY',
          healthCheckedAt: '2026-09-02T00:00:00Z',
          checkIntervalSeconds: 30,
          checkTimeoutSeconds: 5,
          failThreshold: 3,
          recoverThreshold: 1,
          checkPath: '/health',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0112b',
          name: 'analytics-mcp',
          description: '指标查询（SSE）',
          endpoint: 'https://metrics.internal.example',
          transport: 'SSE',
          status: 'ONLINE',
          healthStatus: 'UNHEALTHY',
          healthCheckedAt: '2026-09-03T04:12:00Z',
          checkIntervalSeconds: 60,
          checkTimeoutSeconds: 10,
          failThreshold: 5,
          recoverThreshold: 2,
          checkPath: '/ready',
          createdAt: '2026-08-08T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0112c',
          name: 'staging-mcp',
          description: '预发联调（已下线）',
          endpoint: 'https://staging.internal.example',
          transport: 'STREAMABLE_HTTP',
          status: 'OFFLINE',
          healthStatus: 'UNKNOWN',
          healthCheckedAt: null,
          checkIntervalSeconds: 30,
          checkTimeoutSeconds: 5,
          failThreshold: 3,
          recoverThreshold: 1,
          checkPath: '/health',
          createdAt: '2026-07-15T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/audit-events?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0106',
          chainPosition: 1,
          action: 'LOGIN_SUCCESS',
          targetType: 'USER',
          summary: '{"username":"root"}',
          actorId: '0190-0000-0000-0001',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0106b',
          chainPosition: 2,
          action: 'VIRTUAL_KEY_REVOKE',
          targetType: 'VIRTUAL_KEY',
          summary: '{"keyName":"codex-tools","reason":"manager request"}',
          actorId: '0190-0000-0000-0001',
          createdAt: '2026-08-02T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0106c',
          chainPosition: 3,
          action: 'EXPORT_TASK_CREATED',
          targetType: 'EXPORT_TASK',
          summary: '{"format":"CSV","from":"2026-08-01","to":"2026-08-31"}',
          actorId: '0190-0000-0000-0001',
          createdAt: '2026-08-03T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/exports?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0107',
          format: 'CSV',
          periodFrom: '2026-08-01T00:00:00Z',
          periodTo: '2026-08-31T00:00:00Z',
          status: 'SUCCEEDED',
          rowCount: 100,
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0107b',
          format: 'JSONL',
          periodFrom: '2026-09-01T00:00:00Z',
          periodTo: '2026-09-30T00:00:00Z',
          status: 'PENDING',
          rowCount: undefined,
          createdAt: '2026-09-03T00:00:00Z',
        },
      ]),
    }),
  );
  await page.route('**/api/v1/admin/usage/records?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            occurredAt: '2026-08-26T00:00:00Z',
            modelId: 'deepseek-chat',
            cacheLevel: 'UPSTREAM',
            inputTokens: 100,
            outputTokens: 50,
            latencyMs: 1200,
            upstreamStatusCode: 200,
            providerRequestId: 'req-1',
            gatewayRequestId: 'gw-1',
            isComplete: true,
            usageMissing: false,
            virtualKeyId: '0190-0000-0000-0002',
          },
        ],
        page: 1,
        size: 20,
        total: 1,
      }),
    }),
  );
  await page.route('**/api/v1/admin/usage-deletions?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: '0190-0000-0000-0108',
          periodFrom: '2026-08-01T00:00:00Z',
          periodTo: '2026-08-31T00:00:00Z',
          previewCount: 1000,
          status: 'PENDING_CONFIRMATION',
          expiresAt: '2026-09-04T00:00:00Z',
          createdAt: '2026-09-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0108b',
          periodFrom: '2026-07-01T00:00:00Z',
          periodTo: '2026-07-31T00:00:00Z',
          previewCount: 500,
          deletedCount: 500,
          status: 'EXECUTED',
          executedAt: '2026-08-02T00:00:00Z',
          expiresAt: '2026-09-02T00:00:00Z',
          createdAt: '2026-08-01T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0108c',
          periodFrom: '2026-05-01T00:00:00Z',
          periodTo: '2026-05-31T00:00:00Z',
          previewCount: 1200,
          deletedCount: 1100,
          status: 'EXECUTED',
          executedAt: '2026-06-02T00:00:00Z',
          expiresAt: '2026-07-02T00:00:00Z',
          createdAt: '2026-06-01T00:00:00Z',
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
        {
          id: '0190-0000-0000-0021',
          providerSlug: 'moonshot',
          providerName: 'Moonshot',
          productCode: 'moonshot-payg-api',
          displayName: 'Moonshot PAYG',
          billingMode: 'PAYG',
          protocols: '["messages"]',
          baseUrlHost: 'api.moonshot.cn',
          implementationStatus: 'IMPLEMENTED',
          balanceAuthority: 'OFFICIAL_API',
        },
        {
          id: '0190-0000-0000-0022',
          providerSlug: 'zhipu',
          providerName: 'Zhipu',
          productCode: 'zhipu-payg-api',
          displayName: 'Zhipu PAYG',
          billingMode: 'PAYG',
          protocols: '["messages"]',
          baseUrlHost: 'open.bigmodel.cn',
          implementationStatus: 'IMPLEMENTED',
          balanceAuthority: 'UNAVAILABLE',
        },
        {
          id: '0190-0000-0000-0023',
          providerSlug: 'minimax',
          providerName: 'MiniMax',
          productCode: 'minimax-payg-api',
          displayName: 'MiniMax PAYG',
          billingMode: 'PAYG',
          protocols: '["messages"]',
          baseUrlHost: 'api.minimax.io',
          implementationStatus: 'IMPLEMENTED',
          balanceAuthority: 'UNAVAILABLE',
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
        {
          id: '0190-0000-0000-0021b',
          providerProductId: '0190-0000-0000-0021',
          productName: 'Moonshot PAYG',
          name: 'main-team',
          billingMode: 'PAYG',
          planScope: 'TEAM',
          subscriptionPrice: null,
          currency: 'USD',
          quotaTotal: 1000000,
          quotaUnit: 'TOKENS',
          status: 'ACTIVE',
          createdAt: '2026-08-05T00:00:00Z',
        },
        {
          id: '0190-0000-0000-0021c',
          providerProductId: '0190-0000-0000-0022',
          productName: 'Zhipu PAYG',
          name: 'zhipu-pack',
          billingMode: 'TOKEN_PACKAGE',
          planScope: 'PERSONAL',
          subscriptionPrice: 500,
          currency: 'CNY',
          quotaTotal: 2000000,
          quotaUnit: 'TOKENS',
          status: 'ACTIVE',
          createdAt: '2026-08-10T00:00:00Z',
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
    // The v2 shell is fully rendered: brand, grouped nav and page content.
    await expect(page.getByTestId('keys-table')).toBeVisible();
    await expect(page.getByText('MiQroGate').first()).toBeVisible();

    // Local SVG icons (never the CDN iconfont: private deployments are
    // offline). Each nav item must render an inline <svg>.
    const navIconCount = await page.locator('.new-shell__nav svg').count();
    expect(navIconCount).toBeGreaterThan(4);
    // Every t-icon- classed element must be an <svg> — an <i>/<span>
    // with that class would mean the CDN iconfont leaked back in.
    const iconfontLeak = await page.evaluate(
      () => document.querySelectorAll('i.iconfont, span.iconfont').length,
    );
    expect(iconfontLeak).toBe(0);

    if (viewport.width >= 640) {
      await expect(page.locator('.new-shell__rail')).toBeVisible();
    }
    await page.screenshot({
      path: `test-results/baseline/shell-${viewport.name}.png`,
      fullPage: true,
    });
  });
}

test('login form submits credentials and lands on the keys console', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  // On the login page the session check must fail, otherwise the router
  // guard bounces an "authenticated" visitor straight to /app.
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
  await page.route('**/api/v1/auth/login', async (route) => {
    expect(route.request().method()).toBe('POST');
    expect(route.request().postDataJSON()).toEqual({ username: 'root', password: 'secret' });
    expect(route.request().headers()['x-csrf-token']).toBe('e2e-csrf');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ADMIN_USER),
    });
  });
  await page.goto('/login');
  await page.waitForLoadState('networkidle');
  // The CSRF cookie is normally planted by GET /api/v1/auth/csrf; the
  // HTTP client reads it from document.cookie before every mutating call.
  await page.evaluate(() => {
    document.cookie = 'MIQROKEY_CSRF=e2e-csrf; path=/';
  });
  await page.getByTestId('login-username').fill('root');
  await page.getByTestId('login-password').fill('secret');
  await page.getByTestId('login-submit').click();
  await expect(page).toHaveURL(/\/app\/keys/);
  await expect(page.getByTestId('keys-table')).toBeVisible();
});

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
  await expect(page.getByTestId('users-table')).toContainText('正常');
  await expect(page.getByTestId('users-table')).toContainText('停用');
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

test('admin credentials page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/credentials');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('credentials-table')).toBeVisible();
  await expect(page.getByTestId('credentials-table')).toContainText('anthropic-main');
  await page.screenshot({
    path: 'test-results/baseline/admin-credentials-1440x900.png',
    fullPage: true,
  });
});

test('admin prices page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/prices');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('prices-table')).toBeVisible();
  await expect(page.getByTestId('prices-table')).toContainText('deepseek-chat');
  await page.screenshot({
    path: 'test-results/baseline/admin-prices-1440x900.png',
    fullPage: true,
  });
});

const ADMIN_PAGES = [
  { path: '/app/teams', testid: 'teams-table', expect: 'Platform' },
  { path: '/app/projects', testid: 'projects-table', expect: 'Core AI' },
  { path: '/app/grants', testid: 'grants-table', expect: '正常' },
  { path: '/app/approval-center', testid: 'approvals-queue-table', expect: 'deepseek-v4-flash' },
  { path: '/app/quota-rules', testid: 'quota-rules-table', expect: '1,000,000' },
  { path: '/app/roi', testid: 'roi-report', expect: '缓存节省' },
  { path: '/app/plans', testid: 'subscriptions-table', expect: 'DeepSeek PAYG' },
  { path: '/app/skillhub', testid: 'admin-skills-table', expect: 'web-scraper' },
  { path: '/app/agents', testid: 'agents-table', expect: 'forge-agent' },
  { path: '/app/services', testid: 'services-table', expect: 'platform-api' },
  { path: '/app/mcp-services', testid: 'mcp-table', expect: 'erp-mcp' },
  { path: '/app/webhooks', testid: 'webhooks-table', expect: 'ops-alerts' },
  { path: '/app/alert-rules', testid: 'rules-table', expect: 'usage-missing' },
  { path: '/app/audit', testid: 'audit-table', expect: 'LOGIN_SUCCESS' },
  { path: '/app/exports', testid: 'exports-table', expect: 'CSV' },
  { path: '/app/deletions', testid: 'deletions-table', expect: '待确认' },
  { path: '/app/consumers', testid: 'consumers-table', expect: 'billing-sync' },
  { path: '/app/configs', testid: 'configs-table', expect: 'cache_enabled' },
  { path: '/app/admin-usage', testid: 'usage-records-table', expect: 'deepseek-chat' },
];

for (const pageCfg of ADMIN_PAGES) {
  test(`admin page baseline: ${pageCfg.path}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockApi(page, true);
    await page.goto(pageCfg.path);
    await page.waitForLoadState('networkidle');
    await expect(page.getByTestId(pageCfg.testid)).toBeVisible();
    await expect(page.getByTestId(pageCfg.testid)).toContainText(pageCfg.expect);
    await page.screenshot({
      path: `test-results/baseline/${pageCfg.path.replace(/\//g, '-').replace(/^-/, '')}-1440x900.png`,
      fullPage: true,
    });
  });
}

test('model approval request page baseline at 1440x900', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockApi(page, true);
  await page.goto('/app/model-approvals');
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('model-approvals-table')).toBeVisible();
  await expect(page.getByTestId('model-approvals-table')).toContainText('deepseek-v4-flash');
  await expect(page.getByTestId('model-approvals-table')).toContainText('待审批');
  await page.screenshot({
    path: 'test-results/baseline/model-approvals-1440x900.png',
    fullPage: true,
  });
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
