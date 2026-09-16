import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminUsageView from '@/views/next/NextAdminUsageView.vue';
import * as api from '@/api';
import type {
  UsageGroup,
  UsageRecordPage,
  UsageSummary,
  HourlyUsageReport,
} from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
  adminUsageRecords: vi.fn(),
  adminUsageHourly: vi.fn(),
  listTeams: vi.fn(),
  listUsers: vi.fn(),
  listProjects: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    function pick(value: unknown) {
      emit('update:modelValue', value);
      emit('change', value);
    }
    return { pick, props };
  },
  template: `
    <div class="ui-select-stub">
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        :data-option="opt.value"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>
  `,
});

function group(
  key: string,
  label: string,
  requests: number,
  input: number,
  output: number,
  cost: number,
): UsageGroup {
  return {
    groupKey: key,
    label,
    requests: { upstream: requests, coalesced: 1, l1Hit: 2, l2Hit: 0 },
    tokens: { input, output, cacheRead: 300, cacheCreation: 800 },
    cost: {
      upstreamPaid: cost,
      gatewayObserved: cost,
      projectAllocated: cost,
      savedByGatewayCache: 0.001,
    },
  };
}

function summaryFor(groupBy: string): UsageSummary {
  if (groupBy === 'team') {
    return {
      groupBy,
      groups: [group('t1', '平台组', 30, 40_000, 8_000, 0.05)],
      totals: group('__totals__', '合计', 30, 40_000, 8_000, 0.05),
    };
  }
  if (groupBy === 'day' || groupBy === 'month') {
    return {
      groupBy,
      groups: [
        group('2026-09-15', '2026-09-15', 9, 12_000, 3_000, 0.02),
        group('2026-09-16', '2026-09-16', 5, 8_000, 2_000, 0.01),
      ],
      totals: group('__totals__', '合计', 14, 20_000, 5_000, 0.03),
    };
  }
  return {
    groupBy,
    groups: [group('p1', '演示项目', 14, 20_000, 5_000, 0.03)],
    totals: group('__totals__', '合计', 14, 20_000, 5_000, 0.03),
  };
}

function recordRow(
  i: number,
  overrides: Record<string, unknown> = {},
): NonNullable<UsageRecordPage['items']>[number] {
  return {
    occurredAt: `2026-09-03T08:0${i}:00Z`,
    modelId: 'deepseek-v4-flash',
    cacheLevel: i === 1 ? 'UPSTREAM' : 'L1_HIT',
    inputTokens: 512,
    outputTokens: 128,
    totalTokens: 640,
    latencyMs: i === 1 ? 800 : 120,
    upstreamStatusCode: i === 1 ? null : 200,
    providerRequestId: i === 1 ? null : `req_${i}`,
    gatewayRequestId: `gw-${i}`,
    isComplete: i !== 1,
    usageMissing: i === 1,
    virtualKeyId: 'k1',
    clientIp: i === 1 ? null : '203.0.113.7',
    ...overrides,
  } as NonNullable<UsageRecordPage['items']>[number];
}

const page: UsageRecordPage = {
  items: Array.from({ length: 20 }, (_, i) => recordRow(i)),
  page: 1,
  size: 20,
  total: 45,
};

const hourlyReport: HourlyUsageReport = {
  date: '2026-09-15',
  days: 1,
  dimension: 'USER',
  tzOffsetMinutes: 480,
  rows: [
    {
      hourStart: '2026-09-15T06:00:00Z',
      projectId: 'p1',
      projectLabel: '演示项目',
      dimensionId: 'u1',
      dimensionLabel: 'regular_user',
      requests: 3,
      inputTokens: 1_000,
      outputTokens: 200,
      cacheReadTokens: 50,
      cacheCreationTokens: 10,
      totalTokens: 1_260,
    },
  ],
};

function summaryCalls() {
  return mockApi.adminUsageSummary.mock.calls.map(([query]) => query);
}

