import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import NextCredentialsView from '@/views/next/NextCredentialsView.vue';
import NextGrantsView from '@/views/next/NextGrantsView.vue';
import * as api from '@/api';
import type { CredentialView, SubscriptionView } from '@/types/generated-api';

/**
 * #657 list IA, exercised through a **real router** instead of stubbed links:
 * the credentials list renders a real <router-link>, the router navigates, and
 * the grants list — mounted by <router-view> — turns the query into a filter.
 * The per-view specs stub RouterLink (they assert the intended target); this
 * one asserts the link, the route and the filter actually agree end to end.
 */

vi.mock('@/api', () => ({
  listCredentials: vi.fn(),
  listSubscriptions: vi.fn(),
  listGrants: vi.fn(),
  listProjects: vi.fn(),
  listProviderProducts: vi.fn(),
  createCredential: vi.fn(),
  validateCredential: vi.fn(),
  rotateCredential: vi.fn(),
  getCredential: vi.fn(),
  disableCredential: vi.fn(),
  createGrant: vi.fn(),
  grantModels: vi.fn(),
  updateGrantModels: vi.fn(),
  disableGrant: vi.fn(),
  adminListModels: vi.fn(),
  adminProbeModels: vi.fn(),
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
  template: '<div class="ui-select-stub" />',
});

const Host = defineComponent({ name: 'RouterHost', template: '<router-view />' });

const subscription = (id: string, productId: string, productName: string): SubscriptionView => ({
  id,
  providerProductId: productId,
  productName,
  name: `${productName} Main`,
  billingMode: 'PAYG',
  planScope: 'PERSONAL',
  subscriptionPrice: null as unknown as number,
  currency: 'USD',
  quotaTotal: null as unknown as number,
  quotaUnit: null as unknown as string,
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
});

const credential = (
  id: string,
  name: string,
  subscriptionId: string,
  overrides: Partial<CredentialView> = {},
): CredentialView => ({
  id,
  name,
  subscriptionId,
  status: 'ACTIVE',
  activeVersionId: `${id}-v1`,
  fingerprintPrefix: 'sk-a1b2c3d4e5f6',
  lastValidatedAt: null as unknown as string,
  lastValidationError: null as unknown as string,
  version: 1,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  ...overrides,
});

/** Data rows only — skeleton rows share the class while loading. */
function dataRows(wrapper: ReturnType<typeof mount>): number {
  return wrapper.findAll('.ui-table__row').filter((row) => !row.find('.ui-skeleton').exists())
    .length;
}

/** The table row that mentions `needle`, so assertions never pin row order. */
function rowContaining(wrapper: ReturnType<typeof mount>, needle: string) {
  const row = wrapper.findAll('.ui-table__row').find((el) => el.text().includes(needle));
  expect(row, `no row mentions ${needle}`).toBeTruthy();
  return row!;
}

describe('#657 list information architecture over a real router', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listSubscriptions.mockResolvedValue([
      subscription('s1', 'pr1', 'DeepSeek PAYG'),
      subscription('s2', 'pr2', 'Moonshot PAYG'),
    ]);
    // Two grants reference c1, one references c3, none references c2 — so the
    // dependency column, the filter and the unfiltered list all differ.
    mockApi.listCredentials.mockResolvedValue([
      credential('c1', 'deepseek-main', 's1'),
      credential('c2', 'moonshot-main', 's2'),
      credential('c3', 'glm-main', 's2'),
    ]);
    mockApi.listGrants.mockResolvedValue([
      {
        id: 'g1',
        projectId: 'p1',
        providerProductId: 'pr1',
        upstreamCredentialId: 'c1',
        status: 'ACTIVE',
      },
      {
        id: 'g2',
        projectId: 'p2',
        providerProductId: 'pr1',
        upstreamCredentialId: 'c1',
        status: 'ACTIVE',
      },
      {
        id: 'g3',
        projectId: 'p2',
        providerProductId: 'pr2',
        upstreamCredentialId: 'c3',
        status: 'ACTIVE',
      },
    ] as never);
    mockApi.listProjects.mockResolvedValue([
      {
        id: 'p1',
        code: 'CORE',
        name: 'Core AI',
        status: 'ACTIVE',
        projectTag: 'core-ai',
        createdAt: '2026-08-01T00:00:00Z',
      },
      {
        id: 'p2',
        code: 'QA',
        name: 'QA 回归',
        status: 'ACTIVE',
        projectTag: 'qa',
        createdAt: '2026-08-01T00:00:00Z',
      },
    ]);
    mockApi.listProviderProducts.mockResolvedValue([
      {
        id: 'pr1',
        providerSlug: 'deepseek',
        providerName: 'DeepSeek',
        productCode: 'deepseek-v4',
        displayName: 'DeepSeek V4',
        billingMode: 'PAYG',
        protocols: 'openai,anthropic',
        baseUrlHost: 'api.deepseek.com',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'none',
      },
      {
        id: 'pr2',
        providerSlug: 'moonshot',
        providerName: 'Moonshot',
        productCode: 'moonshot',
        displayName: 'Moonshot',
        billingMode: 'PAYG',
        protocols: 'openai',
        baseUrlHost: 'api.moonshot.cn',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'official',
      },
    ]);
  });

  async function mountAt(path: string) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/app/credentials', name: 'credentials', component: NextCredentialsView },
        { path: '/app/grants', name: 'grants', component: NextGrantsView },
      ],
    });
    await router.push(path);
    await router.isReady();
    const wrapper = mount(Host, {
      global: { plugins: [createPinia(), router], stubs: { UiSelect: SelectStub } },
    });
    await flushPromises();
    return { wrapper, router };
  }

  it('links a referenced credential to its grants and lands on a filtered list (#657)', async () => {
    const { wrapper, router } = await mountAt('/app/credentials');

    const cell = rowContaining(wrapper, 'deepseek-main').find(
      '[data-testid="credential-grant-count"]',
    );
    expect(cell.element.tagName).toBe('A');
    expect(cell.text()).toBe('2');
    const href = cell.attributes('href')!;
    expect(href).toContain('/app/grants');
    expect(href).toContain('credentialId=c1');

    await router.push(href);
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('grants');
    expect(router.currentRoute.value.query.credentialId).toBe('c1');
    // The jump has to land on a filtered list, not a page that ignores it.
    expect(dataRows(wrapper)).toBe(2);
    expect(wrapper.find('[data-testid="grants-filter"]').text()).toContain('deepseek-main');
    expect(wrapper.find('.ui-panel-sub').text()).toContain('共 2 条授权（全部 3 条）');
  });

  it('keeps a credential with no grants unlinked (#657)', async () => {
    const { wrapper } = await mountAt('/app/credentials');

    const cell = rowContaining(wrapper, 'moonshot-main').find(
      '[data-testid="credential-grant-count"]',
    );
    expect(cell.element.tagName).toBe('SPAN');
    expect(cell.text()).toBe('0');
  });

  it('clears the credential filter through the real router (#657)', async () => {
    const { wrapper, router } = await mountAt('/app/grants?credentialId=c1');
    expect(dataRows(wrapper)).toBe(2);

    await wrapper.find('[data-testid="grants-filter-clear"]').trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.query.credentialId).toBeUndefined();
    expect(dataRows(wrapper)).toBe(3);
    expect(wrapper.find('[data-testid="grants-filter"]').exists()).toBe(false);
  });

  it('routes the empty-state CTA back to the full list (#657)', async () => {
    const { wrapper, router } = await mountAt('/app/grants?credentialId=c9');
    expect(dataRows(wrapper)).toBe(0);
    expect(wrapper.text()).toContain('该凭证还没有被任何授权引用');

    await wrapper.find('[data-testid="table-empty-action"]').trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.query.credentialId).toBeUndefined();
    expect(dataRows(wrapper)).toBe(3);
  });
});
