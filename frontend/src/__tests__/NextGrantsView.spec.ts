import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextGrantsView from '@/views/next/NextGrantsView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { toastState } from '@/ui/toast';
import type { ProblemDetails } from '@/types/api';
import type { Grant } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listGrants: vi.fn(),
  listProjects: vi.fn(),
  listCredentials: vi.fn(),
  listProviderProducts: vi.fn(),
  listSubscriptions: vi.fn(),
  createGrant: vi.fn(),
  grantModels: vi.fn(),
  updateGrantModels: vi.fn(),
  disableGrant: vi.fn(),
  adminListModels: vi.fn(),
  adminProbeModels: vi.fn(),
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

const catalogRow = (modelId: string, overrides: Record<string, unknown> = {}) => ({
  id: `row-${modelId}`,
  providerProductId: 'pr1',
  modelId,
  displayName: '',
  status: 'ACTIVE',
  source: 'PROBE',
  ...overrides,
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
        lastValidatedAt: null as unknown as string,
        lastValidationError: null as unknown as string,
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
        lastValidatedAt: null as unknown as string,
        lastValidationError: null as unknown as string,
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
    mockApi.listSubscriptions.mockResolvedValue([
      {
        id: 's1',
        providerProductId: 'pr1',
        productName: 'DeepSeek V4',
        name: 'DeepSeek 按量',
        billingMode: 'PAYG',
        status: 'ACTIVE',
        createdAt: '2026-07-01T00:00:00Z',
      },
      {
        id: 's2',
        providerProductId: 'pr2',
        productName: 'Moonshot',
        name: 'Moonshot 按量',
        billingMode: 'PAYG',
        status: 'ACTIVE',
        createdAt: '2026-07-01T00:00:00Z',
      },
    ]);
    mockApi.adminListModels.mockImplementation(async (productId?: string) => {
      if (productId === 'pr1') {
        return [catalogRow('deepseek-flash'), catalogRow('deepseek-v4-pro')] as never;
      }
      if (productId === 'pr2') {
        return [catalogRow('kimi-k2.5')] as never;
      }
      return [] as never;
    });
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

  async function openCreateForm(wrapper: ReturnType<typeof mountView>) {
    await wrapper.find('[data-testid="grant-create-open"]').trigger('click');
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

  it('derives the product from the credential and defaults the scope to the whole catalog', async () => {
    mockApi.createGrant.mockResolvedValue(grant({ id: 'g9' }));
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'QA · QA 回归');
    await pickOption(wrapper, 'deepseek-main');

    // Product block is derived from the credential's subscription (issue #571).
    const derived = wrapper.find('[data-testid="grant-create-product"]');
    expect(derived.text()).toContain('DeepSeek · DeepSeek V4');
    expect(derived.text()).toContain('deepseek-v4');

    await wrapper.find('[data-testid="grant-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createGrant).toHaveBeenCalledWith({
      projectId: 'p2',
      providerProductId: 'pr1',
      credentialId: 'c1',
      models: ['deepseek-flash', 'deepseek-v4-pro'],
    });
  });

  it('narrows the scope when catalog models are unchecked', async () => {
    mockApi.createGrant.mockResolvedValue(grant({ id: 'g9' }));
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'QA · QA 回归');
    await pickOption(wrapper, 'deepseek-main');

    const box = wrapper.find('[data-testid="model-scope-option-deepseek-v4-pro"]');
    expect(box.exists()).toBe(true);
    await box.setValue(false);
    await flushPromises();
    expect(wrapper.find('[data-testid="model-scope-count"]').text()).toContain('已选 1 个');

    await wrapper.find('[data-testid="grant-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createGrant).toHaveBeenCalledWith({
      projectId: 'p2',
      providerProductId: 'pr1',
      credentialId: 'c1',
      models: ['deepseek-flash'],
    });
  });

  it('offers the official model fetch when the catalog is empty; manual entry is opt-in (#592)', async () => {
    mockApi.adminListModels.mockResolvedValue([] as never);
    mockApi.createGrant.mockResolvedValue(grant({ id: 'g9' }));
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'QA · QA 回归');
    await pickOption(wrapper, 'deepseek-main');

    // Probe-first empty state; free text is not the default path anymore.
    expect(wrapper.find('[data-testid="model-scope-probe"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="model-scope-textarea"]').exists()).toBe(false);

    await wrapper.find('[data-testid="model-scope-manual-open"]').trigger('click');
    await flushPromises();
    const textarea = wrapper.find('[data-testid="model-scope-textarea"]');
    expect(textarea.exists()).toBe(true);
    await textarea.setValue('model-a\nmodel-b');
    await wrapper.find('[data-testid="grant-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createGrant).toHaveBeenCalledWith({
      projectId: 'p2',
      providerProductId: 'pr1',
      credentialId: 'c1',
      models: ['model-a', 'model-b'],
    });
  });

  it('fetches the official list in place and checks the whole discovered set (create mode)', async () => {
    mockApi.adminListModels
      .mockResolvedValueOnce([] as never)
      .mockResolvedValueOnce([
        catalogRow('kimi-k3', { source: 'OFFICIAL' }),
        catalogRow('kimi-k2.6', { source: 'OFFICIAL' }),
      ] as never);
    mockApi.adminProbeModels.mockResolvedValue({
      providerProductId: 'pr1',
      productCode: 'qa-product',
      modelCount: 2,
      probedAt: '2026-09-15T10:00:00Z',
      models: [
        { modelId: 'kimi-k3', displayName: '' },
        { modelId: 'kimi-k2.6', displayName: '' },
      ],
    });
    mockApi.createGrant.mockResolvedValue(grant({ id: 'g9' }));
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'QA · QA 回归');
    await pickOption(wrapper, 'deepseek-main');

    await wrapper.find('[data-testid="model-scope-probe"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminProbeModels).toHaveBeenCalledWith('pr1');
    // The discovered list replaces the empty state and is badged as official.
    expect(wrapper.find('[data-testid="model-scope-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="model-scope-probe-notice"]').text()).toContain('2 个模型');
    expect(wrapper.text()).toContain('官方');

    await wrapper.find('[data-testid="grant-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createGrant).toHaveBeenCalledWith({
      projectId: 'p2',
      providerProductId: 'pr1',
      credentialId: 'c1',
      models: ['kimi-k3', 'kimi-k2.6'],
    });
  });

  it('surfaces the sanitized probe failure and keeps manual entry available', async () => {
    mockApi.adminListModels.mockResolvedValue([] as never);
    mockApi.adminProbeModels.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: 'probe failed',
        status: 502,
        code: 'MODEL_PROBE_FAILED',
        detail: '上游拒绝：无效的 API Key',
        requestId: 'r1',
      } as ProblemDetails),
    );
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'QA · QA 回归');
    await pickOption(wrapper, 'deepseek-main');

    await wrapper.find('[data-testid="model-scope-probe"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="model-scope-probe-error"]').text()).toContain('上游拒绝');
    // The empty state (and with it the manual escape hatch) stays available.
    expect(wrapper.find('[data-testid="model-scope-manual-open"]').exists()).toBe(true);
  });

  it('blocks the submit for an existing project×product×credential triple', async () => {
    const wrapper = mountView();
    await flushPromises();

    await openCreateForm(wrapper);
    await pickOption(wrapper, 'CORE · Core AI');
    await pickOption(wrapper, 'deepseek-main');

    expect(wrapper.find('[data-testid="grant-create-duplicate"]').exists()).toBe(true);
    expect(
      (wrapper.find('[data-testid="grant-create-submit"]').element as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('edits the scope through catalog checkboxes and replaces it on save', async () => {
    mockApi.grantModels.mockResolvedValue(['deepseek-flash']);
    mockApi.updateGrantModels.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="grant-models-open"]').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="grant-models-drawer"]')).toBeTruthy();
    const checked = document.querySelector(
      '[data-testid="model-scope-option-deepseek-flash"]',
    ) as HTMLInputElement;
    expect(checked.checked).toBe(true);

    const extra = document.querySelector(
      '[data-testid="model-scope-option-deepseek-v4-pro"]',
    ) as HTMLInputElement;
    extra.click();
    await flushPromises();

    (document.querySelector('[data-testid="grant-models-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.updateGrantModels).toHaveBeenCalledWith('g1', [
      'deepseek-flash',
      'deepseek-v4-pro',
    ]);
    expect(toastState.items.some((t) => t.message.includes('模型范围已更新'))).toBe(true);
  });

  it('flags granted models missing from the catalog and drops them when unchecked', async () => {
    mockApi.grantModels.mockResolvedValue(['deepseek-flash', 'ghost-model']);
    mockApi.updateGrantModels.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="grant-models-open"]').trigger('click');
    await flushPromises();

    expect(document.querySelector('[data-testid="model-scope-phantom-warn"]')).toBeTruthy();
    const ghost = document.querySelector(
      '[data-testid="model-scope-option-ghost-model"]',
    ) as HTMLInputElement;
    expect(ghost.checked).toBe(true);
    ghost.click();
    await flushPromises();

    (document.querySelector('[data-testid="grant-models-save"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.updateGrantModels).toHaveBeenCalledWith('g1', ['deepseek-flash']);
  });

  it('drops a stale model-scope response when the drawer re-targets another grant (#440)', async () => {
    // A slow load for grant g1 must never land after the user opened g2:
    // saving would otherwise replace g2's scope with g1's model list.
    let releaseA: (models: string[]) => void = () => {};
    mockApi.grantModels
      .mockImplementationOnce(
        () =>
          new Promise<string[]>((resolve) => {
            releaseA = resolve;
          }),
      )
      .mockResolvedValueOnce(['kimi-k2.5']);
    mockApi.updateGrantModels.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    const openButtons = wrapper.findAll('[data-testid="grant-models-open"]');
    expect(openButtons.length).toBe(2);
    await openButtons[0]!.trigger('click'); // g1 — stays pending
    await openButtons[1]!.trigger('click'); // g2 — resolves immediately
    await flushPromises();

    releaseA(['deepseek-flash']); // the stale g1 response arrives late
    await flushPromises();

    // g2's product (pr2 / Moonshot) drives the catalog; the stale g1 scope
    // must not leak in as checked state.
    const kimi = document.querySelector(
      '[data-testid="model-scope-option-kimi-k2.5"]',
    ) as HTMLInputElement;
    expect(kimi.checked).toBe(true);
    expect(document.querySelector('[data-testid="model-scope-option-deepseek-flash"]')).toBeNull();

    (document.querySelector('[data-testid="grant-models-save"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.updateGrantModels).toHaveBeenCalledWith('g2', ['kimi-k2.5']);
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
