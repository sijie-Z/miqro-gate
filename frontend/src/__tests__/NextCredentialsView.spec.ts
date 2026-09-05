import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextCredentialsView from '@/views/next/NextCredentialsView.vue';
import * as api from '@/api';
import type {SubscriptionView} from '@/types/api';
import type { CredentialView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listCredentials: vi.fn(),
  listSubscriptions: vi.fn(),
  createCredential: vi.fn(),
  validateCredential: vi.fn(),
  rotateCredential: vi.fn(),
  getCredential: vi.fn(),
  disableCredential: vi.fn(),
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

const subscription: SubscriptionView = {
  id: '0190-0000-0000-0021',
  providerProductId: '0190-0000-0000-0020',
  productName: 'DeepSeek PAYG',
  name: 'Main',
  billingMode: 'PAYG',
  planScope: 'PERSONAL',
  subscriptionPrice: null,
  currency: 'USD',
  quotaTotal: null,
  quotaUnit: null,
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
};

const credential = (overrides: Partial<CredentialView> = {}): CredentialView => ({
  id: '0190-0000-0000-0030',
  name: 'deepseek-main',
  subscriptionId: subscription.id,
  status: 'ACTIVE',
  activeVersionId: '0190-0000-0000-0031',
  fingerprintPrefix: 'sk-a1b2c3d4e5f6',
  lastValidatedAt: null,
  lastValidationError: null,
  version: 2,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  ...overrides,
});

describe('NextCredentialsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listCredentials.mockResolvedValue([
      credential(),
      credential({
        id: 'c2',
        name: 'moonshot-main',
        status: 'DRAINING',
        lastValidatedAt: '2026-08-26T00:00:00Z',
        lastValidationError: null,
      }),
    ]);
    mockApi.listSubscriptions.mockResolvedValue([subscription]);
  });

  function mountView() {
    return mount(NextCredentialsView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders credentials with masked fingerprints and Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="credentials-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('deepseek-main');
    expect(wrapper.text()).toContain('sk-a1b2c3d4e5f6');
    expect(wrapper.text()).toContain('DeepSeek PAYG · Main');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('宽限期');
    expect(wrapper.text()).toContain('从未验证');
    expect(wrapper.text()).toContain('v2');
  });

  it('creates a credential with the secret and reloads', async () => {
    mockApi.createCredential.mockResolvedValue(credential({ id: 'c9' }));
    const wrapper = mountView();
    await flushPromises();
    const callsBefore = (mockApi.listCredentials as ReturnType<typeof vi.fn>).mock.calls.length;

    await wrapper.find('[data-testid="credential-create-open"]').trigger('click');
    await wrapper.find('[data-testid="credential-create-name"]').setValue('glm-main');
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('DeepSeek PAYG'))!
      .trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="credential-create-secret"]').setValue('sk-live-secret');
    await wrapper.find('[data-testid="credential-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createCredential).toHaveBeenCalledWith({
      name: 'glm-main',
      subscriptionId: subscription.id,
      secret: 'sk-live-secret',
    });
    expect((mockApi.listCredentials as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
      callsBefore,
    );
  });

  it('toggles secret visibility in the create form and gates empty submissions', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-create-open"]').trigger('click');
    const secretInput = wrapper.find('[data-testid="credential-create-secret"]');
    expect(secretInput.attributes('type')).toBe('password');
    await wrapper.find('[data-testid="credential-create-secret-toggle"]').trigger('click');
    await flushPromises();
    expect(secretInput.attributes('type')).toBe('text');

    // Without name/subscription/secret the submit stays disabled.
    expect(
      wrapper.find('[data-testid="credential-create-submit"]').attributes('disabled'),
    ).toBeDefined();
    await wrapper.find('[data-testid="credential-create-submit"]').trigger('click');
    await flushPromises();
    expect(mockApi.createCredential).not.toHaveBeenCalled();
  });
});
