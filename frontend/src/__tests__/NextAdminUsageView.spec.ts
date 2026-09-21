import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { UiTooltip } from '@/ui';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminUsageView from '@/views/next/NextAdminUsageView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type {
  ModelCallTimeline,
  UsageGroup,
  UsageRecordPage,
  UsageSummary,
  HourlyUsageReport,
} from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminUsageSummary: vi.fn(),
  adminUsageRecords: vi.fn(),
  adminUsageHourly: vi.fn(),
  adminUsageTimeline: vi.fn(),
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
    // #758 lifecycle outcomes: all succeeded, deterministic latencies.
    outcomes: {
      succeeded: requests,
      failed: 0,
      cancelled: 0,
      avgDurationMs: 4_200,
      avgTtfbMs: 1_800,
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
    // #758 enrichment columns.
    providerProductName: 'DeepSeek 官方按量 API',
    cost: 0.0012,
    priced: true,
    ttfbMs: i === 1 ? null : 210,
    wireProtocol: 'ANTHROPIC_MESSAGES',
    requestStatus: i === 1 ? 'UPSTREAM_REJECTED' : 'SUCCEEDED',
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

// --- #707 model-call timeline fixtures -------------------------------------

/** A fully observed, successful call: all three milestones, a TTFB, no retry. */
function timelineFor(
  status: string,
  overrides: Partial<ModelCallTimeline> = {},
): ModelCallTimeline {
  return {
    gatewayRequestId: 'gw-3',
    upstreamRequestId: 'upstream-9f2',
    modelId: 'deepseek-v4-flash',
    wireProtocol: 'OPENAI_CHAT',
    streaming: true,
    status,
    httpStatus: 200,
    clientCancelled: false,
    partialResponse: false,
    retryCount: 0,
    startedAt: '2026-09-03T08:03:00Z',
    firstByteAt: '2026-09-03T08:03:00.820Z',
    completedAt: '2026-09-03T08:03:02.140Z',
    durationMs: 2140,
    timeToFirstByteMs: 820,
    tokens: { input: 512, output: 128, cacheRead: 300, cacheCreation: 800 },
    attribution: {
      userId: '11111111-1111-1111-1111-111111111111',
      projectId: '22222222-2222-2222-2222-222222222222',
      virtualKeyId: '33333333-3333-3333-3333-333333333333',
      providerId: '44444444-4444-4444-4444-444444444444',
      providerProductId: '55555555-5555-5555-5555-555555555555',
      credentialId: '66666666-6666-6666-6666-666666666666',
    },
    phases: [
      { key: 'ACCEPTED', label: '受理', at: '2026-09-03T08:03:00Z', elapsedMs: 0 },
      { key: 'FIRST_BYTE', label: '上游首字节', at: '2026-09-03T08:03:00.820Z', elapsedMs: 820 },
      { key: 'COMPLETED', label: '完成', at: '2026-09-03T08:03:02.140Z', elapsedMs: 2140 },
    ],
    ...overrides,
  };
}

/** Mirrors the view's local-time rendering, so a fixture timestamp assertion
 * does not depend on the machine's timezone. */
function localStamp(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function drawerEl(testid: string): Element | null {
  return document.querySelector(`[data-testid="${testid}"]`);
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

  it('#1114: the breakdown CSV quotes cells and neutralizes formula starts', async () => {
    // jsdom's Blob has no .text(); capture the source string at construction.
    const parts: string[] = [];
    const RealBlob = globalThis.Blob;
    class CapturingBlob extends RealBlob {
      constructor(partList: BlobPart[], options?: BlobPropertyBag) {
        super(partList, options);
        parts.push(partList.join(''));
      }
    }
    vi.stubGlobal('Blob', CapturingBlob);
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() });
    mockApi.adminUsageSummary.mockImplementation(async (query) => ({
      ...summaryFor(String(query?.groupBy ?? 'project')),
      groups: [group('g1', 'A,"B', 3, 10, 5, 0.5), group('g2', '=cmd|calc', 1, 10, 5, 0.5)],
    }));

    const wrapper = mountView();
    await flushPromises();
    // The export lives on the breakdown tab (the default tab is the request log).
    await wrapper.find('[data-testid="usage-tab-breakdown"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="usage-breakdown-export"]').trigger('click');
    await flushPromises();
    vi.unstubAllGlobals();

    expect(parts).toHaveLength(1);
    const text = parts[0]!.replace(/^﻿/, '');
    // Every cell quoted (RFC 4180): a comma or a quote in a project/user name
    // must not shift columns …
    expect(text).toContain('"A,""B"');
    // … and a label that starts a formula is neutralized (#430).
    expect(text).toContain('"\'=cmd|calc"');
    expect(text.split('\r\n')[0]).toBe('"分组","请求","Token","成本(CNY)","占比(%)"');
  });

  it('#790: marks the saving as a lower bound when hits could not be priced', async () => {
    mockApi.adminUsageSummary.mockImplementation(async (query) => {
      const summary = summaryFor(String(query?.groupBy ?? 'project'));
      return {
        ...summary,
        totals: { ...summary.totals, unpriced: { unpricedHitEvents: 3 } },
      } as never;
    });

    const wrapper = mountView();
    await flushPromises();

    const marker = wrapper.find('[data-testid="savings-unpriced"]');
    expect(marker.exists()).toBe(true);
    // A bare small amount would read as "the cache saved almost nothing"; the marker
    // is the difference between that and "we had no price to say" (#790).
    expect(marker.text()).toContain('下界');
  });

  it('#790: leaves a fully priced saving unmarked', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="savings-unpriced"]').exists()).toBe(false);
  });

  it('#801: marks the total cost as not-a-total when a gap exists', async () => {
    mockApi.adminUsageSummary.mockImplementation(async (query) => {
      const summary = summaryFor(String(query?.groupBy ?? 'project'));
      return {
        ...summary,
        totals: {
          ...summary.totals,
          pricingStatus: 'PARTIAL',
          unpriced: { unpricedEvents: 617, unavailableEvents: 565 },
        },
      } as never;
    });

    const wrapper = mountView();
    await flushPromises();

    const marker = wrapper.find('[data-testid="cost-unpriced"]');
    expect(marker.exists()).toBe(true);
    expect(marker.text()).toContain('未定价');
  });

  it('#801: leaves a fully priced cost unmarked', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="cost-unpriced"]').exists()).toBe(false);
  });

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

  it('renders the hero card with the 万 unit, sub metrics and the token hit-rate bar (#758)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const hero = wrapper.find('[data-testid="usage-summary"]');
    expect(hero.text()).toContain('真实消耗 Tokens');
    expect(hero.text()).toContain('26,100'); // 20,000 + 5,000 + 300 + 800
    expect(hero.text()).toContain('≈ 2.6 万');
    expect(hero.text()).toContain('总请求数');
    expect(hero.text()).toContain('总成本');
    expect(hero.text()).toContain('新增输入');
    expect(hero.text()).toContain('缓存创建');
    expect(hero.text()).toContain('缓存读取');
    expect(hero.text()).toContain('网关缓存节省');
    // token-level hit rate = 300 / (300 + 20,000) ≈ 1.5%
    expect(hero.text()).toContain('1.5%');
    expect(hero.find('.next-admin-usage__hero-bar-fill').attributes('style')).toContain(
      'width: 1.5%',
    );
    // request-level rate rides the 总请求数 sub line: 2 / (14 + 1 + 2) = 11.8%
    expect(hero.text()).toContain('11.8%');
  });

  it('enriches the request log with 供应商 / 成本 / 用时·首字 / 协议 columns (#758)', async () => {
    mockApi.adminUsageRecords.mockResolvedValue({
      items: [recordRow(0), recordRow(1), recordRow(2, { priced: false, cost: null })],
      page: 1,
      size: 20,
      total: 3,
    });
    const wrapper = mountView();
    await flushPromises();

    const table = wrapper.find('[data-testid="usage-records-table"]').text();
    expect(table).toContain('DeepSeek 官方按量 API');
    expect(table).toContain('¥0.0012');
    expect(table).toContain('未定价');
    expect(table).toContain('120ms / 210ms'); // 用时 / 首字
    expect(table).toContain('Anthropic');
  });

  it('#1128: the request log shows how a row was attributed, and stays quiet on the default route', async () => {
    mockApi.adminUsageRecords.mockResolvedValue({
      items: [
        recordRow(0, {
          resolutionStatus: 'RESOLVED_HEADER',
          claimSource: 'prompt_url',
          claimConfidence: 'HIGH',
        }),
        recordRow(1, { resolutionStatus: 'SOLE_BINDING' }),
      ],
      page: 1,
      size: 20,
      total: 2,
    });
    const wrapper = mountView();
    await flushPromises();

    // Every authenticated request walks the ladder, so the chip is a filter, not a
    // decoration: the single-binding default gets a dash, the decided row gets a chip.
    const chips = wrapper.findAll('[data-testid="usage-attribution-chip"]');
    expect(chips).toHaveLength(1);
    expect(chips[0]!.text()).toContain('按请求头声明');
    // Value-level too: claimSource and claimConfidence are both strings, so a swapped
    // wiring type-checks and would ship silently. (The badge sits *inside* the tooltip's
    // slot, so the note is read off the tooltip list, not by searching downwards.)
    const notes = wrapper.findAllComponents(UiTooltip).map((t) => t.props('text'));
    expect(notes.some((note) => note.includes('客户端声明来源：提示中的链接，置信度 HIGH'))).toBe(
      true,
    );
  });

  it('#1139: a suffix ruling with several candidates speaks; the single-binding one stays a dash', async () => {
    // The wiring is the weak point: the chip's own spec cannot catch a view that
    // forgets to pass resolutionCandidates down — every field is optional in the
    // generated type and the chip would silently fall back to "unknown".
    mockApi.adminUsageRecords.mockResolvedValue({
      items: [
        recordRow(0, { resolutionStatus: 'RESOLVED_SUFFIX', resolutionCandidates: 2 }),
        recordRow(1, { resolutionStatus: 'RESOLVED_SUFFIX', resolutionCandidates: 1 }),
      ],
      page: 1,
      size: 20,
      total: 2,
    });
    const wrapper = mountView();
    await flushPromises();

    const chips = wrapper.findAll('[data-testid="usage-attribution-chip"]');
    expect(chips).toHaveLength(1);
    expect(chips[0]!.text()).toContain('按密钥后缀');
    const notes = wrapper.findAllComponents(UiTooltip).map((t) => t.props('text'));
    expect(notes.some((note) => note.includes('2 个候选绑定') && note.includes('从中选定'))).toBe(
      true,
    );
  });

  /** Column headers of a rendered UiTable, in column order. */
  function headerTitles(wrapper: ReturnType<typeof mount>, testid: string): string[] {
    return wrapper.findAll(`[data-testid="${testid}"] thead th`).map((th) => th.text());
  }

  /** One cell of a records row, located by the column's own header. */
  function cellOf(
    wrapper: ReturnType<typeof mount>,
    testid: string,
    rowIndex: number,
    title: string,
  ): string {
    const index = headerTitles(wrapper, testid).indexOf(title);
    expect(index).toBeGreaterThanOrEqual(0);
    return wrapper
      .findAll(`[data-testid="${testid}"] tbody tr`)
      [rowIndex]!.findAll('td')
      [index]!.text();
  }

  it('drops the 调整 column while no row on the page carries an adjustment (#773)', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(headerTitles(wrapper, 'usage-records-table')).not.toContain('调整');
    // The rows themselves are untouched — the column went, the table did not.
    expect(wrapper.findAll('[data-testid="usage-records-table"] tbody tr')).toHaveLength(20);
  });

  it('keeps the 调整 column and its per-row state while a row is adjusted (#773)', async () => {
    mockApi.adminUsageRecords.mockResolvedValue({
      items: [recordRow(0, { adjusted: true, netOutputTokens: 160 }), recordRow(2)],
      page: 1,
      size: 20,
      total: 2,
    });
    const wrapper = mountView();
    await flushPromises();

    // The adjusted row declares itself; the untouched one keeps the placeholder
    // the column always rendered.
    expect(cellOf(wrapper, 'usage-records-table', 0, '调整')).toContain('已调整');
    expect(cellOf(wrapper, 'usage-records-table', 1, '调整')).toBe('—');
  });

  it('switches 供应商统计 / 模型统计 tabs onto the product and model dimensions (#758)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-tab-provider"]').trigger('click');
    await flushPromises();
    expect(summaryCalls()).toContainEqual(expect.objectContaining({ groupBy: 'product' }));
    const providerTable = wrapper.find('[data-testid="usage-breakdown-table"]').text();
    expect(providerTable).toContain('成功率');
    expect(providerTable).toContain('平均延迟');
    expect(providerTable).toContain('100.0%');
    expect(providerTable).toContain('4.2s'); // avgDurationMs 4200

    await wrapper.find('[data-testid="usage-tab-model"]').trigger('click');
    await flushPromises();
    expect(summaryCalls()).toContainEqual(expect.objectContaining({ groupBy: 'model' }));
  });

  it('jumps to an explicit page through the pager input (#758)', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-page-input"]').setValue('3');
    await wrapper.find('[data-testid="usage-page-go"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageRecords).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 3, size: 20 }),
    );
  });

  it('renders the dimension breakdown with cost share and a server-backed trend', async () => {
    const wrapper = mountView();
    await flushPromises();

    // #758: the breakdown lives behind its own tab now.
    await wrapper.find('[data-testid="usage-tab-breakdown"]').trigger('click');
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

  it('trend labels follow the bucket the user picked last when two loadSeries calls overlap', async () => {
    const wrapper = mountView();
    await flushPromises();

    // Second phase: capture the trend requests so their resolution order can be
    // inverted — the 按月 request is issued first but answers last.
    const pending: { groupBy: string; resolve: (v: UsageSummary) => void }[] = [];
    mockApi.adminUsageSummary.mockImplementation(
      (query) =>
        new Promise<UsageSummary>((resolve) => {
          pending.push({ groupBy: String(query?.groupBy ?? ''), resolve });
        }),
    );

    await wrapper.find('[data-testid="trend-dim-month"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="trend-dim-day"]').trigger('click');
    await flushPromises();

    // Both requests are in flight: the guard in loadSeries() is meant to drop
    // whichever one is no longer the current dimension.
    expect(pending.map((p) => p.groupBy)).toEqual(['month', 'day']);

    const monthBucket = (key: string, label: string) => ({
      ...group(key, label, 4, 5_000, 1_000, 0.01),
    });
    // The 按日 answer lands first — it is the dimension the user selected last.
    pending[1]!.resolve({
      groupBy: 'day',
      groups: [group('2026-09-15', '2026-09-15', 9, 12_000, 3_000, 0.02)],
      totals: group('__totals__', '合计', 9, 12_000, 3_000, 0.02),
    });
    await flushPromises();
    // The stale 按月 answer lands second and must NOT win.
    pending[0]!.resolve({
      groupBy: 'month',
      groups: [monthBucket('2026-03', '2026-03')],
      totals: group('__totals__', '合计', 4, 5_000, 1_000, 0.01),
    });
    await flushPromises();

    const chart = wrapper.find('[data-testid="usage-trend-chart"]').text();
    // Under groupBy=day the bucket 2026-09-15 is labelled "09-15"; a month
    // bucket rendered through the day formatter would read "03".
    expect(chart).toContain('09-15');
  });

  it('a 按日/按月 click supersedes only the trend, not an in-flight 查询', async () => {
    // The trap a shared sequence would set: loadSeries() bumping loadRequestSeq
    // would make the bucket click abandon the 查询's own summary + records, and
    // load()'s finally — cleared under the same condition — would leave the
    // request log stuck on its skeleton rows forever. The two writers therefore
    // get a sequence each; this pins that split.
    const wrapper = mountView();
    await flushPromises();

    const pending: { groupBy: string; resolve: (v: UsageSummary) => void }[] = [];
    mockApi.adminUsageSummary.mockImplementation(
      (query) =>
        new Promise<UsageSummary>((resolve) => {
          pending.push({ groupBy: String(query?.groupBy ?? ''), resolve });
        }),
    );

    await wrapper.find('[data-testid="usage-query"]').trigger('click');
    await flushPromises();
    // 查询 issues the breakdown + trend pair; the bucket click adds one more
    // trend request on top of the still-unanswered pair.
    await wrapper.find('[data-testid="trend-dim-month"]').trigger('click');
    await flushPromises();
    expect(pending.map((p) => p.groupBy)).toEqual(['project', 'day', 'month']);

    pending[0]!.resolve(summaryFor('project'));
    pending[1]!.resolve(summaryFor('day'));
    // 按月 was the user's last pick, so it wins the trend…
    pending[2]!.resolve({
      groupBy: 'month',
      groups: [group('2026-03', '2026-03', 4, 5_000, 1_000, 0.01)],
      totals: group('__totals__', '合计', 4, 5_000, 1_000, 0.01),
    });
    await flushPromises();

    // …and the month bucket renders month-formatted, not sliced to "03".
    expect(wrapper.find('[data-testid="usage-trend-chart"]').text()).toContain('2026-03');
    // …while the 查询 the user actually paid for is not thrown away with it.
    // This is the assertion that separates the two designs: had loadSeries()
    // bumped loadRequestSeq, load()'s finally would have skipped clearing these
    // flags and the request log would sit on its skeleton rows for good.
    expect(
      wrapper.find('[data-testid="usage-records-table"]').findAll('.ui-skeleton'),
    ).toHaveLength(0);
  });

  it('#876: marks a short group cost in the breakdown table', async () => {
    // The hero card above this table already carried the marker for the same figure;
    // the 维度分解 table printed a bare ¥0.0000 for it.
    mockApi.adminUsageSummary.mockImplementation(async (query) => {
      const s = summaryFor(String(query?.groupBy ?? 'project'));
      return {
        ...s,
        groups: [
          {
            ...s.groups![0]!,
            pricingStatus: 'UNAVAILABLE',
            unpriced: { unpricedEvents: 1, unavailableEvents: 1 },
          },
        ],
      } as never;
    });

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="usage-tab-breakdown"]').trigger('click');
    await flushPromises();

    const marker = wrapper.find('[data-testid="breakdown-cost-unpriced"]');
    expect(marker.exists()).toBe(true);
    expect(marker.text()).toContain('未定价');
  });

  it('#876: the marker follows the same table into 供应商统计 and 模型统计', async () => {
    // One table serves three tab entries; each entry has to keep the marker, or the next
    // one re-introduces the gap.
    mockApi.adminUsageSummary.mockImplementation(async (query) => {
      const s = summaryFor(String(query?.groupBy ?? 'project'));
      return {
        ...s,
        groups: [
          {
            ...s.groups![0]!,
            pricingStatus: 'PARTIAL',
            unpriced: { unpricedEvents: 3, unavailableEvents: 1 },
          },
        ],
      } as never;
    });

    const wrapper = mountView();
    await flushPromises();

    for (const tab of ['breakdown', 'provider', 'model']) {
      await wrapper.find(`[data-testid="usage-tab-${tab}"]`).trigger('click');
      await flushPromises();
      expect(
        wrapper.find('[data-testid="breakdown-cost-unpriced"]').exists(),
        `tab ${tab} lost the marker`,
      ).toBe(true);
    }
  });

  it('#876: leaves a fully priced breakdown row unmarked', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="usage-tab-breakdown"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="breakdown-cost-unpriced"]').exists()).toBe(false);
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

    // #758: the breakdown tab hosts the dimension picker and the table.
    await wrapper.find('[data-testid="usage-tab-breakdown"]').trigger('click');
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

  it('opens the call timeline drawer from the request ID column (#707)', async () => {
    mockApi.adminUsageTimeline.mockResolvedValue(timelineFor('SUCCEEDED'));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-timeline-gw-3"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminUsageTimeline).toHaveBeenCalledWith('gw-3');
    const drawer = drawerEl('usage-timeline-drawer');
    expect(drawer, 'timeline drawer should render').toBeTruthy();

    // tier 1: terminal badge, total duration, and the "where it stopped" line
    const hero = drawerEl('usage-timeline-hero')!;
    expect(hero.textContent).toContain('成功');
    expect(hero.textContent).toContain('2.14 s');
    expect(hero.textContent).toContain('三个阶段齐备');

    // every phase carries the timestamp and the elapsed delta of the payload
    const phases = drawerEl('usage-timeline-phases')!;
    expect(phases.textContent).toContain('受理');
    expect(phases.textContent).toContain('上游首字节');
    expect(phases.textContent).toContain('完成');
    expect(phases.textContent).toContain(localStamp('2026-09-03T08:03:00Z'));
    expect(phases.textContent).toContain('起点');
    expect(phases.textContent).toContain('+820 ms');
    expect(phases.textContent).toContain('+2.14 s');
    expect(phases.textContent).not.toContain('缺失');

    // tier 2: TTFB / retries / partial response / HTTP status
    expect(drawerEl('usage-timeline-metrics')).toBeTruthy();
    expect(drawerEl('usage-timeline-ttfb')!.textContent).toContain('820 ms');
    expect(drawerEl('usage-timeline-retries')!.textContent).toContain('0 次');
    expect(drawerEl('usage-timeline-partial')!.textContent).toContain('否');
    expect(drawerEl('usage-timeline-http')!.textContent).toContain('200');

    // tier 3 is collapsed by default; its content is still readable on demand
    const details = drawerEl('usage-timeline-details') as HTMLDetailsElement | null;
    expect(details).toBeTruthy();
    expect(details!.open).toBe(false);
    expect(details!.textContent).toContain('upstream-9f2');
    expect(details!.textContent).toContain('11111111');
    expect(details!.textContent).toContain('OPENAI_CHAT');
    expect(details!.textContent).toContain('流式');
    expect(details!.textContent).toContain('512');
    expect(details!.textContent).toContain('800');

    // metadata only — no payload ever reaches the drawer
    expect(drawer!.textContent).toContain('仅元数据');

    wrapper.unmount();
  });

  it('marks unobserved phases as missing instead of inventing timestamps (#707)', async () => {
    mockApi.adminUsageTimeline.mockResolvedValue(
      timelineFor('CLIENT_CANCELLED', {
        firstByteAt: undefined,
        completedAt: undefined,
        durationMs: undefined,
        timeToFirstByteMs: undefined,
        partialResponse: true,
        phases: [{ key: 'ACCEPTED', label: '受理', at: '2026-09-03T08:03:00Z', elapsedMs: 0 }],
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-timeline-gw-3"]').trigger('click');
    await flushPromises();

    // a cancelled call recorded only 受理 — the absence is the diagnosis
    const firstByte = drawerEl('usage-timeline-phase-FIRST_BYTE')!;
    expect(firstByte.textContent).toContain('缺失');
    expect(firstByte.textContent).not.toMatch(/\d{4}-\d{2}-\d{2}/);
    expect(drawerEl('usage-timeline-phase-COMPLETED')!.textContent).toContain('缺失');

    const accepted = drawerEl('usage-timeline-phase-ACCEPTED')!;
    expect(accepted.textContent).toContain('起点');
    expect(accepted.textContent).toContain(localStamp('2026-09-03T08:03:00Z'));

    // the headline states the terminal and where the call stopped
    const hero = drawerEl('usage-timeline-hero')!;
    expect(hero.textContent).toContain('客户端取消');
    expect(hero.textContent).toContain('调用方在响应写完前断开');
    // un-measured values stay blank rather than being faked as 0
    expect(drawerEl('usage-timeline-duration')!.textContent).toContain('—');
    expect(drawerEl('usage-timeline-ttfb')!.textContent).toContain('—');
    expect(drawerEl('usage-timeline-partial')!.textContent).toContain('是');

    wrapper.unmount();
  });

  it('explains a 404 as "nothing to replay" instead of raising an error (#707)', async () => {
    mockApi.adminUsageTimeline.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: '未找到该请求的调用记录。',
        status: 404,
        code: 'REQUEST_NOT_FOUND',
        detail: '未找到该请求的调用记录。',
        requestId: 'req-404',
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="usage-timeline-gw-5"]').trigger('click');
    await flushPromises();

    const note = drawerEl('usage-timeline-missing');
    expect(note, 'the 404 hint should render').toBeTruthy();
    expect(note!.textContent).toContain('没有可回放的调用留痕');
    expect(note!.textContent).toContain('缓存命中');
    // a missing lifecycle row is an explanation, never an error banner
    expect(drawerEl('usage-timeline-error')).toBeNull();
    expect(drawerEl('usage-timeline-hero')).toBeNull();

    wrapper.unmount();
  });

  it('renders every lifecycle terminal plus the un-finalized state (#707)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const terminals: Array<[string, string]> = [
      ['SUCCEEDED', '成功'],
      ['CLIENT_CANCELLED', '客户端取消'],
      ['STREAM_INTERRUPTED', '流中断'],
      ['TIMEOUT_BEFORE_FIRST_BYTE', '首字节前超时'],
      ['UPSTREAM_REJECTED', '上游拒绝'],
      ['UPSTREAM_UNAVAILABLE', '上游不可用'],
      ['IN_FLIGHT', '未结算'],
    ];

    for (const [status, label] of terminals) {
      mockApi.adminUsageTimeline.mockResolvedValue(timelineFor(status));
      await wrapper.find('[data-testid="usage-timeline-gw-3"]').trigger('click');
      await flushPromises();
      expect(drawerEl('usage-timeline-status')!.textContent, status).toContain(label);
    }

    wrapper.unmount();
  });
});
