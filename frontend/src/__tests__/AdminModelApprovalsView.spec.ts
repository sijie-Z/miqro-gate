import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminModelApprovalsView from '@/views/AdminModelApprovalsView.vue';
import * as api from '@/api';
import type { ModelApprovalView } from '@/types/api';

vi.mock('@/api', () => ({
  listModelApprovals: vi.fn(),
  approveModelApproval: vi.fn(),
  rejectModelApproval: vi.fn(),
}));

const mockApi = vi.mocked(api);

const approval = (overrides: Partial<ModelApprovalView> = {}): ModelApprovalView => ({
  id: '0190-0000-0000-0000-0000000000c1',
  virtualKeyId: '0190-0000-0000-0000-0000000000a1',
  keyName: 'claude-code-main',
  keyDisplay: 'mqk_live_…8f2a',
  projectTag: 'core-ai',
  modelId: 'model-gamma',
  reason: '需要更强的编码模型',
  status: 'PENDING',
  requesterId: '0190-0000-0000-0000-0000000000d1',
  requesterName: '张三',
  createdAt: '2026-09-02T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminModelApprovalsView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminModelApprovalsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listModelApprovals.mockResolvedValue({ items: [], nextCursor: undefined });
  });

  it('loads the pending queue by default and renders reviewable rows', async () => {
    mockApi.listModelApprovals.mockResolvedValue({
      items: [approval(), approval({ id: 'c2', modelId: 'model-auto', status: 'APPROVED' })],
      nextCursor: undefined,
    });

    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.listModelApprovals).toHaveBeenCalledWith({ status: 'PENDING', size: 20 });
    expect(wrapper.text()).toContain('张三');
    expect(wrapper.text()).toContain('model-gamma');
    expect(wrapper.text()).toContain('需要更强的编码模型');
    expect(wrapper.text()).toContain('待审批');
    // Only PENDING rows carry the review buttons.
    const approveButtons = wrapper.findAll('[data-testid="approve-open"]');
    expect(approveButtons).toHaveLength(1);
  });

  it('approves with a note and refreshes the queue', async () => {
    mockApi.listModelApprovals.mockResolvedValue({ items: [approval()], nextCursor: undefined });
    mockApi.approveModelApproval.mockResolvedValue(approval({ status: 'APPROVED' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="approve-open"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="review-panel"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('通过并生效');

    await wrapper.find('[data-testid="review-note"] textarea').setValue('granted');
    await wrapper.find('[data-testid="review-confirm-approve"]').trigger('click');
    await flushPromises();

    expect(mockApi.approveModelApproval).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000c1',
      'granted',
    );
    expect(mockApi.approveModelApproval).toHaveBeenCalledTimes(1);
    // The queue reloads after the review and the panel closes.
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="review-panel"]').exists()).toBe(false);
  });

  it('rejects with a note', async () => {
    mockApi.listModelApprovals.mockResolvedValue({ items: [approval()], nextCursor: undefined });
    mockApi.rejectModelApproval.mockResolvedValue(approval({ status: 'REJECTED' }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="reject-open"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="review-panel"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('确认驳回');

    await wrapper.find('[data-testid="review-note"] textarea').setValue('超预算');
    await wrapper.find('[data-testid="review-confirm-reject"]').trigger('click');
    await flushPromises();

    expect(mockApi.rejectModelApproval).toHaveBeenCalledWith(
      '0190-0000-0000-0000-0000000000c1',
      '超预算',
    );
    expect(mockApi.approveModelApproval).not.toHaveBeenCalled();
  });

  it('loads more with the keyset cursor', async () => {
    mockApi.listModelApprovals
      .mockResolvedValueOnce({ items: [approval()], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({
        items: [approval({ id: 'c9', modelId: 'model-delta' })],
        nextCursor: undefined,
      });

    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.text()).toContain('加载更多');

    const loadMore = wrapper.findAll('button').find((b) => b.text().includes('加载更多'));
    expect(loadMore, 'load-more button should render').toBeTruthy();
    await loadMore!.trigger('click');
    await flushPromises();

    expect(mockApi.listModelApprovals).toHaveBeenLastCalledWith({
      status: 'PENDING',
      size: 20,
      before: 'cursor-1',
    });
    expect(wrapper.text()).toContain('model-delta');
  });
});
