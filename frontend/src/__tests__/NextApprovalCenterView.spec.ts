import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextApprovalCenterView from '@/views/next/NextApprovalCenterView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { ModelApprovalView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listModelApprovals: vi.fn(),
  approveModelApproval: vi.fn(),
  rejectModelApproval: vi.fn(),
}));

const mockApi = vi.mocked(api);

type ApprovalPage = Awaited<ReturnType<typeof api.listModelApprovals>>;

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
  reviewNote: undefined,
  reviewedByName: undefined,
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

  /** The toolbar 刷新 button — no testid, so address it by its label. */
  function refreshButton(wrapper: ReturnType<typeof mountView>): HTMLButtonElement {
    const button = wrapper.findAll('button').find((b) => b.text().trim() === '刷新');
    expect(button, '刷新 button should render').toBeTruthy();
    return button!.element as HTMLButtonElement;
  }

  it('#PH69R2B: 放弃一次「加载更多」后，加载更多不会变成永远点不动的按钮', async () => {
    // Page 2 never comes back while the admin is still on the page.
    let releaseStale!: (page: ApprovalPage) => void;
    mockApi.listModelApprovals
      .mockResolvedValueOnce({ items: [approval()], nextCursor: 'cursor-1' })
      .mockImplementationOnce(
        () =>
          new Promise<ApprovalPage>((resolve) => {
            releaseStale = resolve;
          }),
      )
      .mockResolvedValueOnce({
        items: [approval({ id: 'a2', modelId: 'fresh-model' })],
        nextCursor: 'cursor-2',
      })
      .mockResolvedValueOnce({
        items: [approval({ id: 'a9', modelId: 'paged-model' })],
        nextCursor: undefined,
      });
    const wrapper = mountView();
    await flushPromises();

    // The admin asks for page 2 and gives up waiting for it.
    await wrapper.find('[data-testid="approvals-load-more"]').trigger('click');
    await flushPromises();
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(2);

    // …and hits 刷新 instead. That reload supersedes the page request above.
    refreshButton(wrapper).click();
    await flushPromises();
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(3);
    expect(wrapper.text()).toContain('fresh-model');

    // The abandoned page finally lands — #440 says it must not touch this list.
    releaseStale({ items: [approval({ id: 'stale', modelId: 'stale-model' })], nextCursor: 'x' });
    await flushPromises();
    expect(wrapper.text()).not.toContain('stale-model');
    expect(wrapper.text()).toContain('fresh-model');

    // The fresh page still has a cursor, so 加载更多 is on screen and must work.
    const more = wrapper.find('[data-testid="approvals-load-more"]');
    expect(more.exists(), '加载更多 should still be offered').toBe(true);
    await more.trigger('click');
    await flushPromises();
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(4);
    expect(wrapper.text()).toContain('paged-model');
  });

  it('#PH69R2B: 刷新在途时点「加载更多」，刷新按钮不会一直转圈', async () => {
    // The reload never comes back while the admin is still on the page.
    let releaseReload!: (page: ApprovalPage) => void;
    mockApi.listModelApprovals
      .mockResolvedValueOnce({ items: [approval()], nextCursor: 'cursor-1' })
      .mockImplementationOnce(
        () =>
          new Promise<ApprovalPage>((resolve) => {
            releaseReload = resolve;
          }),
      )
      .mockResolvedValueOnce({
        items: [approval({ id: 'a9', modelId: 'paged-model' })],
        nextCursor: undefined,
      });
    const wrapper = mountView();
    await flushPromises();

    // The reload is in flight: 刷新 (and the table) are meant to be busy.
    refreshButton(wrapper).click();
    await flushPromises();
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(2);
    expect(refreshButton(wrapper).disabled, '刷新 should be busy while reloading').toBe(true);

    // The admin gives up on the reload and asks for the next page instead. The
    // cursor from the first page is still on screen, so 加载更多 is still there.
    await wrapper.find('[data-testid="approvals-load-more"]').trigger('click');
    await flushPromises();
    expect(mockApi.listModelApprovals).toHaveBeenCalledTimes(3);

    // The abandoned reload lands — dropped by the guard, as #440 requires…
    releaseReload({ items: [approval({ id: 'stale', modelId: 'stale-model' })], nextCursor: 'x' });
    await flushPromises();
    expect(wrapper.text()).not.toContain('stale-model');

    // …but 刷新 must not be left spinning: nothing is in flight for it anymore.
    expect(refreshButton(wrapper).disabled).toBe(false);
    expect(refreshButton(wrapper).getAttribute('aria-busy')).toBeNull();
  });
});
