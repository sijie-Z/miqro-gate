import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextOverviewView from '@/views/next/NextOverviewView.vue';
import * as api from '@/api';
import type {} from '@/types/api';
import type { UsageSummary, VirtualKeyView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  usageSummary: vi.fn(),
  adminUsageSummary: vi.fn(),
  listSubscriptions: vi.fn(),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { username: 'demo2_user', displayName: 'Demo 用户', role: 'USER' },
  }),
}));

const mockApi = vi.mocked(api);

const key = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView => ({
  id: '0190-0001',
  name: 'claude-code-main',
  purpose: 'CLAUDE_CODE',
  status: 'ACTIVE',
  displayPrefix: 'mqk_live_abcdefghijklmnopqrstuv',
  lastFour: '8f2a',
  display: 'mqk_live_…8f2a',
  modelIds: ['claude-3-7-sonnet'],
  projectId: 'p1',
  projectTag: 'core-ai',
  cachePolicy: 'DISABLED',
  baseUrl: 'https://gateway.test.internal',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const summary: UsageSummary = {
  groupBy: 'project',
  groups: [
    {
      groupKey: 'p1',
      label: 'Core AI',
      requests: { upstream: 12, coalesced: 2, l1Hit: 4, l2Hit: 1 },
      tokens: { input: 1_200_000, output: 400_000, cacheRead: 20_000, cacheCreation: 40_000 },
      cost: { upstreamPaid: '3.200000', gatewayObserved: '0.010000' },
    },
    {
      groupKey: 'p2',
      label: 'Agent Lab',
      requests: { upstream: 5, coalesced: 0, l1Hit: 0, l2Hit: 0 },
      tokens: { input: 100_000, output: 30_000, cacheRead: 0, cacheCreation: 5_000 },
      cost: { upstreamPaid: '0.400000', gatewayObserved: '0.002000' },
    },
  ],
  totals: {
    groupKey: '__totals__',
    label: '合计',
    requests: { upstream: 17, coalesced: 2, l1Hit: 4, l2Hit: 1 },
    tokens: { input: 1_300_000, output: 430_000, cacheRead: 20_000, cacheCreation: 45_000 },
    cost: { upstreamPaid: '3.600000', gatewayObserved: '0.012000' },
  },
};

describe('NextOverviewView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listVirtualKeys.mockResolvedValue([
      key(),
      key({ id: '0190-0009', name: 'codex-extra', status: 'ROTATING' }),
    ]);
    mockApi.usageSummary.mockResolvedValue(summary);
  });

  function mountView() {
    return mount(NextOverviewView, { global: { plugins: [createPinia()] } });
  }

  it('renders the stat band with request/token/cost aggregates', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.usageSummary).toHaveBeenCalledWith('project');
    const stats = wrapper.find('[data-testid="overview-stats"]');
    expect(stats.text()).toContain('Virtual Key');
    expect(stats.text()).toContain('2');
    expect(stats.text()).toContain('本月请求');
    expect(stats.text()).toContain('本月 Tokens');
    expect(stats.text()).toContain('1.7M'); // 1.2M+0.4M+0.1M+0.03M
    expect(stats.text()).toContain('¥3.60');
  });

  it('renders usage bars and the recent keys panel with Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="overview-usage"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('用量分布（按项目）');
    expect(wrapper.text()).toContain('Core AI');
    expect(wrapper.find('[data-testid="overview-keys"]').text()).toContain('claude-code-main');
    expect(wrapper.find('[data-testid="overview-keys"]').text()).toContain('轮换中');
  });

  it('shows the empty hint when there is no usage yet', async () => {
    mockApi.usageSummary.mockResolvedValue({
      groupBy: 'project',
      groups: [],
      totals: summary.totals,
    });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有用量记录');
    expect(wrapper.find('[data-testid="overview-ledger"]').exists()).toBe(false); // non-admin
  });
});
