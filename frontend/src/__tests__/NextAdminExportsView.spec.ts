import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminExportsView from '@/views/next/NextAdminExportsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { ExportTask } from '@/types/generated-api';

vi.mock('@/api', () => ({
  exportRecent: vi.fn(),
  exportStatus: vi.fn(),
  createExport: vi.fn(),
}));

const mockApi = vi.mocked(api);

const task = (overrides: Partial<ExportTask> = {}): ExportTask => ({
  id: 'e1',
  format: 'CSV',
  periodFrom: '2026-08-01T00:00:00Z',
  periodTo: '2026-08-31T00:00:00Z',
  status: 'SUCCEEDED',
  sha256: 'abc',
  rowCount: 120,
  byteCount: 2048,
  createdAt: '2026-09-01T00:00:00Z',
  finishedAt: '2026-09-01T00:00:02Z',
  ...overrides,
});

describe('NextAdminExportsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.exportRecent.mockResolvedValue([
      task(),
      task({ id: 'e2', status: 'PENDING', format: 'JSONL', rowCount: undefined }),
    ]);
    vi.useFakeTimers();
  });

  afterEach(() => vi.useRealTimers());

  function mountView() {
    return mount(NextAdminExportsView, { global: { plugins: [createPinia()] } });
  }

  it('renders tasks with status badges and rows', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="exports-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('CSV');
    expect(wrapper.text()).toContain('已完成');
    expect(wrapper.text()).toContain('排队中');
    expect(wrapper.text()).toContain('120');
  });

  it('#PH89: 窗口列与同一行的创建时间同口径（本地），不写后端 UTC 日', async () => {
    // One instant, two columns. 2026-08-31T16:30:00Z is chosen so the local and
    // UTC clocks disagree at UTC+8 — the day turns over between them, which is
    // exactly what the window column used to print.
    const instant = '2026-08-31T16:30:00Z';
    mockApi.exportRecent.mockResolvedValue([
      task({ id: 'tz', periodFrom: instant, periodTo: instant, createdAt: instant }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    // Derived with local getters, so this holds in whatever zone it runs in.
    const d = new Date(instant);
    const pad = (n: number) => String(n).padStart(2, '0');
    const localDay = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const localClock = `${localDay} ${pad(d.getHours())}:${pad(d.getMinutes())}`;

    const text = wrapper.text();
    expect(text).toContain(localClock);
    expect(text).toContain(`${localDay} → ${localDay}`);
  });

  it('#716: marks a file that contains corrections, and leaves an untouched one unmarked', async () => {
    mockApi.exportRecent.mockResolvedValue([
      task({ id: 'a1', adjustmentLevel: 'PRESENT' }),
      task({ id: 'a2', adjustmentLevel: 'NONE' }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0]!.find('[data-testid="export-adjustment-level"]').text()).toContain('含调整');
    // NONE gets no chip: a badge on nearly every row would drown the one that matters.
    expect(rows[1]!.find('[data-testid="export-adjustment-level"]').exists()).toBe(false);
  });

  it('creates an export and polls to completion', async () => {
    mockApi.createExport.mockResolvedValue(task({ id: 'e9', status: 'PENDING' }));
    mockApi.exportStatus.mockResolvedValue(task({ id: 'e9', status: 'SUCCEEDED', rowCount: 5 }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="export-create-open"]').trigger('click');
    await wrapper.find('[data-testid="export-from"]').setValue('2026-09-01T00:00:00Z');
    await wrapper.find('[data-testid="export-to"]').setValue('2026-09-02T00:00:00Z');
    await wrapper.find('[data-testid="export-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createExport).toHaveBeenCalledWith(
      'CSV',
      '2026-09-01T00:00:00Z',
      '2026-09-02T00:00:00Z',
    );
    vi.advanceTimersByTime(2500);
    await flushPromises();
    expect(mockApi.exportStatus).toHaveBeenCalledWith('e9');
  });

  it('#PH35: a second click while the create request is in flight does not queue a second export', async () => {
    // ExportTaskService.create mints a fresh task id, INSERTs a row and hands
    // the task to the worker; nothing dedupes the (tenant, window, format)
    // triple, so two POSTs = two full exports over the same data.
    let releaseCreate: (value: unknown) => void = () => {};
    mockApi.createExport.mockImplementation(
      () =>
        new Promise((resolve) => {
          releaseCreate = resolve;
        }) as never,
    );
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="export-create-open"]').trigger('click');
    await wrapper.find('[data-testid="export-from"]').setValue('2026-09-01T00:00:00Z');
    await wrapper.find('[data-testid="export-to"]').setValue('2026-09-02T00:00:00Z');

    const submit = wrapper.find('[data-testid="export-create-submit"]');
    await submit.trigger('click');
    await flushPromises();
    // Second click lands while the first POST is still unanswered.
    await submit.trigger('click');
    await flushPromises();

    expect(mockApi.createExport).toHaveBeenCalledTimes(1);

    releaseCreate(task({ id: 'e9', status: 'PENDING' }));
    await flushPromises();
  });

  it('#PH53: one failed status poll must not stop polling for good', async () => {
    // A transient failure (proxy 502, server restart, laptop sleep) must not be
    // treated as terminal: killing the interval leaves the row showing a status
    // that is already wrong, with no message and no way back but a page reload.
    mockApi.exportRecent.mockResolvedValue([task({ id: 'e9', status: 'RUNNING' })]);
    mockApi.createExport.mockResolvedValue(task({ id: 'e9', status: 'RUNNING' }));
    mockApi.exportStatus
      .mockRejectedValueOnce(new Error('transient'))
      .mockResolvedValue(task({ id: 'e9', status: 'SUCCEEDED', rowCount: 5 }));

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="export-create-open"]').trigger('click');
    await wrapper.find('[data-testid="export-from"]').setValue('2026-09-01T00:00:00Z');
    await wrapper.find('[data-testid="export-to"]').setValue('2026-09-02T00:00:00Z');
    await wrapper.find('[data-testid="export-create-submit"]').trigger('click');
    await flushPromises();

    vi.advanceTimersByTime(2500);
    await flushPromises();
    expect(mockApi.exportStatus).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('生成中');

    vi.advanceTimersByTime(2500);
    await flushPromises();
    expect(mockApi.exportStatus).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('已完成');
  });

  it('#PH53: retrying is bounded, and giving up is announced', async () => {
    // The other half of the contract: retrying must not become an infinite 2 s
    // poll against a backend that is simply down, and stopping must not be silent.
    mockApi.exportRecent.mockResolvedValue([task({ id: 'e9', status: 'RUNNING' })]);
    mockApi.createExport.mockResolvedValue(task({ id: 'e9', status: 'RUNNING' }));
    mockApi.exportStatus.mockRejectedValue(new Error('down'));

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="export-create-open"]').trigger('click');
    await wrapper.find('[data-testid="export-from"]').setValue('2026-09-01T00:00:00Z');
    await wrapper.find('[data-testid="export-to"]').setValue('2026-09-02T00:00:00Z');
    await wrapper.find('[data-testid="export-create-submit"]').trigger('click');
    await flushPromises();

    // Advance exactly one interval per iteration so the tick count stays 1:1
    // (2 500 ms steps would drift against the 2 000 ms interval).
    for (let tick = 1; tick <= 5; tick += 1) {
      vi.advanceTimersByTime(2000);
      await flushPromises();
      expect(mockApi.exportStatus).toHaveBeenCalledTimes(tick);
    }
    expect(toastState.items.some((t) => t.message.includes('已停止自动刷新'))).toBe(true);

    vi.advanceTimersByTime(20000);
    await flushPromises();
    expect(mockApi.exportStatus).toHaveBeenCalledTimes(5);
  });
});
