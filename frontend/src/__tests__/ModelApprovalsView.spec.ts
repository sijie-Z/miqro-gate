import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import ModelApprovalsView from '@/views/ModelApprovalsView.vue';
import * as api from '@/api';
import type { ModelApprovalView, VirtualKeyView } from '@/types/api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  listMyModelApprovals: vi.fn(),
  submitModelApproval: vi.fn(),
}));

const mockApi = vi.mocked(api);

/**
 * TDesign's TPopup teleports its panel into document.body behind a popper
 * state machine whose timing is not deterministic in jsdom. The stub renders
 * trigger and panel inline so option clicks exercise the real select model.
 * (Same pattern as KeysView.spec / AdminMcpServicesView.spec.)
 */
const PopupStub = defineComponent({
  name: 'TPopup',
  inheritAttrs: false,
  setup(_, { slots, expose }) {
    expose({
      update: () => {},
      getOverlay: () => null,
      getOverlayState: () => ({ hover: false }),
      getPopper: () => null,
      close: () => {},
    });
    return () => h('div', { class: 't-popup-stub' }, [slots.default?.(), slots.content?.()]);
  },
});

const key = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView => ({
  id: '0190-0000-0000-0000-0000000000a1',
  name: 'claude-code-main',
  purpose: 'CLAUDE_CODE',
  status: 'ACTIVE',
  displayPrefix: 'mqk_live_',
  lastFour: '8f2a',
  display: 'mqk_live_…8f2a',
  modelIds: ['model-alpha'],
  projectId: '0190-0000-0000-0000-0000000000b1',
  projectTag: 'core-ai',
  cachePolicy: 'DISABLED',
  baseUrl: 'https://gateway.test.internal',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const approval = (overrides: Partial<ModelApprovalView> = {}): ModelApprovalView => ({
  id: '0190-0000-0000-0000-0000000000c1',
  virtualKeyId: key().id,
  keyName: 'claude-code-main',
  keyDisplay: 'mqk_live_…8f2a',
  projectTag: 'core-ai',
  modelId: 'model-gamma',
  reason: '需要更强的编码模型',
  status: 'PENDING',
  requesterId: '0190-0000-0000-0000-0000000000d1',
  requesterName: 'Admin',
  createdAt: '2026-09-02T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(ModelApprovalsView, {
    global: { plugins: [TDesign, createPinia()], stubs: { TPopup: PopupStub } },
  });
}

describe('ModelApprovalsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listVirtualKeys.mockResolvedValue([
      key(),
      key({ id: '0190-0000-0000-0000-0000000000a2', status: 'REVOKED', name: 'old-key' }),
    ]);
    mockApi.listMyModelApprovals.mockResolvedValue([]);
  });

  async function openCreateAndPickKey(wrapper: ReturnType<typeof mountView>) {
    await wrapper.find('[data-testid="model-approval-open"]').trigger('click');
    await flushPromises();
    const items = wrapper.findAll('.t-select-option');
    const target = items.find((el) => el.text().includes('claude-code-main'));
    expect(target, 'key option should render via the popup stub').toBeTruthy();
    await target!.trigger('click');
    await flushPromises();
  }

  it('renders my requests with key display and model', async () => {
    mockApi.listMyModelApprovals.mockResolvedValue([
      approval(),
      approval({ id: 'c2', modelId: 'model-auto', status: 'APPROVED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('model-gamma');
    expect(wrapper.text()).toContain('需要更强的编码模型');
    expect(wrapper.text()).toContain('待审批');
    expect(wrapper.text()).toContain('已通过');
  });

  it('submits a request with key, trimmed model and reason', async () => {
    mockApi.submitModelApproval.mockResolvedValue(approval());

    const wrapper = mountView();
    await flushPromises();
    await openCreateAndPickKey(wrapper);

    await wrapper.find('[data-testid="model-approval-model"] input').setValue(' model-gamma ');
    await wrapper.find('.create-form textarea').setValue('需要更强的编码模型');
    await wrapper.find('[data-testid="model-approval-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.submitModelApproval).toHaveBeenCalledWith({
      virtualKeyId: key().id,
      modelId: 'model-gamma',
      reason: '需要更强的编码模型',
    });
  });

  it('submits without a reason when the field is empty', async () => {
    mockApi.submitModelApproval.mockResolvedValue(approval());

    const wrapper = mountView();
    await flushPromises();
    await openCreateAndPickKey(wrapper);

    await wrapper.find('[data-testid="model-approval-model"] input').setValue('model-gamma');
    await wrapper.find('[data-testid="model-approval-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.submitModelApproval).toHaveBeenCalledWith({
      virtualKeyId: key().id,
      modelId: 'model-gamma',
      reason: undefined,
    });
  });

  it('shows the inline error when submission fails', async () => {
    mockApi.submitModelApproval.mockRejectedValue(
      Object.assign(new Error('该模型已在 Key 上'), { code: 'MODEL_ALREADY_AVAILABLE' }),
    );

    const wrapper = mountView();
    await flushPromises();
    await openCreateAndPickKey(wrapper);

    await wrapper.find('[data-testid="model-approval-model"] input').setValue('model-alpha');
    await wrapper.find('[data-testid="model-approval-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('该模型已在 Key 上');
    expect(mockApi.submitModelApproval).toHaveBeenCalledTimes(1);
  });
});
