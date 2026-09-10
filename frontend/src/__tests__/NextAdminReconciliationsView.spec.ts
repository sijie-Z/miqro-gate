import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminReconciliationsView from '@/views/next/NextAdminReconciliationsView.vue';
import * as api from '@/api';
import type { ReconciliationReport, ReconciliationRow } from '@/api';

vi.mock('@/api', () => ({
  listReconciliations: vi.fn(),
  reconciliationReport: vi.fn(),
  reconciliationRows: vi.fn(),
  createReconciliation: vi.fn(),
}));

const mockApi = vi.mocked(api);

const report = (overrides: Partial<ReconciliationReport> = {}): ReconciliationReport => ({
  id: 'r1',
  providerCode: 'anthropic-claude',
  currency: 'USD',
  windowFrom: '2026-09-09T00:00:00Z',
  windowTo: '2026-09-10T00:00:00Z',
  status: 'SUCCEEDED',
  totalRows: 4,
  matched: 1,
  partialBuckets: 1,
  unmatchedProvider: 3,
  unmatchedLocal: 1,
  lineErrorCount: 2,
  amountDiff: '5.00000000',
  uploadBytes: 2048,
  createdAt: '2026-09-10T08:00:00Z',
  finishedAt: '2026-09-10T08:00:02Z',
  ...overrides,
});

const row = (overrides: Partial<ReconciliationRow> = {}): ReconciliationRow => ({
  rowNo: 1,
  verdict: 'MATCHED',
  matchedBy: 'REQUEST_ID',
  providerRowRef: 'bill-1',
  localRef: 'evt-1',
  detail: { modelId: 'm-1', amount: '1.00', currency: 'USD' },
  ...overrides,
});

describe('NextAdminReconciliationsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listReconciliations.mockResolvedValue({ reports: [report()] });
    mockApi.reconciliationReport.mockResolvedValue(report());
    mockApi.reconciliationRows.mockResolvedValue({ rows: [row()], nextCursor: '' });
    vi.useFakeTimers();
  });

  afterEach(() => vi.useRealTimers());

  function mountView() {
    return mount(NextAdminReconciliationsView, { global: { plugins: [createPinia()] } });
  }

  it('renders reports and opens the four-state detail', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="recon-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('anthropic-claude');
    expect(wrapper.text()).toContain('已完成');

    await wrapper.find('[data-testid="recon-open"]').trigger('click');
    await flushPromises();

    expect(mockApi.reconciliationReport).toHaveBeenCalledWith('r1');
    expect(mockApi.reconciliationRows).toHaveBeenCalledWith(
      'r1',
      expect.objectContaining({ limit: 100 }),
    );
    const detail = wrapper.find('[data-testid="recon-detail"]');
    expect(detail.exists()).toBe(true);
    expect(detail.text()).toContain('总行数');
    expect(detail.text()).toContain('5.00000000');
    expect(detail.text()).toContain('bill-1');
    expect(detail.text()).toContain('m-1');
  });

  it('filters rows by verdict and pages with the cursor', async () => {
    mockApi.reconciliationRows
      .mockResolvedValueOnce({ rows: [row()], nextCursor: '' })
      .mockResolvedValueOnce({
        rows: [row({ rowNo: 2, verdict: 'PARTIAL', matchedBy: null })],
        nextCursor: 2,
      })
      .mockResolvedValueOnce({
        rows: [row({ rowNo: 3, verdict: 'UNMATCHED_LOCAL', matchedBy: null })],
        nextCursor: '',
      });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="recon-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="recon-filter-PARTIAL"]').trigger('click');
    await flushPromises();

    expect(mockApi.reconciliationRows).toHaveBeenLastCalledWith(
      'r1',
      expect.objectContaining({ state: 'PARTIAL', cursor: undefined }),
    );

    await wrapper.find('[data-testid="recon-rows-more"]').trigger('click');
    await flushPromises();

    expect(mockApi.reconciliationRows).toHaveBeenLastCalledWith(
      'r1',
      expect.objectContaining({ state: 'PARTIAL', cursor: 2 }),
    );
    // The cursor was exhausted by the last page — the button disappears.
    expect(wrapper.find('[data-testid="recon-rows-more"]').exists()).toBe(false);
  });

  it('validates the form and uploads with params, then polls to completion', async () => {
    mockApi.createReconciliation.mockResolvedValue(report({ id: 'r9', status: 'PENDING' }));
    mockApi.reconciliationReport.mockResolvedValue(report({ id: 'r9', status: 'SUCCEEDED' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="recon-upload-open"]').trigger('click');
    // Submit without a provider code → inline error, no API call.
    await wrapper.find('[data-testid="recon-submit"]').trigger('click');
    expect(mockApi.createReconciliation).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="recon-form-error"]').text()).toContain('providerCode');

    await wrapper.find('[data-testid="recon-provider"]').setValue('anthropic-claude');
    await wrapper.find('[data-testid="recon-window-from"]').setValue('2026-09-09T00:00:00Z');
    await wrapper.find('[data-testid="recon-window-to"]').setValue('2026-09-10T00:00:00Z');
    const input = wrapper.find('[data-testid="recon-file"]');
    const file = new File(['{"provider_request_id":"req-1"}'], 'bill.jsonl', {
      type: 'application/json',
    });
    Object.defineProperty(input.element, 'files', { value: [file] });
    await input.trigger('change');
    await flushPromises();

    await wrapper.find('[data-testid="recon-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createReconciliation).toHaveBeenCalledWith(
      {
        providerCode: 'anthropic-claude',
        currency: 'USD',
        windowFrom: '2026-09-09T00:00:00Z',
        windowTo: '2026-09-10T00:00:00Z',
      },
      file,
    );

    vi.advanceTimersByTime(2500);
    await flushPromises();
    expect(mockApi.reconciliationReport).toHaveBeenCalledWith('r9');
  });
});
