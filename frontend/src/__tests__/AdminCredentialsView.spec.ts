import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import TDesign from 'tdesign-vue-next';
import AdminCredentialsView from '@/views/AdminCredentialsView.vue';
import * as api from '@/api';
import type { CredentialDetailView, CredentialView, SubscriptionView } from '@/types/api';

vi.mock('@/api', () => ({
  listCredentials: vi.fn(),
  getCredential: vi.fn(),
  createCredential: vi.fn(),
  validateCredential: vi.fn(),
  rotateCredential: vi.fn(),
  disableCredential: vi.fn(),
  listSubscriptions: vi.fn(),
}));

const mockApi = vi.mocked(api);

/**
 * TDesign's TPopup teleports its panel behind a popper state machine that is
 * timing-fragile in jsdom (see KeysView.spec.ts); stub it to render trigger
 * and panel inline — dropdown positioning is not app logic.
 */
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

const credential = (overrides: Partial<CredentialView> = {}): CredentialView => ({
  id: '0190-cred-1',
  name: 'anthropic-main',
  subscriptionId: '0190-sub-1',
  status: 'ACTIVE',
  activeVersionId: '0190-ver-1',
  fingerprintPrefix: 'a1b2c3d4e5f6a7b8',
  lastValidatedAt: '2026-08-26T00:00:00Z',
  lastValidationError: null,
  version: 2,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  ...overrides,
});

const subscriptions: SubscriptionView[] = [
  {
    id: '0190-sub-1',
    providerProductId: '0190-product',
    productName: 'Anthropic API',
    name: 'Main',
    billingMode: 'PAYG',
    planScope: 'PERSONAL',
    status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z',
  },
];

const detail: CredentialDetailView = {
  credential: credential(),
  versions: [
    {
      id: '0190-ver-2',
      status: 'ACTIVE',
      encryptionKeyVersion: 'v1',
      fingerprintPrefix: 'a1b2c3d4e5f6a7b8',
      validFrom: '2026-08-20T00:00:00Z',
      retiredAt: null,
      createdAt: '2026-08-20T00:00:00Z',
    },
    {
      id: '0190-ver-1',
      status: 'DRAINING',
      encryptionKeyVersion: 'v1',
      fingerprintPrefix: '9f8e7d6c5b4a3921',
      validFrom: '2026-08-01T00:00:00Z',
      retiredAt: '2026-08-20T00:00:00Z',
      createdAt: '2026-08-01T00:00:00Z',
    },
  ],
};

function mountView() {
  return mount(AdminCredentialsView, {
    global: {
      plugins: [TDesign, createPinia()],
      stubs: { TPopup: PopupStub },
    },
  });
}

