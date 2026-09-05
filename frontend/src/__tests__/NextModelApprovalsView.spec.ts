import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextModelApprovalsView from '@/views/next/NextModelApprovalsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {} from '@/types/api';
import type { VirtualKeyView, ModelApprovalView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  listMyModelApprovals: vi.fn(),
  submitModelApproval: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
    error: { type: String, default: '' },
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
      <label v-if="props.label">{{ props.label }}</label>
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
      <p v-if="props.error" class="stub-error">{{ props.error }}</p>
    </div>
  `,
});

const keyView = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView => ({
  id: '0190-0001',
  name: 'claude-code-main',
  purpose: 'CLAUDE_CODE',
  status: 'ACTIVE',
  displayPrefix: 'mqk_live_abc',
  lastFour: '8f2a',
  display: 'mqk_live_…8f2a',
  modelIds: ['claude-3-7-sonnet'],
  projectId: 'p1',
  projectTag: 'core-ai',
  cachePolicy: 'DISABLED',
  baseUrl: 'https://gateway.test.internal',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const approval = (overrides: Partial<ModelApprovalView> = {}): ModelApprovalView => ({
  id: '0190-0000-0000-00a1',
  virtualKeyId: '0190-0001',
  keyName: 'claude-code-main',
  keyDisplay: 'mqk_live_…8f2a',
  projectTag: 'core-ai',
  modelId: 'deepseek-v4-flash',
  reason: '编码任务需要更强的推理模型',
  status: 'PENDING',
  requesterId: 'u1',
  requesterName: 'demo2_user',
  reviewNote: null,
  reviewedByName: null,
  createdAt: '2026-09-03T00:00:00Z',
  updatedAt: '2026-09-03T00:00:00Z',
  ...overrides,
});

describe('NextModelApprovalsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listVirtualKeys.mockResolvedValue([
      keyView(),
      keyView({ id: '0190-0002', status: 'REVOKED' }),
    ]);
    mockApi.listMyModelApprovals.mockResolvedValue([
      approval(),
      approval({ id: 'a2', status: 'APPROVED', modelId: 'deepseek-v4.1' }),
      approval({
        id: 'a3',
        status: 'REJECTED',
        reviewNote: '超出当前项目范围',
        modelId: 'kimi-k2.5',
      }),
    ]);
  });

  function mountView() {
    return mount(NextModelApprovalsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders own applications with Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(mockApi.listVirtualKeys).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="model-approvals-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('deepseek-v4-flash');
    expect(wrapper.text()).toContain('待审批');
    expect(wrapper.text()).toContain('已通过');
    expect(wrapper.text()).toContain('已驳回');
    expect(wrapper.text()).toContain('超出当前项目范围');
  });

  it('submits an application and reloads the list', async () => {
    mockApi.submitModelApproval.mockResolvedValue(approval({ status: 'APPROVED' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listMyModelApprovals as ReturnType<typeof vi.fn>).mock.calls
      .length;

    await wrapper.find('[data-testid="model-approval-open"]').trigger('click');
    const keyButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('mqk_live_…8f2a'));
    expect(keyButton).toBeTruthy();
    await keyButton!.trigger('click');
    await flushPromises();

    const modelInput = wrapper.find('[data-testid="model-approval-model"]');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(modelInput.element, 'deepseek-v4.1');
    modelInput.element.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    await wrapper.find('[data-testid="model-approval-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.submitModelApproval).toHaveBeenCalledWith({
      virtualKeyId: '0190-0001',
      modelId: 'deepseek-v4.1',
      reason: undefined,
    });
    expect(
      (mockApi.listMyModelApprovals as ReturnType<typeof vi.fn>).mock.calls.length,
    ).toBeGreaterThan(callsBefore);
    expect(toastState.items.some((t) => t.message.includes('自动批准'))).toBe(true);
  });

  it('validates the form before submitting', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="model-approval-open"]').trigger('click');
    await wrapper.find('[data-testid="model-approval-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请选择 Virtual Key');
    expect(wrapper.text()).toContain('请填写模型 ID');
    expect(mockApi.submitModelApproval).not.toHaveBeenCalled();
  });
});
