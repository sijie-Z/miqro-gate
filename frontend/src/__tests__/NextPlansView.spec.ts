import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextPlansView from '@/views/next/NextPlansView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type {ProviderProductView} from '@/types/api';
import type { SubscriptionView, SeatView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listSubscriptions: vi.fn(),
  listProviderProducts: vi.fn(),
  createSubscription: vi.fn(),
  listSeats: vi.fn(),
  createSeat: vi.fn(),
  updateSeat: vi.fn(),
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
  id: '0190-0000-0000-0021',
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

const subscription = (overrides: Partial<SubscriptionView> = {}): SubscriptionView => ({
  id: '0190-0000-0000-0031',
  providerProductId: product.id,
  productName: 'DeepSeek PAYG',
  name: 'Main',
  billingMode: 'FIXED_SUBSCRIPTION',
  planScope: 'TEAM',
  subscriptionPrice: 100,
  currency: 'USD',
  quotaTotal: 5_000_000,
  quotaUnit: 'TOKENS',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const seat = (overrides: Partial<SeatView> = {}): SeatView => ({
  id: '0190-0000-0000-0041',
  subscriptionId: subscription().id,
  assignedUserId: 'u1',
  username: 'alice',
  displayName: 'Alice',
  seatStatus: 'ASSIGNED',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

describe('NextPlansView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    document.body.innerHTML = '';
    mockApi.listSubscriptions.mockResolvedValue([
      subscription(),
      subscription({ id: 's2', name: 'Staging', status: 'DISABLED', quotaTotal: null }),
    ]);
    mockApi.listProviderProducts.mockResolvedValue([product]);
    mockApi.listSeats.mockResolvedValue([seat()]);
  });

  function mountView() {
    return mount(NextPlansView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders subscriptions with plan labels, quotas and statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="subscriptions-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('DeepSeek PAYG');
    expect(wrapper.text()).toContain('团队 Plan');
    expect(wrapper.text()).toContain('100 USD');
    expect(wrapper.text()).toContain('5 小时');
    expect(wrapper.text()).toContain('34%');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('停用');
    expect(wrapper.text()).toContain('未配置配额');
  });

  it('creates a subscription and reloads', async () => {
    mockApi.createSubscription.mockResolvedValue(subscription({ id: 's9' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listSubscriptions as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="subscription-create-open"]').trigger('click');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('DeepSeek PAYG'))!
      .trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="subscription-create-name"]').setValue('Coding Plan');
    await wrapper.find('[data-testid="subscription-create-price"]').setValue('200');
    await wrapper.find('[data-testid="subscription-create-quota"]').setValue('1000000');
    await flushPromises();
    await wrapper.find('[data-testid="subscription-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createSubscription).toHaveBeenCalledWith({
      providerProductId: product.id,
      name: 'Coding Plan',
      billingMode: 'FIXED_SUBSCRIPTION',
      planScope: 'PERSONAL',
      subscriptionPrice: 200,
      currency: 'USD',
      quotaTotal: 1000000,
      quotaUnit: undefined,
    });
    expect(
      (mockApi.listSubscriptions as ReturnType<typeof vi.fn>).mock.calls.length,
    ).toBeGreaterThan(callsBefore);
  });

  it('assigns and releases seats through the drawer and gate', async () => {
    mockApi.createSeat.mockResolvedValue({} as never);
    mockApi.updateSeat.mockResolvedValue({} as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="subscription-seats-open"]').trigger('click');
    await flushPromises();
    expect(document.querySelector('[data-testid="seats-drawer"]')).toBeTruthy();
    expect(document.body.textContent).toContain('alice');

    // Assign (drawer teleports to body — drive the real DOM)
    const assignInput = document.querySelector(
      '[data-testid="seat-assign-user"]',
    ) as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(assignInput, 'bob');
    assignInput.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    (document.querySelector('[data-testid="seat-create"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(mockApi.createSeat).toHaveBeenCalledWith('0190-0000-0000-0031', {
      displayName: undefined,
      assignedUserId: 'bob',
    });

    // Release with confirmation gate
    (document.querySelector('[data-testid="seat-release"]') as HTMLButtonElement).click();
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '释放' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();
    expect(mockApi.updateSeat).toHaveBeenCalledWith('0190-0000-0000-0031', '0190-0000-0000-0041', {
      status: 'AVAILABLE',
    });
  });
});
