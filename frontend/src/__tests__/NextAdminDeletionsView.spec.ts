import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminDeletionsView from '@/views/next/NextAdminDeletionsView.vue';
import * as api from '@/api';
import type { UsageDeletionRequest } from '@/types/api';

vi.mock('@/api', () => ({
  deletionRecent: vi.fn(),
  deletionPreview: vi.fn(),
  createDeletion: vi.fn(),
  confirmDeletion: vi.fn(),
}));

const mockApi = vi.mocked(api);

const request: UsageDeletionRequest = {
  id: 'd1',
  periodFrom: '2026-08-01T00:00:00Z',
  periodTo: '2026-08-31T00:00:00Z',
  previewCount: 500,
  status: 'EXECUTED',
  deletedCount: 500,
  executedAt: '2026-09-01T00:00:00Z',
  expiresAt: '2026-09-01T00:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
};

describe('NextAdminDeletionsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.deletionRecent.mockResolvedValue([request]);
    mockApi.deletionPreview.mockResolvedValue({ count: 123 });
  });

  function mountView() {
    return mount(NextAdminDeletionsView, { global: { plugins: [createPinia()] } });
  }

  it('renders deletion requests with statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="deletions-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('500');
    expect(wrapper.text()).toContain('已执行');
  });

  it('previews, creates and confirms a deletion with the token', async () => {
    mockApi.createDeletion.mockResolvedValue({
      id: 'd9',
      previewCount: 123,
      confirmToken: 'tok-abc',
      expiresAt: '2026-09-04T00:00:00Z',
    });
    mockApi.confirmDeletion.mockResolvedValue(request);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="deletion-preview"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="deletion-preview-count"]').text()).toContain('123');

    await wrapper.find('[data-testid="deletion-create"]').trigger('click');
    await flushPromises();
    expect(mockApi.createDeletion).toHaveBeenCalled();
    expect(document.body.textContent).toContain('tok-abc');

    const tokenInput = document.querySelector(
      '[data-testid="deletion-confirm-token"]',
    ) as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(tokenInput, 'tok-abc');
    tokenInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (
      document.querySelector('[data-testid="deletion-confirm-submit"]') as HTMLButtonElement
    ).click();
    await flushPromises();

    expect(mockApi.confirmDeletion).toHaveBeenCalledWith('d9', 'tok-abc');
  });
});
