import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createPinia } from 'pinia';
import NextSettingsView from '@/views/next/NextSettingsView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';

/** UiSelect stub: renders clickable option buttons (mirrors the keys spec). */
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
      <button v-for="opt in props.options" :key="opt.value" type="button" class="stub-option" @click="pick(opt.value)">
        {{ opt.label }}
      </button>
    </div>
  `,
});

vi.mock('@/api', () => ({
  getUnattributedPolicy: vi.fn(),
  putUnattributedPolicy: vi.fn(),
  deleteUnattributedPolicy: vi.fn(),
  listCredentials: vi.fn(),
}));

const mockApi = vi.mocked(api);

describe('NextSettingsView', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mockApi.getUnattributedPolicy.mockResolvedValue({ configured: false });
    mockApi.listCredentials.mockResolvedValue([
      { id: 'cred-1', name: '专用凭证', status: 'ACTIVE' },
      { id: 'cred-2', name: '停用凭证', status: 'DISABLED' },
    ] as never);
  });

  it('renders the deployment facts table', async () => {
    const wrapper = mount(NextSettingsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="page-title"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="deploy-info"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('MiQroGate');
    expect(wrapper.text()).toContain('Docker Compose（单节点私有化）');
    expect(wrapper.text()).toContain('8080（管理 API）');
    expect(wrapper.text()).toContain('PostgreSQL 17');
  });

  it('#647: shows the fail-closed baseline and saves a policy via the active credentials', async () => {
    mockApi.putUnattributedPolicy.mockResolvedValue({
      configured: true,
      credentialId: 'cred-1',
      credentialName: '专用凭证',
      providerProductName: 'DeepSeek 官方按量 API',
      models: ['deepseek-flash'],
    });

    const wrapper = mount(NextSettingsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="unattributed-policy"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="unattributed-policy-state"]').text()).toContain(
      '未配置（失败关闭）',
    );

    // Only ACTIVE credentials are offered (stub select renders options inline).
    expect(wrapper.find('[data-testid="unattributed-policy-credential"]').exists()).toBe(true);
    const optionTexts = wrapper.findAll('.stub-option').map((el) => el.text());
    expect(optionTexts.some((t) => t.includes('专用凭证'))).toBe(true);
    expect(optionTexts.some((t) => t.includes('停用凭证'))).toBe(false);

    await wrapper.find('[data-testid="unattributed-policy-models"]').setValue('deepseek-flash,');
    // Pick the credential option through the UiSelect stub.
    const option = wrapper.findAll('.stub-option').find((el) => el.text().includes('专用凭证'));
    await option!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="unattributed-policy-save"]').trigger('click');
    await flushPromises();

    expect(mockApi.putUnattributedPolicy).toHaveBeenCalledWith({
      credentialId: 'cred-1',
      models: ['deepseek-flash'],
    });
    expect(wrapper.find('[data-testid="unattributed-policy-state"]').text()).toContain('已启用');
  });

  it('#1306: surfaces a policy load failure instead of rendering nothing', async () => {
    mockApi.getUnattributedPolicy.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: 'internal error',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: '服务内部错误，请稍后重试。',
        requestId: 'rq-1306',
      }),
    );

    const wrapper = mount(NextSettingsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    // The failure must be visible — and must not masquerade as "未配置（失败关闭）".
    expect(wrapper.find('[data-testid="unattributed-policy-error"]').text()).toContain(
      '服务内部错误，请稍后重试。',
    );
    expect(wrapper.find('[data-testid="unattributed-policy-state"]').exists()).toBe(false);

    // …and the operator must be able to recover without F5.
    mockApi.getUnattributedPolicy.mockResolvedValue({ configured: false });
    await wrapper.find('[data-testid="unattributed-policy-retry"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="unattributed-policy-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="unattributed-policy-state"]').text()).toContain(
      '未配置（失败关闭）',
    );
  });

  it('#647: clear turns the policy off', async () => {
    mockApi.getUnattributedPolicy.mockResolvedValue({
      configured: true,
      credentialId: 'cred-1',
      credentialName: '专用凭证',
      models: [],
    });
    mockApi.deleteUnattributedPolicy.mockResolvedValue(undefined);

    const wrapper = mount(NextSettingsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();

    await wrapper.find('[data-testid="unattributed-policy-clear"]').trigger('click');
    await flushPromises();

    expect(mockApi.deleteUnattributedPolicy).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="unattributed-policy-state"]').text()).toContain(
      '未配置（失败关闭）',
    );
  });
});
