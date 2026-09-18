import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextProvidersView from '@/views/next/NextProvidersView.vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { ProviderProductView } from '@/types/api';

vi.mock('@/api', () => ({
  listProviderProducts: vi.fn(),
  listSubscriptions: vi.fn(),
  listCredentials: vi.fn(),
  listGrants: vi.fn(),
  adminListModels: vi.fn(),
  adminCreateModel: vi.fn(),
  adminProbeModels: vi.fn(),
  adminModelProbeStatus: vi.fn(),
  adminDeleteModel: vi.fn(),
  adminTestRunModel: vi.fn(),
}));

const mockApi = vi.mocked(api);

const product = (overrides: Partial<ProviderProductView> = {}): ProviderProductView => ({
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
  ...overrides,
});

describe('NextProvidersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.listProviderProducts.mockResolvedValue([
      product(),
      product({
        id: '0021',
        providerSlug: 'aliyun',
        providerName: '阿里云',
        productCode: 'bailian-coding-plan',
        displayName: '百炼 Coding Plan',
        billingMode: 'TOKEN_PACKAGE',
        baseUrlHost: 'coding.dashscope.aliyuncs.com',
        implementationStatus: 'IMPLEMENTED',
        balanceAuthority: 'UNAVAILABLE',
      }),
    ]);
    // #657 dependency/catalog columns: defaults are empty, individual tests override.
    mockApi.listSubscriptions.mockResolvedValue([]);
    mockApi.listCredentials.mockResolvedValue([]);
    mockApi.listGrants.mockResolvedValue([]);
    mockApi.adminListModels.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextProvidersView, { global: { plugins: [createPinia()] } });
  }

  it('renders the provider catalogue with status and balance labels', async () => {
    // #657: one catalogue row for the DeepSeek product → counted in the 模型目录 column.
    mockApi.adminListModels.mockResolvedValue([
      { id: 'm1', providerProductId: '0190-0000-0000-0020', modelId: 'deepseek-chat' },
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="products-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('DeepSeek PAYG');
    expect(wrapper.text()).toContain('已验证');
    expect(wrapper.text()).toContain('官方 API');
    expect(wrapper.text()).toContain('百炼 Coding Plan');
    expect(wrapper.text()).toContain('已实现');
    expect(wrapper.text()).toContain('不可用');
    expect(wrapper.text()).toContain('api.deepseek.com');
    expect(wrapper.text()).toContain('模型目录');
    const catalog = wrapper.findAll('[data-testid="product-catalog-count"]');
    expect(catalog[0]!.text()).toContain('1 个模型');
    expect(catalog[1]!.text()).toContain('未探测');
    expect(wrapper.find('[data-testid="product-deps"]').text()).toMatch(/凭证\s*0\s*·\s*授权\s*0/);
  });

  it('F18: opens the model catalog drawer and adds a manual model', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminCreateModel.mockResolvedValue({
      id: 'mm1',
      providerProductId: '0190-0000-0000-0020',
      modelId: 'manual-probe-fallback',
      displayName: '人工兜底',
      status: 'ACTIVE',
      source: 'MANUAL',
      version: 0,
      updatedAt: '2026-09-08T00:00:00Z',
    });
    const wrapper = mount(NextProvidersView, { global: { plugins: [createPinia()] } });
    await flushPromises();

    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();
    await flushPromises();
    expect(mockApi.adminListModels).toHaveBeenCalledWith('0190-0000-0000-0020');
    const dialog = document.querySelector('[data-testid="product-models-dialog"]');
    expect(dialog, 'models dialog should open').toBeTruthy();
    expect(dialog!.textContent).toContain('暂无目录模型');

    mockApi.adminListModels.mockResolvedValue([
      {
        id: 'mm1',
        providerProductId: '0190-0000-0000-0020',
        modelId: 'manual-probe-fallback',
        displayName: '人工兜底',
        status: 'ACTIVE',
        source: 'MANUAL',
        version: 0,
        updatedAt: '2026-09-08T00:00:00Z',
      },
    ]);
    const idInput = document.querySelector('[data-testid="product-models-id"]') as HTMLInputElement;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(idInput, 'manual-probe-fallback');
    idInput.dispatchEvent(new Event('input', { bubbles: true }));
    (document.querySelector('[data-testid="product-models-add"]') as HTMLButtonElement).click();
    await flushPromises();
    const dialogAfter = document.querySelector('[data-testid="product-models-dialog"]');
    expect(dialogAfter?.textContent, 'no guard error expected').not.toContain('模型 ID 必填');
    expect(mockApi.adminCreateModel).toHaveBeenCalledWith('0190-0000-0000-0020', {
      modelId: 'manual-probe-fallback',
      displayName: undefined,
    });
    await flushPromises();
    expect(dialog!.textContent).toContain('manual-probe-fallback');
    expect(dialog!.textContent).toContain('人工');
  });

  it('I4: probes the provider model catalog and shows the last probe status', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminModelProbeStatus.mockResolvedValue({
      status: 'SUCCEEDED',
      error: null,
      modelCount: 2,
      probedAt: '2026-09-10T10:00:00Z',
    });
    mockApi.adminProbeModels.mockResolvedValue({
      providerProductId: '0190-0000-0000-0020',
      productCode: 'deepseek-payg-api',
      modelCount: 2,
      probedAt: '2026-09-10T12:00:00Z',
      models: [{ modelId: 'deepseek-chat', displayName: 'DeepSeek Chat' }],
    });
    const wrapper = mountView();
    await flushPromises();
    // #657: load() also fetches the full catalog once — count only the dialog's calls.
    mockApi.adminListModels.mockClear();
    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();

    const statusLine = document.querySelector('[data-testid="product-probe-status"]');
    expect(statusLine?.textContent).toContain('上次探测成功');
    expect(statusLine?.textContent).toContain('2 个模型');

    (document.querySelector('[data-testid="product-probe"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminProbeModels).toHaveBeenCalledWith('0190-0000-0000-0020');
    expect(mockApi.adminListModels).toHaveBeenCalledTimes(2);
  });

  it('I4: a failed probe surfaces the sanitized error inline', async () => {
    mockApi.adminListModels.mockResolvedValue([]);
    mockApi.adminModelProbeStatus.mockResolvedValue({
      status: 'FAILED',
      error: 'DeepSeek /models returned HTTP 500',
      modelCount: null,
      probedAt: '2026-09-10T11:00:00Z',
    });
    mockApi.adminProbeModels.mockRejectedValue(
      new ApiError({
        type: 'about:blank',
        title: 'probe failed',
        status: 502,
        code: 'MODEL_PROBE_FAILED',
        detail: 'DeepSeek /models returned HTTP 500',
        requestId: 'rq-1',
      }),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();

    (document.querySelector('[data-testid="product-probe"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(document.querySelector('[data-testid="product-probe-error"]')?.textContent).toContain(
      'HTTP 500',
    );
    expect(document.querySelector('[data-testid="product-probe-status"]')?.textContent).toContain(
      '上次探测失败',
    );
  });

  it('I552: test-runs a model from the catalog and shows reply, latency and tokens', async () => {
    mockApi.adminListModels.mockResolvedValue([
      {
        id: 'mm2',
        providerProductId: '0190-0000-0000-0020',
        modelId: 'deepseek-flash',
        displayName: 'DeepSeek Flash',
        status: 'ACTIVE',
        source: 'OFFICIAL',
        version: 0,
        updatedAt: '2026-09-15T00:00:00Z',
      },
    ]);
    mockApi.adminModelProbeStatus.mockResolvedValue({
      status: null,
      error: null,
      modelCount: null,
      probedAt: null,
    });
    mockApi.adminTestRunModel.mockResolvedValue({
      providerProductId: '0190-0000-0000-0020',
      modelId: 'deepseek-flash',
      httpStatus: 200,
      latencyMs: 123,
      content: '联调OK',
      promptTokens: 7,
      completionTokens: 3,
      totalTokens: 10,
    });
    const wrapper = mount(NextProvidersView, { global: { plugins: [createPinia()] } });
    await flushPromises();

    await wrapper.find('[data-testid="product-models-open"]').trigger('click');
    await flushPromises();
    await flushPromises();

    (
      document.querySelector(
        '[data-testid="product-model-testrun-deepseek-flash"]',
      ) as HTMLButtonElement
    ).click();
    await flushPromises();
    expect(document.querySelector('[data-testid="model-testrun-dialog"]')).toBeTruthy();

    (document.querySelector('[data-testid="model-testrun-run"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(mockApi.adminTestRunModel).toHaveBeenCalledWith(
      '0190-0000-0000-0020',
      'deepseek-flash',
      undefined,
    );
    const result = document.querySelector('[data-testid="model-testrun-result"]');
    expect(result).toBeTruthy();
    expect(result!.textContent).toContain('联调OK');
    expect(result!.textContent).toContain('HTTP 200');
    expect(result!.textContent).toContain('123 ms');
    expect(result!.textContent).toContain('tokens 10');
  });

  it('#651: row operations are links — model catalog plus a per-provider docs deep link', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="product-models-open"]').classes()).toContain(
      'ui-link-action',
    );

    const docLinks = wrapper.findAll('[data-testid="product-doc-open"]');
    expect(docLinks).toHaveLength(2);
    const deepseekDoc = docLinks[0]!;
    const aliyunDoc = docLinks[1]!;
    expect(deepseekDoc.attributes('href')).toBe(
      'https://github.com/sijie-Z/miqro-gate/blob/develop/docs/provider-catalog.md#38-deepseek-官方-api',
    );
    expect(aliyunDoc.attributes('href')).toBe(
      'https://github.com/sijie-Z/miqro-gate/blob/develop/docs/provider-catalog.md#32-阿里云百炼-model-studio',
    );
    expect(deepseekDoc.attributes('target')).toBe('_blank');
    expect(deepseekDoc.attributes('rel')).toContain('noopener');
  });

  it('#651: focusing a status badge reveals the state explainer tooltip', async () => {
    const wrapper = mountView();
    await flushPromises();

    const anchors = wrapper.findAll('.ui-tooltip__anchor');
    expect(anchors).toHaveLength(2);

    await anchors[0]!.trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain('真实供应商凭证');
  });

  // #735: docs/provider-adapter-contract.md promises a *persistent* warning for
  // products that are not VERIFIED. Every test below is written so that the
  // same query flips between "present" and "absent" across statuses — a bare
  // "element exists" assertion would also pass if the warning were rendered
  // unconditionally.
  describe('#735 adapter-status persistent warning', () => {
    /** DeepSeek VERIFIED + 阿里云 IMPLEMENTED + 腾讯云 DOCUMENTED. */
    function threeStatuses() {
      mockApi.listProviderProducts.mockResolvedValue([
        product(),
        product({
          id: '0021',
          providerSlug: 'aliyun',
          providerName: '阿里云',
          productCode: 'bailian-coding-plan',
          displayName: '百炼 Coding Plan',
          implementationStatus: 'IMPLEMENTED',
        }),
        product({
          id: '0022',
          providerSlug: 'tencent',
          providerName: '腾讯云',
          productCode: 'tencent-coding-plan',
          displayName: '腾讯云 Coding Plan',
          implementationStatus: 'DOCUMENTED',
        }),
      ]);
    }

    it('warns on the page and per row for non-VERIFIED products only', async () => {
      threeStatuses();
      const wrapper = mountView();
      await flushPromises();

      const inline = wrapper.findAll('[data-testid="adapter-warning-row"]');
      expect(inline).toHaveLength(2);
      expect(inline.map((n) => n.text())).toEqual(['⚠ 非已验证', '⚠ 非已验证']);

      const banner = wrapper.find('[data-testid="adapter-warning-banner"]');
      expect(banner.exists()).toBe(true);
      expect(banner.text()).toContain('共 2 个产品实例当前不是 VERIFIED');
      expect(banner.text()).toContain('本提示不改变产品的启用与可用行为');
    });

    it('renders no warning at all when every product is VERIFIED', async () => {
      // Discriminating power: the per-row query is the same one that returned 2
      // above, so it is the status — not the markup — that drives the count.
      mockApi.listProviderProducts.mockResolvedValue([
        product(),
        product({ id: '0021', displayName: 'DeepSeek PAYG (2)', providerSlug: 'deepseek' }),
      ]);
      const wrapper = mountView();
      await flushPromises();

      expect(wrapper.findAll('[data-testid="adapter-warning-row"]')).toHaveLength(0);
      expect(wrapper.find('[data-testid="adapter-warning-banner"]').exists()).toBe(false);
    });

    it('marks DEGRADED and DRAFT products too, and drops out once VERIFIED', async () => {
      mockApi.listProviderProducts.mockResolvedValue([
        product({ id: '0021', displayName: '降级产品', implementationStatus: 'DEGRADED' }),
        product({ id: '0022', displayName: '草稿产品', implementationStatus: 'DRAFT' }),
      ]);
      const wrapper = mountView();
      await flushPromises();

      expect(wrapper.findAll('[data-testid="adapter-warning-row"]')).toHaveLength(2);
      expect(wrapper.find('[data-testid="adapter-warning-banner"]').text()).toContain(
        '共 2 个产品实例当前不是 VERIFIED',
      );
    });

    it('flags DISABLED rows with the state label, not the plain "unverified" wording', async () => {
      // DISABLED also is not VERIFIED, so it carries the marker; the badge and
      // the hint next to it are what separate "disabled" from "not yet
      // verified". The marker wording is deliberately status-agnostic.
      mockApi.listProviderProducts.mockResolvedValue([
        product({ id: '0021', displayName: '停用产品', implementationStatus: 'DISABLED' }),
      ]);
      const wrapper = mountView();
      await flushPromises();

      const row = wrapper.find('[data-testid="adapter-warning-row"]');
      expect(row.exists()).toBe(true);
      expect(row.text()).toBe('⚠ 非已验证');
      expect(wrapper.text()).toContain('已停用');
      // The hint lives in the badge tooltip, which renders outside the wrapper
      // and only mounts once the anchor is focused; earlier mounts in the same
      // file leave their own tooltips behind, so read the union of all of them.
      await wrapper.find('.ui-tooltip__anchor').trigger('focus');
      await flushPromises();
      const hints = Array.from(document.querySelectorAll('.ui-tooltip')).map(
        (node) => node.textContent ?? '',
      );
      expect(hints.join(' ')).toContain('该产品实例已停用，不再用于新建凭证。');
    });

    it('repeats the warning inside the product detail surface', async () => {
      threeStatuses();
      const wrapper = mountView();
      await flushPromises();

      const opens = wrapper.findAll('[data-testid="product-models-open"]');

      // Row 1 is the VERIFIED DeepSeek product: no warning block.
      await opens[0]!.trigger('click');
      await flushPromises();
      expect(document.querySelector('[data-testid="product-models-dialog"]')).toBeTruthy();
      expect(document.querySelector('[data-testid="product-models-adapter-warning"]')).toBeNull();

      // Row 3 is DOCUMENTED: the same query now finds the block, which explains
      // the state in place instead of only on hover.
      await opens[2]!.trigger('click');
      await flushPromises();
      const block = document.querySelector('[data-testid="product-models-adapter-warning"]');
      expect(block, 'DOCUMENTED product should warn in its detail dialog').toBeTruthy();
      expect(block!.textContent).toContain('该产品未处于「已验证」状态');
      expect(block!.textContent).toContain('已文档化');
      expect(block!.textContent).toContain('适配器尚未完成验证');
    });
  });
});
