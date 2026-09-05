import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextApprovalCenterView from '@/views/next/NextApprovalCenterView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {} from '@/types/api';
import type { ModelApprovalView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listModelApprovals: vi.fn(),
  approveModelApproval: vi.fn(),
  rejectModelApproval: vi.fn(),
}));

const mockApi = vi.mocked(api);

const approval = (overrides: Partial<ModelApprovalView> = {}): ModelApprovalView => ({
  id: 'a1',
  virtualKeyId: 'k1',
  keyName: 'claude-code-main',
  keyDisplay: 'mqk_live_…8f2a',
  projectTag: 'core-ai',
  modelId: 'deepseek-v4.1',
  reason: '需要更强推理',
  status: 'PENDING',
  requesterId: 'u1',
  requesterName: 'demo2_user',
  reviewNote: null,
  reviewedByName: null,
  createdAt: '2026-09-03T00:00:00Z',
  updatedAt: '2026-09-03T00:00:00Z',
  ...overrides,
});

describe('NextApprovalCenterView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listModelApprovals.mockResolvedValue({
      items: [
        approval(),
        approval({ id: 'a2', status: 'APPROVED', reviewedByName: 'root', reviewNote: 'ok' }),
      ],
      nextCursor: undefined,
    });
  });

  function mountView() {
    return mount(NextApprovalCenterView, { global: { plugins: [createPinia()] } });
  }

  it('loads the pending queue by default and shows Chinese status pills', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.listModelApprovals).toHaveBeenCalledWith({ status: 'PENDING', size: 20 });
    expect(wrapper.find('[data-testid="approvals-queue-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('demo2_user');
    expect(wrapper.text()).toContain('deepseek-v4.1');
    expect(wrapper.text()).toContain('待审批');
    expect(wrapper.text()).toContain('mqk_live_…8f2a');
  });

  it('switches the filter and reloads with the chosen status', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="filter-approved"]').trigger('click');
    await flushPromises();

    expect(mockApi.listModelApprovals).toHaveBeenLastCalledWith({
      status: 'APPROVED',
      size: 20,
    });
  });

  it('approves a pending request with a note and reloads', async () => {
    mockApi.approveModelApproval.mockResolvedValue(approval({ status: 'APPROVED' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listModelApprovals as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="approve-open"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="review-panel"]').exists()).toBe(true);

    await wrapper.find('[data-testid="review-note"]').setValue('符合项目范围');
    await wrapper.find('[data-testid="review-confirm-approve"]').trigger('click');
    await flushPromises();

    expect(mockApi.approveModelApproval).toHaveBeenCalledWith('a1', '符合项目范围');
    expect(
      (mockApi.listModelApprovals as ReturnType<typeof vi.fn>).mock.calls.length,
    ).toBeGreaterThan(callsBefore);
    expect(toastState.items.some((t) => t.message.includes('已通过模型'))).toBe(true);
  });

  it('rejects with a note', async () => {
    mockApi.rejectModelApproval.mockResolvedValue(approval({ status: 'REJECTED' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="reject-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="review-note"]').setValue('超出当前范围');
    await wrapper.find('[data-testid="review-confirm-reject"]').trigger('click');
    await flushPromises();

    expect(mockApi.rejectModelApproval).toHaveBeenCalledWith('a1', '超出当前范围');
    expect(toastState.items.some((t) => t.message.includes('已驳回申请'))).toBe(true);
  });

  it('loads more pages when a cursor is present', async () => {
    mockApi.listModelApprovals
      .mockResolvedValueOnce({ items: [approval()], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({
        items: [approval({ id: 'a9' })],
        nextCursor: undefined,
      });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="approvals-load-more"]').trigger('click');
    await flushPromises();

    expect(mockApi.listModelApprovals).toHaveBeenLastCalledWith({
      status: 'PENDING',
      size: 20,
      before: 'cursor-1',
    });
    expect(wrapper.text()).toContain('mqk_live_…8f2a');
  });
});
