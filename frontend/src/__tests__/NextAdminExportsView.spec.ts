import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminExportsView from '@/views/next/NextAdminExportsView.vue';
import * as api from '@/api';
import type {} from '@/types/api';
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
});
