import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProvidersView from '@/views/next/NextProvidersView.vue';
import * as api from '@/api';
import type { ProviderProductView } from '@/types/api';

vi.mock('@/api', () => ({
  listProviderProducts: vi.fn(),
  adminListModels: vi.fn(),
  adminCreateModel: vi.fn(),
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
});
