import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import TDesign from 'tdesign-vue-next';
import AdminPricesView from '@/views/AdminPricesView.vue';
import * as api from '@/api';
import type { PriceSnapshotView, ProviderProductView } from '@/types/api';

vi.mock('@/api', () => ({
  listPrices: vi.fn(),
  createPrice: vi.fn(),
  listProviderProducts: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** See KeysView.spec.ts — popup positioning is not app logic in jsdom. */
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

const price = (overrides: Partial<PriceSnapshotView> = {}): PriceSnapshotView => ({
  id: '0190-0000-0000-0000-000000000040',
  providerProductId: '0190-0000-0000-0000-000000000020',
  modelId: 'claude-3-7-sonnet',
  tokenType: 'INPUT',
  currency: 'USD',
  unitPrice: '3.0000',
  effectiveFrom: '2026-08-26T00:00:00Z',
  source: 'MANUAL',
  createdBy: '0190-0000-0000-0000-000000000001',
  createdAt: '2026-08-26T00:00:00Z',
  ...overrides,
});

const products: ProviderProductView[] = [
  {
    id: '0190-0000-0000-0000-000000000020',
    providerSlug: 'anthropic',
    providerName: 'Anthropic',
    productCode: 'anthropic-payg-api',
    displayName: 'Anthropic API',
    billingMode: 'PAYG',
    protocols: '["messages"]',
    baseUrlHost: 'api.anthropic.com',
    implementationStatus: 'IMPLEMENTED',
    balanceAuthority: 'UNAVAILABLE',
  },
];

function mountView() {
  return mount(AdminPricesView, {
    global: {
      plugins: [TDesign, createPinia()],
      stubs: { TPopup: PopupStub },
    },
  });
}

describe('AdminPricesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listPrices.mockResolvedValue([price()]);
    mockApi.listProviderProducts.mockResolvedValue(products);
  });

  it('renders the price catalog with resolved product names', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="prices-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Anthropic API');
    expect(wrapper.text()).toContain('claude-3-7-sonnet');
    expect(wrapper.text()).toContain('输入');
    expect(wrapper.text()).toContain('$3.0000 / 1M');
    expect(wrapper.text()).toContain('人工录入');
  });

  it('shows an empty state when no prices exist', async () => {
    mockApi.listPrices.mockResolvedValue([]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有模型单价');
  });

  it('creates a price snapshot with the entered fields', async () => {
    mockApi.listPrices.mockResolvedValue([]);
    mockApi.createPrice.mockResolvedValue(price());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="price-create-open"]').trigger('click');
    await wrapper.find('[data-testid="price-create-model"] input').setValue('deepseek-chat');
    await wrapper.find('[data-testid="price-create-unit"] input').setValue('2.5000');
    const productOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('Anthropic API'));
    await productOption!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="price-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createPrice).toHaveBeenCalledWith({
      providerProductId: '0190-0000-0000-0000-000000000020',
      modelId: 'deepseek-chat',
      tokenType: 'INPUT',
      currency: 'CNY',
      unitPrice: '2.5',
      source: 'MANUAL',
    });
    expect(mockApi.listPrices).toHaveBeenCalledTimes(2);
  });

  it('surfaces backend create errors with requestId inline', async () => {
    mockApi.listPrices.mockResolvedValue([]);
    mockApi.createPrice.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        title: 'Product missing',
        status: 404,
        code: 'PRODUCT_NOT_FOUND',
        detail: '供应商产品不存在。',
        requestId: 'req-price-1',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="price-create-open"]').trigger('click');
    await wrapper.find('[data-testid="price-create-model"] input').setValue('m');
    await wrapper.find('[data-testid="price-create-unit"] input').setValue('1');
    const productOption = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('Anthropic API'));
    await productOption!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="price-create-submit"]').trigger('click');
    await flushPromises();

    const error = wrapper.find('[data-testid="price-create-error"]');
    expect(error.text()).toContain('供应商产品不存在');
    expect(error.text()).toContain('req-price-1');
  });
});
