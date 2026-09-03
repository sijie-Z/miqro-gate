import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProvidersView from '@/views/next/NextProvidersView.vue';
import * as api from '@/api';
import type { ProviderProductView } from '@/types/api';

vi.mock('@/api', () => ({ listProviderProducts: vi.fn() }));

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
});
