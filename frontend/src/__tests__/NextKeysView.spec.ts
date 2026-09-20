import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextKeysView from '@/views/next/NextKeysView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type {
  CreateVirtualKeyResponse,
  MeGrantsResponse,
  VirtualKeyView,
} from '@/types/generated-api';

vi.mock('@/api', () => ({
  listVirtualKeys: vi.fn(),
  myGrants: vi.fn(),
  createVirtualKey: vi.fn(),
  rotateVirtualKey: vi.fn(),
  revokeVirtualKey: vi.fn(),
  renameVirtualKey: vi.fn(),
  disableVirtualKey: vi.fn(),
  enableVirtualKey: vi.fn(),
  usageSummary: vi.fn(),
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
  projects: [
    { id: 'p1', code: 'P1', name: 'Core AI', projectTag: 'core-ai' },
    { id: 'p2', code: 'P2', name: 'QA Team', projectTag: 'qa-team' },
  ],
  grants: [
    {
      id: 'g1',
      projectId: 'p1',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet', 'claude-3-5-haiku'],
    },
    {
      id: 'g2',
      projectId: 'p2',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet', 'claude-3-5-haiku'],
    },
  ],
  purposes: ['CLAUDE_CODE', 'CLAUDE_DESKTOP', 'CODEX', 'CUSTOM'],
};

/**
 * #1157: p3's only grant is for a **different** provider product, so no key whose
 * product is `claude-api` can bind it — the server matches each extra to that
 * project's own grant of the same product (409 PROJECT_GRANT_MISSING).
 */
const grantsWithForeignProduct: MeGrantsResponse = {
  ...grants,
  projects: [
    ...(grants.projects ?? []),
    { id: 'p3', code: 'P3', name: 'Other Vendor', projectTag: 'other' },
  ],
  grants: [
    ...(grants.grants ?? []),
    {
      id: 'g3',
      projectId: 'p3',
      providerProductId: '0190-other-product',
      providerProductCode: 'openai-api',
      providerProductName: 'OpenAI API',
      models: ['gpt-4o-mini'],
    },
  ],
};

/**
 * #1157: p1 and p2 each hold grants for **two** products, p3 only for the first.
 * Enough to check that the bound set follows the *selected* product, and that an
 * explicit uncheck is not resurrected when that product changes.
 */
const grantsAcrossProducts: MeGrantsResponse = {
  projects: [
    { id: 'p1', code: 'P1', name: 'Core AI', projectTag: 'core-ai' },
    { id: 'p2', code: 'P2', name: 'QA Team', projectTag: 'qa-team' },
    { id: 'p3', code: 'P3', name: 'Agent Lab', projectTag: 'agent-lab' },
  ],
  grants: [
    {
      id: 'g1',
      projectId: 'p1',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet'],
    },
    {
      id: 'g1b',
      projectId: 'p1',
      providerProductId: '0190-other-product',
      providerProductCode: 'openai-api',
      providerProductName: 'OpenAI API',
      models: ['gpt-4o-mini'],
    },
    {
      id: 'g2',
      projectId: 'p2',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet'],
    },
    {
      id: 'g2b',
      projectId: 'p2',
      providerProductId: '0190-other-product',
      providerProductCode: 'openai-api',
      providerProductName: 'OpenAI API',
      models: ['gpt-4o-mini'],
    },
    {
      id: 'g3',
      projectId: 'p3',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet'],
    },
  ],
  purposes: ['CLAUDE_CODE'],
};

/**
 * #1157: disjoint products — neither project is bindable while the other is primary.
 * This is the shape that used to leave a stale id checked after bouncing the primary.
 */
const grantsDisjointProducts: MeGrantsResponse = {
  projects: [
    { id: 'p1', code: 'P1', name: 'Core AI', projectTag: 'core-ai' },
    { id: 'p2', code: 'P2', name: 'QA Team', projectTag: 'qa-team' },
  ],
  grants: [
    {
      id: 'g1',
      projectId: 'p1',
      providerProductId: '0190-product',
      providerProductCode: 'claude-api',
      providerProductName: 'Claude API',
      models: ['claude-3-7-sonnet'],
    },
    {
      id: 'g2',
      projectId: 'p2',
      providerProductId: '0190-other-product',
      providerProductCode: 'openai-api',
      providerProductName: 'OpenAI API',
      models: ['gpt-4o-mini'],
    },
  ],
  purposes: ['CLAUDE_CODE'],
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
    mockApi.usageSummary.mockResolvedValue({ groupBy: 'virtual_key', groups: [], totals: {} });
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
      key({
        id: '0190-0009',
        name: 'codex-extra',
        status: 'REVOKED',
        cachePolicy: 'ENABLED',
        projectId: 'p2',
        projectTag: 'tools',
      }),
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

  it('#1013: the truncated allowed-models cell is reachable as a tooltip', async () => {
    // 多模型必然被 nowrap+ellipsis 截断，而截掉的部分在页面上没有第二个入口
    // （行内「更多」只有接入/轮换/重命名/停用/吊销）——所以这一格必须带 tooltip 触发器。
    mockApi.listVirtualKeys.mockResolvedValue([
      key({
        modelIds: [
          'deepseek-flash',
          'deepseek-v4-pro',
          'deepseek-v4-flash',
          'deepseek-v4-flash-vision-exp',
        ],
      }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const cell = wrapper.find('.next-keys__models');
    expect(cell.exists()).toBe(true);
    expect(cell.element.closest('.ui-tooltip__anchor')).not.toBeNull();
  });

  it('copies the masked key id from the row action', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([key({ id: 'k-copy-1', display: 'mqk_live_…8f2a' })]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="key-copy-k-copy-1"]').trigger('click');
    await flushPromises();

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('mqk_live_…8f2a');
  });

  it('shows the admin-contact onboarding when the account is in no project', async () => {
    mockApi.myGrants.mockResolvedValue({ projects: [], grants: [], purposes: [] });

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="onboard-no-project"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('等待管理员开通');
  });

  it('#1065: a failed load is not diagnosed as "not in any project"', async () => {
    mockApi.myGrants.mockResolvedValue({ projects: [], grants: [], purposes: [] });
    mockApi.listVirtualKeys.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        status: 500,
        code: 'INTERNAL',
        detail: '数据库不可用',
        requestId: 'req-500',
        title: 'Error',
      }),
    );

    const wrapper = mountView();
    await flushPromises();

    // The request failed — that says nothing about project membership, so the
    // onboarding (and its "ask your admin" steps) must not be what the user sees.
    expect(wrapper.find('[data-testid="onboard-no-project"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('还没有被加入任何项目');
    expect(wrapper.find('[data-testid="table-load-failed"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="keys-summary"]').text()).toBe('—');
  });

  it('keeps the plain empty invite once the account has a project', async () => {
    mockApi.myGrants.mockResolvedValue(grants);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="onboard-no-project"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="onboard-has-project"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('还没有虚拟密钥');
  });

  it('labels purpose as a declarative, non-restrictive tag (#596)', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.listVirtualKeys.mockResolvedValue([key()]);

    const wrapper = mountView();
    await flushPromises();

    // Every purpose value explains the label semantics through the shared
    // hover-bubble pattern (#651).
    expect(wrapper.findAll('.ui-tooltip__anchor').length).toBeGreaterThan(0);

    // Create form explains the label once the cascade reaches the purpose step.
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('miqi-dev');
    await flushPromises();
    const projectButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Core AI'));
    await projectButton!.trigger('click');
    await flushPromises();
    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Claude API'));
    await grantButton!.trigger('click');
    await flushPromises();

    const hint = wrapper.find('[data-testid="create-purpose-hint"]');
    expect(hint.exists()).toBe(true);
    expect(hint.text()).toContain('不限制客户端');
    expect(hint.text()).toContain('允许模型');
  });

  it('binds additional projects through the optional checkboxes (ADR-0018)', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await wrapper.find('[data-testid="create-name"]').setValue('multi-project');

    const projectButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Core AI'));
    await projectButton!.trigger('click');
    await flushPromises();
    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Claude API'));
    await grantButton!.trigger('click');
    await flushPromises();

    // Extra-project checkbox appears once a primary project is chosen.
    const extra = wrapper.find('[data-testid="create-extra-project-p2"]');
    expect(extra.exists()).toBe(true);
    await extra.setValue(true);
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p1', 'p2']);
  });

  it('#646: defaults to ALL projects — primary = first, every other project pre-selected', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('default-all');

    // No project interaction at all: pick the grant for the default primary.
    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Claude API'));
    await grantButton!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as {
      projectId?: string;
      projectIds?: string[];
    };
    expect(payload.projectId).toBe('p1');
    expect(payload.projectIds).toEqual(['p1', 'p2']);
  });

  it('#1157: an extra project granted another product is listed but not pre-selected', async () => {
    mockApi.myGrants.mockResolvedValue(grantsWithForeignProduct);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('mixed-products');

    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Claude API'));
    await grantButton!.trigger('click');
    await flushPromises();

    // Still offered: hiding it would leave the user guessing why a project they can
    // see elsewhere is missing here. What must not happen is the form pre-selecting
    // a combination the server refuses — that 409 arrives without the user doing
    // anything, which is the opposite of what Default-All is for.
    expect(wrapper.find('[data-testid="create-extra-project-p3"]').exists()).toBe(true);

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p1', 'p2']);
  });

  it('#1157: bouncing the primary across products leaves no non-bindable project checked', async () => {
    // Disjoint products: neither project is bindable while the other is primary, so
    // every step here leaves the bindable set empty. Storing the selection used to
    // backfill p2 on the way past and keep it checked even after the Claude grant was
    // re-picked — an untouched form then submitted a pair the server refuses.
    mockApi.myGrants.mockResolvedValue(grantsDisjointProducts);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('bounce');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API');
    await pick('QA Team');
    await pick('Core AI');
    await pick('Claude API');

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p1']);
  });

  it('#1157: switching the grant to another product re-binds the extras that match it', async () => {
    mockApi.myGrants.mockResolvedValue(grantsAcrossProducts);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('switch-grant');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API'); // p2 and p3 both hold Claude grants
    await pick('OpenAI API'); // only p2 holds one

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p1', 'p2']);
  });

  it('#1157: an explicit uncheck survives a change of product', async () => {
    mockApi.myGrants.mockResolvedValue(grantsAcrossProducts);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('keep-uncheck');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API');
    await wrapper.find('[data-testid="create-extra-project-p2"]').setValue(false);
    await flushPromises();
    // p2 is bindable for OpenAI too — re-deriving the selection must not undo the
    // user's explicit removal (that would silently bind a project they dropped).
    await pick('OpenAI API');

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p1']);
  });

  it('#1157: explicitly checking a non-bindable project still submits it', async () => {
    // The deliberate half of the design: the list stays complete, and a user who
    // insists gets the server's actionable refusal rather than a silent no-op. Pin
    // it, so a later "let's just filter the list" edit has to argue with a test.
    mockApi.myGrants.mockResolvedValue(grantsWithForeignProduct);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('insist');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API');
    await wrapper.find('[data-testid="create-extra-project-p3"]').setValue(true);
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const sent = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(sent.projectIds).toEqual(['p1', 'p2', 'p3']);
  });

  it('#1157: promoting a checked extra to primary does not submit it twice', async () => {
    mockApi.myGrants.mockResolvedValue(grantsWithForeignProduct);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('promote');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API');
    await wrapper.find('[data-testid="create-extra-project-p3"]').setValue(true);
    await flushPromises();
    await pick('Other Vendor'); // p3 becomes the primary
    await pick('OpenAI API'); // …and its own grant

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const sent = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(sent.projectIds).toEqual(['p3']);
  });

  it('#1157: a second form session starts from the default again', async () => {
    mockApi.myGrants.mockResolvedValue(grantsAcrossProducts);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('first');

    const pick = async (label: string) => {
      const option = wrapper.findAll('.stub-option').find((el) => el.text().includes(label));
      await option!.trigger('click');
      await flushPromises();
    };
    await pick('Claude API');
    await wrapper.find('[data-testid="create-extra-project-p2"]').setValue(false);
    await flushPromises();
    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();
    expect(
      (mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] }).projectIds,
    ).toEqual(['p1', 'p3']);

    // Reopen: the uncheck belonged to the previous session, not to the form.
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('second');
    await pick('Claude API');
    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const second = mockApi.createVirtualKey.mock.calls[1]![0] as { projectIds?: string[] };
    expect(second.projectIds).toEqual(['p1', 'p2', 'p3']);
  });

  it('#646: switching the primary keeps the previous project as an extra (no silent drop)', async () => {
    mockApi.myGrants.mockResolvedValue(grants);
    mockApi.createVirtualKey.mockResolvedValue(created);

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="create-key-open"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="create-name"]').setValue('switch-primary');

    const qaButton = wrapper.findAll('.stub-option').find((el) => el.text().includes('QA Team'));
    await qaButton!.trigger('click');
    await flushPromises();

    // The previous primary (Core AI / p1) stays bound as an extra.
    expect(wrapper.find('[data-testid="create-extra-project-p1"]').exists()).toBe(true);

    const grantButton = wrapper
      .findAll('.stub-option')
      .find((el) => el.text().includes('Claude API'));
    await grantButton!.trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    const payload = mockApi.createVirtualKey.mock.calls[0]![0] as { projectIds?: string[] };
    expect(payload.projectIds).toEqual(['p2', 'p1']);
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
      .find((el) => el.text().includes('Claude API'));
    expect(grantButton).toBeTruthy();
    await grantButton!.trigger('click');
    await flushPromises();

    // #646: extras default to ALL projects — deselect to keep this single-
    // project cascade under test (and prove unchecking works).
    const extraP2 = wrapper.find('[data-testid="create-extra-project-p2"]');
    expect(extraP2.exists()).toBe(true);
    await extraP2.setValue(false);
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
      projectIds: ['p1'],
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

    // CC Switch one-click import + copy-ready snippets ride along the one-time dialog.
    expect(document.querySelector('[data-testid="secret-ccswitch"]')).toBeTruthy();
    expect(document.querySelector('[data-testid="secret-copy-env"]')).toBeTruthy();
    expect(document.querySelector('[data-testid="secret-copy-settings"]')).toBeTruthy();
    // #839: the import panel offers both targets, defaulting from the purpose
    // (this key is CLAUDE_CODE) — Codex stays one click away.
    expect(document.querySelector('[data-testid="secret-ccswitch-app-claude"]')).toBeTruthy();
    const codexTarget = document.querySelector(
      '[data-testid="secret-ccswitch-app-codex"]',
    ) as HTMLInputElement;
    expect(codexTarget).toBeTruthy();
    expect(codexTarget.checked).toBe(false);
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
      .find((el) => el.text().includes('Claude API'))!
      .trigger('click');
    await flushPromises();

    await wrapper.find('[data-testid="create-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="create-error"]').text()).toContain('models are not granted');
    expect(wrapper.find('[data-testid="create-error"]').text()).toContain('req-123');
  });

  it('#582: the status filter narrows the list to one lifecycle state', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([
      key(),
      key({ id: 'k-off', name: 'paused-key', status: 'DISABLED' }),
      key({ id: 'k-dead', name: 'old-key', status: 'REVOKED' }),
    ]);

    const wrapper = mountView();
    await flushPromises();

    const offOption = wrapper.findAll('.stub-option').find((el) => el.text().trim() === '停用');
    await offOption!.trigger('click');
    await flushPromises();

    const tableText = wrapper.find('[data-testid="keys-table"]').text();
    expect(tableText).toContain('paused-key');
    expect(tableText).not.toContain('claude-code-main');
    expect(tableText).not.toContain('old-key');
  });

  it('#582: shows per-key 7-day usage inline and degrades to — for unused keys', async () => {
    mockApi.listVirtualKeys.mockResolvedValue([key(), key({ id: 'k-idle', name: 'idle-key' })]);
    mockApi.usageSummary.mockResolvedValue({
      groupBy: 'virtual_key',
      groups: [
        {
          groupKey: '0190-0001',
          label: 'claude-code-main',
          requests: { upstream: 3 },
          tokens: { input: 1000, output: 500 },
        },
      ],
      totals: {},
    });

    const wrapper = mountView();
    await flushPromises();

    const usageCells = wrapper.findAll('[data-testid="key-usage-inline"]');
    expect(usageCells).toHaveLength(1);
    expect(usageCells[0]!.text()).toContain('3 次');
    expect(usageCells[0]!.text()).toContain('1.5K tok');
    // The unused key renders the muted dash placeholder.
    expect(wrapper.findAll('.next-keys__usage--empty').length).toBe(1);
  });
});