describe('NextAdminUsageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.adminUsageSummary.mockImplementation(async (query) =>
      summaryFor(String(query?.groupBy ?? 'project')),
    );
    mockApi.adminUsageRecords.mockResolvedValue(page);
    mockApi.adminUsageHourly.mockResolvedValue(hourlyReport);
    mockApi.listTeams.mockResolvedValue([{ id: 't1', name: '平台组', status: 'ACTIVE' }] as never);
    mockApi.listUsers.mockResolvedValue([] as never);
    mockApi.listProjects.mockResolvedValue([
      { id: 'p1', name: '演示项目', code: 'demo', status: 'ACTIVE' },
    ] as never);
  });

  function mountView() {
    return mount(NextAdminUsageView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('passes a picked time range to the summary, series and records APIs', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="admin-usage-range-30"]').trigger('click');
    await flushPromises();

    expect(summaryCalls()).toContainEqual(
      expect.objectContaining({
        groupBy: 'project',
        from: expect.any(String),
        to: expect.any(String),
      }),
    );
    expect(summaryCalls()).toContainEqual(
      expect.objectContaining({
        groupBy: 'day',
        from: expect.any(String),
        to: expect.any(String),
      }),
    );
    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({
        page: 1,
        size: 20,
        from: expect.any(String),
        to: expect.any(String),
      }),
    );
  });

  it('renders the KPI strip with tokens, hit rate and cost', async () => {
    const wrapper = mountView();
    await flushPromises();

    const strip = wrapper.find('[data-testid="usage-summary"]').text();
    expect(strip).toContain('14');
    expect(strip).toContain('20,000');
    expect(strip).toContain('5,000');
    expect(strip).toContain('命中率');
    expect(strip).toContain('0.0300');
    expect(wrapper.find('[data-testid="usage-records-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.text()).toContain('L1 命中');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('缺失');
    expect(wrapper.text()).toContain('gw-1');
    // #605: the calling address renders in the records table
    expect(wrapper.text()).toContain('203.0.113.7');
  });

  it('renders the dimension breakdown with cost share and a server-backed trend', async () => {
    const wrapper = mountView();
    await flushPromises();

    const breakdown = wrapper.find('[data-testid="usage-breakdown-table"]').text();
    expect(breakdown).toContain('演示项目');
    expect(breakdown).toContain('¥0.0300');
    expect(breakdown).toContain('100.0%');

    // the trend is fed by the server (groupBy=day), not by the records page
    expect(summaryCalls()).toContainEqual(expect.objectContaining({ groupBy: 'day' }));
    await wrapper.find('[data-testid="trend-dim-month"]').trigger('click');
    await flushPromises();
    expect(summaryCalls()).toContainEqual(expect.objectContaining({ groupBy: 'month' }));
  });

  it('paginates to the next page and disables prev on the first page', async () => {
    const wrapper = mountView();
    await flushPromises();

    const prev = wrapper.find('[data-testid="usage-prev"]');
    expect(prev.attributes('disabled')).toBeDefined();
    await wrapper.find('[data-testid="usage-next"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2, size: 20 }),
    );
    expect(wrapper.find('[data-testid="usage-prev"]').attributes('disabled')).toBeUndefined();
  });

  it('passes filter inputs (model, client IP) and the project picker to the query', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-project-id"] [data-option="p1"]').trigger('click');
    await wrapper.find('[data-testid="usage-model-id"]').setValue('deepseek-v4-flash');
    await wrapper.find('[data-testid="usage-client-ip"]').setValue('203.0.113.7');
    await wrapper.find('[data-testid="usage-query"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({
        modelId: 'deepseek-v4-flash',
        projectId: 'p1',
        clientIp: '203.0.113.7',
        page: 1,
        size: 20,
      }),
    );
    expect(summaryCalls()).toContainEqual(
      expect.objectContaining({ groupBy: 'project', projectId: 'p1' }),
    );
  });

  it('drills down from a team row into the records filter and clears it (#681)', async () => {
    const wrapper = mountView();
    await flushPromises();

    // switch the breakdown dimension to teams
    await wrapper.find('[data-testid="usage-group-by"] [data-option="team"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="usage-breakdown-table"]').text()).toContain('平台组');

    // click the team row → records/summary get teamId, a chip appears
    await wrapper.find('[data-testid="usage-breakdown-table"] tbody tr').trigger('click');
    await flushPromises();
    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ teamId: 't1' }),
    );
    expect(summaryCalls()).toContainEqual(
      expect.objectContaining({ groupBy: 'team', teamId: 't1' }),
    );
    const chip = wrapper.find('[data-testid="usage-drill-团队"]');
    expect(chip.exists()).toBe(true);
    expect(chip.text()).toContain('平台组');

    // clearing the chip drops the filter
    await chip.trigger('click');
    await flushPromises();
    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ teamId: undefined }),
    );
    expect(wrapper.find('[data-testid="usage-drill-团队"]').exists()).toBe(false);
  });

  it('renders the hourly token table and reloads when the day range changes (#634)', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.adminUsageHourly).toHaveBeenCalledWith(
      expect.objectContaining({
        dimension: 'USER',
        days: 1,
        tzOffsetMinutes: expect.any(Number),
      }),
    );
    const table = wrapper.find('[data-testid="usage-hourly-table"]');
    expect(table.text()).toContain('演示项目');
    expect(table.text()).toContain('regular_user');
    expect(table.text()).toContain('1,260');

    await wrapper.find('[data-testid="hourly-days-7"]').trigger('click');
    await flushPromises();
    expect(mockApi.adminUsageHourly).toHaveBeenLastCalledWith(expect.objectContaining({ days: 7 }));
  });
});
