import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { computed, defineComponent, h } from 'vue';
import NextCredentialsView from '@/views/next/NextCredentialsView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type {
  CredentialView,
  SubscriptionView,
  ValidateCredentialResponse,
} from '@/types/generated-api';

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

  /** Opens a row's kebab menu and clicks 测试密钥 — the same radix flow as the #1231 test. */
  async function openValidateDialog(
    wrapper: ReturnType<typeof mountView>,
    credentialId: string,
  ): Promise<void> {
    const kebab = wrapper.find(`[data-testid="credential-actions-${credentialId}"]`)
      .element as HTMLElement;
    kebab.click();
    await flushPromises();
    // Take the newest match: a menu opened earlier in the same test can still be in the DOM.
    const items = document.body.querySelectorAll<HTMLElement>(
      '[data-testid="credential-validate"]',
    );
    const item = items[items.length - 1]?.closest('[role="menuitem"]') as HTMLElement | null;
    expect(item, 'validate menu item should render').toBeTruthy();
    item!.focus();
    item!.click();
    await flushPromises();
  }

  /**
   * The validate dialog is portalled into document.body (UiDialog → radix
   * DialogPortal), so wrapper.find cannot see it. Address it through the
   * document the way the #1231 drawer test does.
   */
  function validateDialog(): HTMLElement | null {
    const anchor = document.body.querySelector('[data-testid="credential-validate-secret"]');
    // `anchor?.closest()` yields undefined when the dialog is gone; normalise to null
    // so `toBeNull()` asserts "closed" rather than tripping over undefined.
    return (anchor?.closest('.ui-dialog__content') as HTMLElement | null | undefined) ?? null;
  }

  function validateRunButton(): HTMLButtonElement {
    const button = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="credential-validate-run"]',
    );
    expect(button, '测试 button should render').toBeTruthy();
    return button!;
  }

  /** Opens a row's kebab menu and clicks 轮换 — the same radix flow as the #1231 test. */
  async function openRotateDialog(
    wrapper: ReturnType<typeof mountView>,
    credentialId: string,
  ): Promise<void> {
    const kebab = wrapper.find(`[data-testid="credential-actions-${credentialId}"]`)
      .element as HTMLElement;
    kebab.click();
    await flushPromises();
    // Take the newest match: a menu opened earlier in the same test can still be in the DOM.
    const items = document.body.querySelectorAll<HTMLElement>('[data-testid="credential-rotate"]');
    const item = items[items.length - 1]?.closest('[role="menuitem"]') as HTMLElement | null;
    expect(item, 'rotate menu item should render').toBeTruthy();
    item!.focus();
    item!.click();
    await flushPromises();
  }

  /**
   * The rotate dialog is portalled into document.body (UiDialog → radix
   * DialogPortal), so wrapper.find cannot see it. Address it through the
   * document the way the #1231 drawer test does.
   */
  function rotateDialog(): HTMLElement | null {
    const anchor = document.body.querySelector('[data-testid="credential-rotate-secret"]');
    // `anchor?.closest()` yields undefined when the dialog is gone; normalise to null
    // so `toBeNull()` asserts "closed" rather than tripping over undefined.
    return (anchor?.closest('.ui-dialog__content') as HTMLElement | null | undefined) ?? null;
  }

  function rotateSubmitButton(): HTMLButtonElement {
    const button = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="credential-rotate-submit"]',
    );
    expect(button, '轮换 button should render').toBeTruthy();
    return button!;
  }

  function rotateSecretInput(): HTMLInputElement {
    const input = document.body.querySelector<HTMLInputElement>(
      '[data-testid="credential-rotate-secret"]',
    );
    expect(input, 'secret input should render').toBeTruthy();
    return input!;
  }

  function rotateErrorBox(): HTMLElement | null {
    return document.body.querySelector<HTMLElement>('[data-testid="credential-rotate-error"]');
  }

  function rotateCancelButton(): HTMLButtonElement {
    const cancel = [...(rotateDialog()?.querySelectorAll('button') ?? [])].find(
      (b) => b.textContent?.trim() === '取消',
    );
    expect(cancel, '取消 button should render').toBeTruthy();
    return cancel!;
  }

  function setRotateSecret(value: string): void {
    const input = rotateSecretInput();
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
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

  it('#PH69R2B: 放弃一次验证后再打开弹窗，测试按钮不会永久转圈禁用', async () => {
    // Hold the validation open so the admin can walk away from it.
    let release!: (value: ValidateCredentialResponse) => void;
    mockApi.validateCredential.mockImplementation(
      () =>
        new Promise<ValidateCredentialResponse>((resolve) => {
          release = resolve;
        }),
    );
    const wrapper = mountView();
    await flushPromises();

    await openValidateDialog(wrapper, '0190-0000-0000-0030');
    const secret = document.body.querySelector<HTMLInputElement>(
      '[data-testid="credential-validate-secret"]',
    );
    expect(secret, 'secret input should render').toBeTruthy();
    secret!.value = 'sk-candidate';
    secret!.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    validateRunButton().click();
    await flushPromises();

    // In flight the button is meant to be busy — this part is correct.
    expect(validateRunButton().disabled).toBe(true);

    // The admin gives up on it: closes the dialog and goes to look at another credential.
    const close = [...(validateDialog()?.querySelectorAll('button') ?? [])].find(
      (b) => b.textContent?.trim() === '关闭',
    );
    expect(close, '关闭 button should render').toBeTruthy();
    close!.click();
    await flushPromises();
    expect(validateDialog(), 'dialog should be gone after 关闭').toBeNull();
    await openValidateDialog(wrapper, 'c2');
    // The dialog on screen belongs to the other credential now.
    expect(document.body.textContent).toContain(
      '测试候选密钥是否与「moonshot-main」当前生效版本一致。',
    );

    // The abandoned answer finally lands — it was never this dialog's business.
    release({ matchesActive: true, providerStatus: 'NOT_CHECKED' } as ValidateCredentialResponse);
    await flushPromises();

    // This dialog never had a request in flight, so its 测试 button must be usable.
    expect(validateRunButton().disabled).toBe(false);
    expect(validateRunButton().getAttribute('aria-busy')).toBeNull();
  });

  it('#PH69R2B: 放弃一次轮换后再打开别的凭据，迟到的失败不会写进新弹窗', async () => {
    // Hold credential A's rotation open, so the admin can walk away from it.
    let failRotate!: (reason: unknown) => void;
    mockApi.rotateCredential.mockImplementation(
      () =>
        new Promise<CredentialView>((_resolve, reject) => {
          failRotate = reject;
        }),
    );
    const wrapper = mountView();
    await flushPromises();

    await openRotateDialog(wrapper, '0190-0000-0000-0030');
    setRotateSecret('sk-rotated-a');
    rotateSubmitButton().click();
    await flushPromises();
    expect(mockApi.rotateCredential).toHaveBeenCalledTimes(1);

    // The admin changes their mind and moves on to another credential.
    rotateCancelButton().click();
    await flushPromises();
    expect(rotateDialog(), 'dialog should be gone after 取消').toBeNull();

    await openRotateDialog(wrapper, 'c2');
    // Precondition: this is B's dialog, and nothing has failed here yet.
    expect(document.body.textContent).toContain('为「moonshot-main」提供新的密钥');
    expect(rotateErrorBox()).toBeNull();

    // A's failure finally lands — it was never this dialog's business.
    failRotate(
      new ApiError({
        type: 'about:blank',
        title: '轮换失败。',
        status: 400,
        code: 'INVALID_REQUEST',
        detail: '轮换失败。',
        requestId: 'req-rotate-a',
      }),
    );
    await flushPromises();

    // B's dialog never sent a request, so it must not report A's failure.
    expect(rotateErrorBox(), 'A 的失败不该显示在 B 的轮换弹窗里').toBeNull();
    expect(document.body.textContent).not.toContain('req-rotate-a');
  });

  it('#PH69R2B: 放弃一次轮换后再打开别的凭据，迟到的成功不会关掉新弹窗', async () => {
    // Same walk-away, but A's rotation eventually succeeds.
    let succeedRotate!: () => void;
    mockApi.rotateCredential.mockImplementation(
      () =>
        new Promise<CredentialView>((resolve) => {
          succeedRotate = () => resolve(credential({ id: '0190-0000-0000-0030' }));
        }),
    );
    const wrapper = mountView();
    await flushPromises();

    await openRotateDialog(wrapper, '0190-0000-0000-0030');
    setRotateSecret('sk-rotated-a');
    rotateSubmitButton().click();
    await flushPromises();

    rotateCancelButton().click();
    await flushPromises();
    expect(rotateDialog()).toBeNull();

    await openRotateDialog(wrapper, 'c2');
    setRotateSecret('sk-typing-b');
    await flushPromises();

    succeedRotate();
    await flushPromises();

    // B's dialog is the admin's live work: A's success must not close it or wipe its input.
    expect(rotateDialog(), 'B 的弹窗不该被 A 的迟到成功关掉').not.toBeNull();
    expect(rotateSecretInput().value).toBe('sk-typing-b');
  });

  it('#PH69R2B: 放弃一次轮换后再打开别的凭据，轮换按钮不会带着上一次的 busy 出现', async () => {
    // A's rotation never settles — the admin simply walked away from it.
    mockApi.rotateCredential.mockImplementation(() => new Promise<CredentialView>(() => {}));
    const wrapper = mountView();
    await flushPromises();

    await openRotateDialog(wrapper, '0190-0000-0000-0030');
    setRotateSecret('sk-rotated-a');
    rotateSubmitButton().click();
    await flushPromises();
    expect(rotateSubmitButton().disabled).toBe(true); // busy is right — A is in flight

    rotateCancelButton().click();
    await flushPromises();
    await openRotateDialog(wrapper, 'c2');

    // This dialog has no request in flight: its 轮换 button must be usable.
    expect(rotateSubmitButton().disabled).toBe(false);
    expect(rotateSubmitButton().getAttribute('aria-busy')).toBeNull();
  });
});
