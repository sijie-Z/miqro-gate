import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { computed, defineComponent, h } from 'vue';
import NextCredentialsView from '@/views/next/NextCredentialsView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { CredentialView, SubscriptionView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  listCredentials: vi.fn(),
  listSubscriptions: vi.fn(),
  listGrants: vi.fn(),
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

/** RouterLink stub that publishes its target so tests can assert jump targets. */
const RouterLinkStub = defineComponent({
  name: 'RouterLink',
  inheritAttrs: false,
  props: { to: { type: [String, Object], default: '' } },
  setup(props, { slots, attrs }) {
    const target = computed(() =>
      typeof props.to === 'string' ? props.to : JSON.stringify(props.to),
    );
    return () => h('a', { ...attrs, 'data-router-to': target.value }, slots.default?.());
  },
});

const subscription: SubscriptionView = {
  id: '0190-0000-0000-0021',
  providerProductId: '0190-0000-0000-0020',
  productName: 'DeepSeek PAYG',
  name: 'Main',
  billingMode: 'PAYG',
  planScope: 'PERSONAL',
  subscriptionPrice: null as unknown as number,
  currency: 'USD',
  quotaTotal: null as unknown as number,
  quotaUnit: null as unknown as string,
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
  lastValidatedAt: null as unknown as string,
  lastValidationError: null as unknown as string,
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
        lastValidationError: null as unknown as string,
      }),
    ]);
    mockApi.listSubscriptions.mockResolvedValue([subscription]);
    mockApi.listGrants.mockResolvedValue([
      {
        id: 'g1',
        projectId: 'p1',
        providerProductId: subscription.providerProductId,
        upstreamCredentialId: credential().id,
        status: 'ACTIVE',
      },
      {
        id: 'g2',
        projectId: 'p2',
        providerProductId: subscription.providerProductId,
        upstreamCredentialId: credential().id,
        status: 'ACTIVE',
      },
    ]);
  });

  function mountView() {
    return mount(NextCredentialsView, {
      global: {
        plugins: [createPinia()],
        stubs: { UiSelect: SelectStub, RouterLink: RouterLinkStub },
      },
    });
  }

  it('renders credentials with masked fingerprints and Chinese statuses', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="credentials-table"]').exists()).toBe(true);
    // #657 dependency column: the first credential is referenced by two grants.
    expect(wrapper.text()).toContain('授权引用');
    const firstRow = wrapper.findAll('[data-testid="credential-grant-count"]');
    expect(firstRow.length).toBeGreaterThan(0);
    expect(firstRow[0]!.text()).toBe('2');
    expect(wrapper.find('[data-testid="page-guide"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('三步用起来');
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

  it('deep-links a referenced credential to its grants and keeps 0 plain (#657)', async () => {
    const wrapper = mountView();
    await flushPromises();

    const counts = wrapper.findAll('[data-testid="credential-grant-count"]');
    expect(counts.length).toBe(2);

    // Referenced twice → the count is the entry point into the filtered grants list.
    expect(counts[0]!.element.tagName).toBe('A');
    expect(counts[0]!.text()).toBe('2');
    expect(JSON.parse(counts[0]!.attributes('data-router-to') as string)).toEqual({
      name: 'grants',
      query: { credentialId: '0190-0000-0000-0030' },
    });

    // Nothing to look at → a 0 must not look like a door.
    expect(counts[1]!.element.tagName).toBe('SPAN');
    expect(counts[1]!.text()).toBe('0');
  });

  it('shows the history load failure instead of asserting "没有版本记录" (#1231)', async () => {
    mockApi.getCredential.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: '版本历史读取失败。',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: '版本历史读取失败。',
        requestId: 'req-hist-500',
      }),
    );
    const wrapper = mountView();
    await flushPromises();

    // kebab → 版本历史, the same radix menu flow as UiDrawer.spec.ts.
    const kebab = wrapper.find('[data-testid="credential-actions-0190-0000-0000-0030"]')
      .element as HTMLElement;
    kebab.click();
    await flushPromises();
    const historyItem = document.body.querySelector<HTMLElement>(
      '[data-testid="credential-history"]',
    );
    const menuItem = historyItem?.closest('[role="menuitem"]') as HTMLElement | null;
    expect(menuItem, 'menu item should render').toBeTruthy();
    menuItem!.focus();
    menuItem!.click();
    await flushPromises();

    const drawer = document.querySelector('[data-testid="credential-history-drawer"]');
    expect(drawer, 'history drawer should render').toBeTruthy();
    // A failed read must not be painted as "there are no versions"…
    expect(drawer!.textContent).not.toContain('没有版本记录');
    // …the table shows the failure with a retry instead.
    expect(drawer!.querySelector('[data-testid="table-load-failed"]')).toBeTruthy();
    expect(drawer!.querySelector('[data-testid="table-load-retry"]')).toBeTruthy();
  });
});