describe('AdminCredentialsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listCredentials.mockResolvedValue([credential()]);
    mockApi.listSubscriptions.mockResolvedValue(subscriptions);
    mockApi.getCredential.mockResolvedValue(detail);
  });

  it('renders masked metadata, never plaintext secrets', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="credentials-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('anthropic-main');
    expect(wrapper.text()).toContain('a1b2c3d4e5f6a7b8');
    expect(wrapper.text()).toContain('Anthropic API · Main');
    expect(wrapper.text()).toContain('Active');
    // The plaintext secret never appears anywhere.
    expect(wrapper.text()).not.toContain('sk-ant-');
  });

  it('shows an empty state when no credentials exist', async () => {
    mockApi.listCredentials.mockResolvedValue([]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有上游凭证');
  });

  it('creates a credential with the entered secret and reloads', async () => {
    mockApi.listCredentials.mockResolvedValue([]);
    mockApi.createCredential.mockResolvedValue(credential());

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-create-open"]').trigger('click');
    await wrapper.find('[data-testid="credential-create-name"] input').setValue('deepseek-main');
    await wrapper
      .find('[data-testid="credential-create-secret"] input')
      .setValue('sk-deepseek-123');
    // TDesign select: options render inside the stubbed popup inline.
    const option = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('Anthropic API'));
    expect(option, 'subscription option should exist').toBeTruthy();
    await option!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="credential-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createCredential).toHaveBeenCalledWith({
      name: 'deepseek-main',
      subscriptionId: '0190-sub-1',
      secret: 'sk-deepseek-123',
    });
    expect(mockApi.listCredentials).toHaveBeenCalledTimes(2);
  });

  it('surfaces backend create errors with requestId inline', async () => {
    mockApi.listCredentials.mockResolvedValue([]);
    mockApi.createCredential.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        title: 'Invalid secret',
        status: 400,
        code: 'CREDENTIAL_INVALID',
        detail: 'Secret 格式非法。',
        requestId: 'req-cred-1',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-create-open"]').trigger('click');
    await wrapper.find('[data-testid="credential-create-name"] input').setValue('bad');
    await wrapper.find('[data-testid="credential-create-secret"] input').setValue('x');
    const option = wrapper
      .findAll('.t-select-option')
      .find((o) => o.text().includes('Anthropic API'));
    await option!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="credential-create-submit"]').trigger('click');
    await flushPromises();

    const error = wrapper.find('[data-testid="credential-create-error"]');
    expect(error.text()).toContain('Secret 格式非法');
    expect(error.text()).toContain('req-cred-1');
  });

  it('validates a candidate secret and shows the match result', async () => {
    mockApi.validateCredential.mockResolvedValue({ matchesActive: true, message: null });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-validate"]').at(0)!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="credential-validate-secret"] input').setValue('sk-ant-test');
    await wrapper.find('[data-testid="credential-validate-run"]').trigger('click');
    await flushPromises();

    expect(mockApi.validateCredential).toHaveBeenCalledWith('0190-cred-1', {
      secret: 'sk-ant-test',
    });
    expect(wrapper.find('[data-testid="credential-validate-result"]').text()).toContain(
      '与当前生效版本一致',
    );
  });

  it('shows a mismatch message when the candidate differs', async () => {
    mockApi.validateCredential.mockResolvedValue({
      matchesActive: false,
      message: '与当前生效版本不一致。',
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-validate"]').at(0)!.trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="credential-validate-secret"] input').setValue('sk-ant-wrong');
    await wrapper.find('[data-testid="credential-validate-run"]').trigger('click');
    await flushPromises();

    const result = wrapper.find('[data-testid="credential-validate-result"]');
    expect(result.text()).toContain('与当前生效版本不一致');
    expect(result.classes()).toContain('t-alert--error');
  });

  it('rotates a credential with the new secret', async () => {
    mockApi.rotateCredential.mockResolvedValue(credential({ version: 3 }));

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-rotate"]').at(0)!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="credential-rotate-secret"] input').setValue('sk-ant-new');
    await wrapper.find('[data-testid="credential-rotate-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.rotateCredential).toHaveBeenCalledWith('0190-cred-1', {
      secret: 'sk-ant-new',
    });
    expect(mockApi.listCredentials).toHaveBeenCalledTimes(2);
  });

  it('disables a credential after confirming the real dialog', async () => {
    mockApi.disableCredential.mockResolvedValue({ message: 'Credential disabled' });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-disable"]').at(0)!.trigger('click');
    await flushPromises();

    // DialogPlugin mounts a real dialog into document.body; confirm it.
    const confirmButton = Array.from(document.querySelectorAll('.t-dialog__confirm')).find((b) =>
      b.textContent?.includes('禁用'),
    );
    expect(confirmButton, 'confirm dialog should render').toBeTruthy();
    (confirmButton as HTMLElement).click();
    await flushPromises();

    expect(mockApi.disableCredential).toHaveBeenCalledWith('0190-cred-1');
  });

  it('does not disable when the confirmation is cancelled', async () => {
    mockApi.disableCredential.mockResolvedValue({ message: 'Credential disabled' });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-disable"]').at(0)!.trigger('click');
    await flushPromises();

    const cancelButton = Array.from(document.querySelectorAll('.t-dialog__cancel')).find((b) =>
      b.textContent?.includes('取消'),
    );
    expect(cancelButton, 'confirm dialog should render').toBeTruthy();
    (cancelButton as HTMLElement).click();
    await flushPromises();

    expect(mockApi.disableCredential).not.toHaveBeenCalled();
  });

  it('lists version history in the drawer', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="credential-actions"]').trigger('click');
    await wrapper.findAll('[data-testid="credential-history"]').at(0)!.trigger('click');
    await flushPromises();

    expect(mockApi.getCredential).toHaveBeenCalledWith('0190-cred-1');
    // The drawer renders in place (no teleport); query the wrapper tree.
    const drawer = wrapper.find('[data-testid="credential-versions"]');
    expect(drawer.exists()).toBe(true);
    expect(drawer.text()).toContain('Active');
    expect(drawer.text()).toContain('Draining');
  });
});
