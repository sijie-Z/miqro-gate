import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextPricesView from '@/views/next/NextPricesView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {ProviderProductView} from '@/types/api';
import type { PriceSnapshotView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listPrices: vi.fn(),
  listProviderProducts: vi.fn(),
  createPrice: vi.fn(),
}));

const mockApi = vi.mocked(api);

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

const product: ProviderProductView = {
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
};

const price = (overrides: Partial<PriceSnapshotView> = {}): PriceSnapshotView => ({
  id: '0190-0000-0000-0040',
  providerProductId: product.id,
  modelId: 'deepseek-chat',
  tokenType: 'INPUT',
  currency: 'CNY',
  unitPrice: '2.0000',
  effectiveFrom: '2026-08-26T00:00:00Z',
  source: 'MANUAL',
  createdBy: 'root',
  createdAt: '2026-08-26T00:00:00Z',
  ...overrides,
});

describe('NextPricesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    mockApi.listPrices.mockResolvedValue([
      price(),
      price({ id: '0041', tokenType: 'OUTPUT', unitPrice: '16.0000', currency: 'USD' }),
    ]);
    mockApi.listProviderProducts.mockResolvedValue([product]);
  });

  function mountView() {
    return mount(NextPricesView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders price snapshots with resolved product names and prices', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="prices-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('DeepSeek PAYG');
    expect(wrapper.text()).toContain('deepseek-chat');
    expect(wrapper.text()).toContain('¥2.0000');
    expect(wrapper.text()).toContain('$16.0000');
    expect(wrapper.text()).toContain('人工录入');
  });

  it('creates a price snapshot and reloads', async () => {
    mockApi.createPrice.mockResolvedValue(price({ id: '0042' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listPrices as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="price-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('DeepSeek PAYG'))!
      .trigger('click');
    await flushPromises();
    const model = wrapper.find('[data-testid="price-create-model"]');
    await model.setValue('deepseek-chat');
    const unit = wrapper.find('[data-testid="price-create-unit"]');
    await unit.setValue('2.5');
    // 默认 INPUT/CNY/MANUAL 已选 — currency 切 USD
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text() === 'USD（$）')!
      .trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="price-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createPrice).toHaveBeenCalledWith({
      providerProductId: product.id,
      modelId: 'deepseek-chat',
      tokenType: 'INPUT',
      currency: 'USD',
      unitPrice: '2.5',
      source: 'MANUAL',
    });
    expect((mockApi.listPrices as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });

  it('blocks submission without a unit price', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="price-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('DeepSeek PAYG'))!
      .trigger('click');
    await wrapper.find('[data-testid="price-create-model"]').setValue('deepseek-chat');
    await flushPromises();

    expect(
      wrapper.find('[data-testid="price-create-submit"]').attributes('disabled'),
    ).toBeDefined();
    await wrapper.find('[data-testid="price-create-submit"]').trigger('click');
    await flushPromises();
    expect(mockApi.createPrice).not.toHaveBeenCalled();
  });
});
