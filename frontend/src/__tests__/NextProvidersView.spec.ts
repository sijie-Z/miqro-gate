import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProvidersView from '@/views/next/NextProvidersView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { ProviderProductView } from '@/types/api';

vi.mock('@/api', () => ({
  listProviderProducts: vi.fn(),
  adminListModels: vi.fn(),
  adminCreateModel: vi.fn(),
  adminProbeModels: vi.fn(),
  adminModelProbeStatus: vi.fn(),
  adminDeleteModel: vi.fn(),
}));

const mockApi = vi.mocked(api);

const product = (overrides: Partial<ProviderProductView> = {}): ProviderProductView => ({
  id: '0190-0000-0000-0020',
  providerSlug: 'deepseek',
  providerName: 'DeepSeek',
  productCode: 'deepseek-payg-api',
  displayName: 'DeepSeek PAYG',
  billingMode: 'PAYG',
  protocols: '["messages"]',
  baseUrlHost: 'api.deepseek.com',
  implementationStatus: 'VERIFIED',
  balanceAuthority: 'OFFICIAL_API',
  ...overrides,
});

describe('NextProvidersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listProviderProducts.mockResolvedValue([
      product(),
      product({
        id: '0021',
        providerSlug: 'aliyun',
        providerName: '阿里云',
        productCode: 'bailian-coding-plan',
        displayName: '百炼 Coding Plan',
        billingMode: 'TOKEN_PACKAGE',
        baseUrlHost: 'coding.dashscope.aliyuncs.com',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'UNAVAILABLE',
      }),
    ]);
  });

  function mountView() {
    return mount(NextProvidersView, { global: { plugins: [createPinia()] } });
  }

  it('renders the provider catalogue with status and balance labels', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="products-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('DeepSeek PAYG');
    expect(wrapper.text()).toContain('已验证');
    expect(wrapper.text()).toContain('官方 API');
    expect(wrapper.text()).toContain('百炼 Coding Plan');
    expect(wrapper.text()).toContain('已实现');
    expect(wrapper.text()).toContain('不可用');
    expect(wrapper.text()).toContain('api.deepseek.com');
  });

  it('F18: opens the model catalog drawer and adds a manual model', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminCreateModel.mockResolvedValue({
      id: 'mm1',
      providerProductId: '0190-0000-0000-0020',
      modelId: 'manual-probe-fallback',
      displayName: '人工兜底',
      status: 'ACTIVE',
      source: 'MANUAL',
      version: 0,
      updatedAt: '2026-09-08T00:00:00Z',
    });
    const wrapper = mount(NextProvidersView, { global: { plugins: [createPinia()] } });
    await flushPromises();

    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();
    await flushPromises();
    expect(mockApi.adminListModels).toHaveBeenCalledWith('0190-0000-0000-0020');
    const dialog = document.querySelector('[data-testid="product-models-dialog"]');
    expect(dialog, 'models dialog should open').toBeTruthy();
    expect(dialog!.textContent).toContain('暂无目录模型');

    mockApi.adminListModels.mockResolvedValue([
      {
        id: 'mm1',
        providerProductId: '0190-0000-0000-0020',
        modelId: 'manual-probe-fallback',
        displayName: '人工兜底',
        status: 'ACTIVE',
        source: 'MANUAL',
        version: 0,
        updatedAt: '2026-09-08T00:00:00Z',
      },
    ]);
    const idInput = document.querySelector(
      '[data-testid="product-models-id"]',
    ) as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(idInput, 'manual-probe-fallback');
    idInput.dispatchEvent(new Event('input', { bubbles: true }));
    (document.querySelector('[data-testid="product-models-add"]') as HTMLButtonElement).click();
    await flushPromises();
    const dialogAfter = document.querySelector('[data-testid="product-models-dialog"]');
    expect(dialogAfter?.textContent, 'no guard error expected').not.toContain('模型 ID 必填');
    expect(mockApi.adminCreateModel).toHaveBeenCalledWith('0190-0000-0000-0020', {
      modelId: 'manual-probe-fallback',
      displayName: undefined,
    });
    await flushPromises();
    expect(dialog!.textContent).toContain('manual-probe-fallback');
    expect(dialog!.textContent).toContain('人工');
  });

  it('I4: probes the provider model catalog and shows the last probe status', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminModelProbeStatus.mockResolvedValue({
      status: 'SUCCEEDED',
      error: null,
      modelCount: 2,
      probedAt: '2026-09-10T10:00:00Z',
    });
    mockApi.adminProbeModels.mockResolvedValue({
      providerProductId: '0190-0000-0000-0020',
      productCode: 'deepseek-payg-api',
      modelCount: 2,
      probedAt: '2026-09-10T12:00:00Z',
      models: [{ modelId: 'deepseek-chat', displayName: 'DeepSeek Chat' }],
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();

    const statusLine = document.querySelector('[data-testid="product-probe-status"]');
    expect(statusLine?.textContent).toContain('上次探测成功');
    expect(statusLine?.textContent).toContain('2 个模型');

    (document.querySelector('[data-testid="product-probe"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminProbeModels).toHaveBeenCalledWith('0190-0000-0000-0020');
    expect(mockApi.adminListModels).toHaveBeenCalledTimes(2);
  });

  it('I4: a failed probe surfaces the sanitized error inline', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminModelProbeStatus.mockResolvedValue({
      status: 'FAILED',
      error: 'DeepSeek /models returned HTTP 500',
      modelCount: null,
      probedAt: '2026-09-10T11:00:00Z',
    });
    mockApi.adminProbeModels.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: 'probe failed',
        status: 502,
        code: 'MODEL_PROBE_FAILED',
        detail: 'DeepSeek /models returned HTTP 500',
        requestId: 'rq-1',
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();

    (document.querySelector('[data-testid="product-probe"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(document.querySelector('[data-testid="product-probe-error"]')?.textContent).toContain(
      'HTTP 500',
    );
    expect(
      document.querySelector('[data-testid="product-probe-status"]')?.textContent,
    ).toContain('上次探测失败');
  });
});
