import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import KeysView from '@/views/KeysView.vue';
import SecretRevealDialog from '@/components/SecretRevealDialog.vue';
import * as api from '@/api';
import type { CreateVirtualKeyResponse, MeGrantsResponse, VirtualKeyView } from '@/types/api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  myGrants: vi.fn(),
  createVirtualKey: vi.fn(),
  rotateVirtualKey: vi.fn(),
  revokeVirtualKey: vi.fn(),
}));

const mockApi = vi.mocked(api);

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

function mountView() {
  return mount(KeysView, {
    global: {
      plugins: [ElementPlus, createPinia()],
    },
  });
}

/**
 * Element Plus 2.9 selects the option on `click` (mousedown only prevents
 * focus stealing). Closed poppers may remain in document.body and stay
 * "visible" in jsdom, so options are matched by their label text instead of
 * visibility/position.
 */
function pickOptionByText(text: string): void {
  const items = Array.from(document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'));
  const target = items.find((el) => el.textContent?.includes(text));
  expect(target, `option containing "${text}" should exist`).toBeTruthy();
  target.dispatchEvent(new MouseEvent('click', { bubbles: true }));
}

describe('KeysView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    // resetAllMocks clears implementations too — a previous test's
    // mockResolvedValue must not leak into the next test.
    vi.resetAllMocks();
    // Closed el-select poppers stay teleported in <body>; drop leftovers so
    // option lookups only see the current test's dropdown.
    document.body.innerHTML = '';
    // jsdom has no clipboard
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('renders masked keys, never the plaintext', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([key()]);
    mockApi.myGrants.mockResolvedValue(grants);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="keys-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('mqk_live_…8f2a');
    expect(wrapper.text()).not.toContain('mqk_live_abcdefghijklmnopqrstuv');
    expect(wrapper.text()).toContain('core-ai');
  });

  it('shows an empty state when no keys exist', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([]);
    mockApi.myGrants.mockResolvedValue(grants);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有 Virtual Key');
  });

  it('creates a key and reveals the secret once, requiring acknowledgement', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([]);
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('claude-code-main');
    await wrapper.find('[data-testid="create-project"] .el-select__wrapper').trigger('click');
    await flushPromises();
    pickOptionByText('Core AI'); // project p1
    await flushPromises();

    // Grant selection defaults models to the full grant set.
    await wrapper.find('[data-testid="create-grant"] .el-select__wrapper').trigger('click');
    await flushPromises();
    pickOptionByText('0190-product'); // grant g1
    await flushPromises();

    const submitBtn = wrapper.find('[data-testid="create-submit"]');
    await submitBtn.trigger('click');
    await flushPromises();

    expect(mockApi.createVirtualKey).toHaveBeenCalledWith({
      name: 'claude-code-main',
      projectId: 'p1',
      providerProductId: '0190-product',
      credentialGrantId: 'g1',
      purpose: 'CLAUDE_CODE',
      allowedModels: ['claude-3-7-sonnet', 'claude-3-5-haiku'],
    });

    // Secret dialog: plaintext visible, close disabled until acknowledged.
    expect(wrapper.find('[data-testid="secret-value"]').text()).toBe('mqk_live_newkey');
    const closeButton = wrapper.find('[data-testid="secret-close"]');
    expect(closeButton.attributes('disabled')).toBeDefined();

    // el-checkbox toggles on the native input change; jsdom label clicks do
    // not forward to the input.
    const ackInput = wrapper.find('[data-testid="secret-ack"] input');
    await ackInput.setValue(true);
    await flushPromises();
    expect(closeButton.attributes('disabled')).toBeUndefined();

    await closeButton.trigger('click');
    // el-dialog keeps its DOM after closing (destroy-on-close=false); the
    // visible signal is the modelValue prop.
    await flushPromises();
    expect(wrapper.findComponent(SecretRevealDialog).props('modelValue')).toBe(false);
    expect(wrapper.findComponent(SecretRevealDialog).props('secret')).toBe(created.secret);
  });

  it('surfaces backend errors with requestId in the create form', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([]);
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockRejectedValue(
      new (await import('@/api/http')).ApiError({
        type: 'about:blank',
        title: 'Model not granted',
        status: 400,
        code: 'MODEL_NOT_GRANTED',
        detail: 'One or more models are not granted.',
        requestId: 'req-123',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('bad-key');
    await wrapper.find('[data-testid="create-project"] .el-select__wrapper').trigger('click');
    await flushPromises();
    pickOptionByText('Core AI');
    await flushPromises();
    await wrapper.find('[data-testid="create-grant"] .el-select__wrapper').trigger('click');
    await flushPromises();
    pickOptionByText('0190-product');
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="create-error"]').text()).toContain(
      'One or more models are not granted',
    );
    expect(wrapper.find('[data-testid="create-error"]').text()).toContain('req-123');
  });
});
