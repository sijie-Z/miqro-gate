import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextGrantsView from '@/views/next/NextGrantsView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { Grant } from '@/types/api';

vi.mock('@/api', () => ({
  listGrants: vi.fn(),
  listProjects: vi.fn(),
  listCredentials: vi.fn(),
  listProviderProducts: vi.fn(),
  createGrant: vi.fn(),
  grantModels: vi.fn(),
  updateGrantModels: vi.fn(),
  disableGrant: vi.fn(),
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
    </div>
  `,
});

const grant = (overrides: Partial<Grant> = {}): Grant => ({
  id: 'g1',
  projectId: 'p1',
  providerProductId: 'pr1',
  upstreamCredentialId: 'c1',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

describe('NextGrantsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    document.body.innerHTML = '';
    mockApi.listGrants.mockResolvedValue([
      grant(),
      grant({
        id: 'g2',
        projectId: 'p2',
        status: 'DISABLED',
        upstreamCredentialId: 'c2',
        providerProductId: 'pr2',
      }),
    ]);
    mockApi.listProjects.mockResolvedValue([
      {
        id: 'p1',
        code: 'CORE',
        name: 'Core AI',
        status: 'ACTIVE',
        projectTag: 'core-ai',
        createdAt: '2026-08-01T00:00:00Z',
      },
      {
        id: 'p2',
        code: 'QA',
        name: 'QA 回归',
        status: 'ACTIVE',
        projectTag: 'qa',
        createdAt: '2026-08-01T00:00:00Z',
      },
    ]);
    mockApi.listCredentials.mockResolvedValue([
      {
        id: 'c1',
        name: 'deepseek-main',
        subscriptionId: 's1',
        status: 'ACTIVE',
        activeVersionId: 'v1',
        fingerprintPrefix: 'fp1',
        lastValidatedAt: null,
        lastValidationError: null,
        version: 1,
        createdAt: '2026-07-01T00:00:00Z',
        updatedAt: '2026-07-01T00:00:00Z',
      },
      {
        id: 'c2',
        name: 'moonshot-main',
        subscriptionId: 's2',
        status: 'ACTIVE',
        activeVersionId: 'v1',
        fingerprintPrefix: 'fp2',
        lastValidatedAt: null,
        lastValidationError: null,
        version: 1,
        createdAt: '2026-07-02T00:00:00Z',
        updatedAt: '2026-07-02T00:00:00Z',
      },
    ]);
    mockApi.listProviderProducts.mockResolvedValue([
      {
        id: 'pr1',
        providerSlug: 'deepseek',
        providerName: 'DeepSeek',
        productCode: 'deepseek-v4',
        displayName: 'DeepSeek V4',
        billingMode: 'PAYG',
        protocols: 'openai,anthropic',
        baseUrlHost: 'api.deepseek.com',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'none',
      },
      {
        id: 'pr2',
        providerSlug: 'moonshot',
        providerName: 'Moonshot',
        productCode: 'moonshot',
        displayName: 'Moonshot',
        billingMode: 'PAYG',
        protocols: 'openai',
        baseUrlHost: 'api.moonshot.cn',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'official',
      },
    ]);
  });

  function mountView() {
    return mount(NextGrantsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  async function pickOption(wrapper: ReturnType<typeof mountView>, label: string) {
    const btn = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
    expect(btn, `option "${label}" should exist`).toBeTruthy();
    await btn!.trigger('click');
    await flushPromises();
  }

  it('renders grants with resolved display names', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="grants-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('CORE · Core AI');
    expect(wrapper.text()).toContain('deepseek-main');
    expect(wrapper.text()).toContain('DeepSeek · DeepSeek V4');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('停用');
  });

  it('creates a grant with the selected project, credential and models', async () => {
    mockApi.createGrant.mockResolvedValue(grant({ id: 'g9' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="grant-create-open"]').trigger('click');
    await pickOption(wrapper, 'CORE · Core AI');
    await pickOption(wrapper, 'deepseek-main');
    const models = wrapper.find('[data-testid="grant-create-models"]');
    await models.setValue('claude-3-7-sonnet\ngpt-5.2');
    await wrapper.find('[data-testid="grant-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createGrant).toHaveBeenCalledWith({
      projectId: 'p1',
      providerProductId: '',
      credentialId: 'c1',
      models: ['claude-3-7-sonnet', 'gpt-5.2'],
    });
  });

  it('opens the model-scope drawer and replaces the scope on save', async () => {
    mockApi.grantModels.mockResolvedValue(['claude-3-7-sonnet']);
    mockApi.updateGrantModels.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="grant-models-open"]').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="grant-models-drawer"]')).toBeTruthy();
    const input = document.querySelector(
      '[data-testid="grant-models-input"]',
    ) as HTMLTextAreaElement;
    expect(input).toBeTruthy();
    expect(input.value).toContain('claude-3-7-sonnet');

    input.value = 'kimi-k2.5';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="grant-models-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.updateGrantModels).toHaveBeenCalledWith('g1', ['kimi-k2.5']);
    expect(toastState.items.some((t) => t.message.includes('模型范围已更新'))).toBe(true);
  });

  it('disables an active grant through the confirmation gate', async () => {
    mockApi.disableGrant.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="grant-disable"]').trigger('click');
    await flushPromises();

    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '禁用' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.disableGrant).toHaveBeenCalledWith('g1');
  });
});
