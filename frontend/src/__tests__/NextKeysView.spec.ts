import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextKeysView from '@/views/next/NextKeysView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { CreateVirtualKeyResponse, MeGrantsResponse, VirtualKeyView } from '@/types/api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  myGrants: vi.fn(),
  createVirtualKey: vi.fn(),
  rotateVirtualKey: vi.fn(),
  revokeVirtualKey: vi.fn(),
}));

const mockApi = vi.mocked(api);

/**
 * UiSelect is built on radix-vue's Select, whose pointer-driven popup state
 * machine is not deterministic in jsdom (same reasoning as the legacy TPopup
 * stub). The stub renders one option button per option; change semantics stay
 * user-like (click an option → page cascade handlers run).
 */
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
    return {
      pick,
      props,
    };
  },
  template: `
    <div class="ui-select-stub">
      <label v-if="props.label">{{ props.label }}</label>
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        :class="{ selected: props.modelValue === opt.value }"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>
  `,
});

const key = (overrides: Partial<VirtualKeyView> = {}): VirtualKeyView => ({
  id: '0190-0001',
  name: 'claude-code-main',
  purpose: 'CLAUDE_CODE',
  status: 'ACTIVE',
  displayPrefix: 'mqk_live_abcdefghijklmnopqrstuv',
  lastFour: '8f2a',
  display: 'mqk_live_…8f2a',
  modelIds: ['claude-3-7-sonnet'],
  projectId: 'p1',
  projectTag: 'core-ai',
  cachePolicy: 'DISABLED',
  baseUrl: 'https://gateway.test.internal',
  createdAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

const grants: MeGrantsResponse = {
  projects: [{ id: 'p1', code: 'P1', name: 'Core AI', projectTag: 'core-ai' }],
  grants: [
    {
      id: 'g1',
      projectId: 'p1',
      providerProductId: '0190-product',
      models: ['claude-3-7-sonnet', 'claude-3-5-haiku'],
    },
  ],
  purposes: ['CLAUDE_CODE', 'CLAUDE_DESKTOP', 'CODEX', 'CUSTOM'],
};

const created: CreateVirtualKeyResponse = {
  id: '0190-0002',
  secret: 'mqk_live_newkey',
  baseUrl: 'https://gateway.test.internal',
  display: 'mqk_live_…0002',
  shownOnce: true,
  createdAt: '2026-08-25T00:00:00Z',
  version: 1,
};

describe('NextKeysView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listVirtualKeys.mockResolvedValue([]);
    document.body.innerHTML = '';
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  function mountView() {
    return mount(NextKeysView, {
      global: {
        plugins: [createPinia()],
        stubs: { UiSelect: SelectStub },
      },
    });
  }

  it('renders masked keys with Chinese statuses, never the plaintext', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([
      key(),
      key({ id: '0190-0009', name: 'codex-extra', status: 'REVOKED', cachePolicy: 'ENABLED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="keys-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('mqk_live_…8f2a');
    expect(wrapper.text()).not.toContain('mqk_live_abcdefghijklmnopqrstuv');
    expect(wrapper.text()).toContain('可用');
    expect(wrapper.text()).toContain('已吊销');
    expect(wrapper.text()).toContain('开启');
    expect(wrapper.find('[data-testid="keys-summary"]').text()).toContain('共 2 个');
    expect(wrapper.text()).toContain('core-ai');
  });

  it('shows the admin-contact onboarding when the account is in no project', async () => {
    mockApi.myGrants.mockResolvedValue({ projects: [], grants: [], purposes: [] });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="onboard-no-project"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('等待管理员开通');
  });

  it('keeps the plain empty invite once the account has a project', async () => {
    mockApi.myGrants.mockResolvedValue(grants);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="onboard-no-project"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="onboard-has-project"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('还没有 Virtual Key');
  });

  it('creates a key through the cascade and reveals the secret once (ack required)', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('claude-code-main');
    await flushPromises();

    // Project option button (stub) — Core AI（core-ai）
    const projectButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Core AI'));
    expect(projectButton).toBeTruthy();
    await projectButton!.trigger('click');
    await flushPromises();

    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('0190-product'));
    expect(grantButton).toBeTruthy();
    await grantButton!.trigger('click');
    await flushPromises();

    const submitBtn = wrapper.find('[data-testid="create-submit"]');
    // Cascade complete (name + project + grant + models defaulted) — enabled.
    expect(submitBtn.attributes('disabled')).toBeUndefined();
    // Models default to the full grant set on grant change.
    expect(wrapper.findAll('.next-keys__model--on').length).toBeGreaterThan(0);
    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createVirtualKey).toHaveBeenCalledWith({
      name: 'claude-code-main',
      projectId: 'p1',
      providerProductId: '0190-product',
      credentialGrantId: 'g1',
      purpose: 'CLAUDE_CODE',
      allowedModels: ['claude-3-7-sonnet', 'claude-3-5-haiku'],
      cachePolicy: 'DISABLED',
    });

    // Secret dialog teleports to body — query the real DOM.
    expect(document.body.textContent).toContain('mqk_live_newkey');
    const closeButton = document.querySelector('[data-testid="secret-close"]') as HTMLButtonElement;
    expect(closeButton).toBeTruthy();
    expect(closeButton.disabled).toBe(true);

    const ack = document.querySelector('[data-testid="secret-ack"]') as HTMLInputElement;
    ack.click();
    await flushPromises();
    expect(closeButton.disabled).toBe(false);
  });

  it('surfaces API errors with request ids in the create form', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 400,
        code: 'MODEL_NOT_GRANTED',
        detail: 'One or more models are not granted',
        requestId: 'req-123',
        title: 'Bad Request',
      }),
    );

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('bad-key');
    await flushPromises();
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Core AI'))!
      .trigger('click');
    await flushPromises();
    await wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('0190-product'))!
      .trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="create-error"]').text()).toContain('models are not granted');
    expect(wrapper.find('[data-testid="create-error"]').text()).toContain('req-123');
  });
});
