<script setup lang="ts">
/**
 * NextProvidersView — /app/providers v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy providers page: catalogue of provider
 * product instances with protocol / base host / implementation / balance
 * source columns.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiInput,
  UiPageGuide,
  UiStatusBadge,
  UiTable,
  UiTooltip,
  toast,
} from '@/ui';
import ProviderBrandChip from '@/components/ProviderBrandChip.vue';
import type { ProviderProductView } from '@/types/api';
import type { CredentialView, Grant, ModelCatalogRow, SubscriptionView } from '@/types/generated-api';
import { PROVIDERS_GUIDE } from '@/content/pageGuides';

const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
// 依赖与目录元数据（#657）：一次并行取订阅/凭证/授权/全量模型目录，聚合成
// 列表上的「模型数 / 凭证数 / 授权数」——删除或停用前的轻量影响面。
const subscriptions = ref<SubscriptionView[]>([]);
const credentials = ref<CredentialView[]>([]);
const grants = ref<Grant[]>([]);
const catalogModels = ref<ModelCatalogRow[]>([]);

// UiTable slot rows arrive as loose Records; the catalogue list always returns
// complete product rows, so cast back to the handwritten view type (which is
// deliberately kept in @/types/api, not migrated to the generated hub).
function productOf(row: unknown): ProviderProductView {
  return row as unknown as ProviderProductView;
}

const columns = [
  { key: 'provider', title: '供应商', width: '220px' },
  { key: 'product', title: '产品', minWidth: '220px' },
  { key: 'protocols', title: '协议', width: '190px' },
  { key: 'baseUrl', title: '接入地址', minWidth: '220px' },
  { key: 'catalog', title: '模型目录', width: '110px' },
  { key: 'implementationStatus', title: '实现状态', width: '130px' },
  { key: 'balanceAuthority', title: '余额来源', width: '120px' },
  { key: 'deps', title: '依赖', width: '150px' },
  { key: 'actions', title: '操作', width: '200px' },
];

function implTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'VERIFIED':
      return 'success';
    case 'IMPLEMENTED':
      return 'warning';
    case 'DEGRADED':
      return 'danger';
    default:
      return 'neutral';
  }
}

const implLabel: Record<string, string> = {
  DRAFT: '草稿',
  DOCUMENTED: '已文档化',
  VERIFIED: '已验证',
  IMPLEMENTED: '已实现',
  DEGRADED: '降级',
  DISABLED: '已停用',
};

// Status-badge explainers (#651) — the state ladder as defined in
// docs/provider-catalog.md §2, so the badge alone is not the whole story.
const implHint: Record<string, string> = {
  DRAFT: '产品条目已创建，尚未完成资料整理。',
  DOCUMENTED: '官方资料已确认设计与接入方式；适配器尚未完成验证。详见「接入文档」。',
  IMPLEMENTED: '适配器与 Mock 契约测试已完成，等待真实凭证验证。',
  VERIFIED: '已用真实供应商凭证完成契约测试。',
  DEGRADED: '部分能力只能本地估算或人工核对，详见接入文档。',
  DISABLED: '该产品实例已停用，不再用于新建凭证。',
};

function implHintOf(status: string): string {
  return implHint[status] ?? '实现状态含义见接入文档。';
}

// Per-provider docs deep links (#651): docs/provider-catalog.md §3.1–3.8 each
// cover one provider's endpoints, auth and reference materials. Anchor slugs
// follow GitHub's heading ids for the Chinese headings; an unknown provider
// falls back to the doc top.
const DOC_URL = 'https://github.com/sijie-Z/miqro-gate/blob/develop/docs/provider-catalog.md';
const DOC_ANCHORS: Record<string, string> = {
  aliyun: '32-阿里云百炼-model-studio',
  baidu: '36-百度千帆',
  deepseek: '38-deepseek-官方-api',
  minimax: '34-minimax',
  moonshot: '35-kimi--moonshot',
  tencent: '31-腾讯云-tokenhub',
  volcengine: '37-火山引擎方舟',
  zhipu: '33-智谱-glm',
};

function docUrl(product: ProviderProductView): string {
  const anchor = DOC_ANCHORS[product.providerSlug];
  return anchor ? `${DOC_URL}#${anchor}` : DOC_URL;
}

function balanceLabel(authority: string): string {
  switch (authority) {
    case 'OFFICIAL_API':
      return '官方 API';
    case 'LOCAL_ESTIMATE':
      return '本地估算';
    case 'UNAVAILABLE':
      return '不可用';
    default:
      return authority;
  }
}

// F18 model-catalog maintenance (manual entry fallback)
const modelsProduct = ref<ProviderProductView | null>(null);
const modelsVisible = ref(false);
const models = ref<ModelCatalogRow[]>([]);
const modelsLoading = ref(false);
const modelsError = ref('');
const modelForm = ref({ modelId: '', displayName: '' });
const modelSaving = ref(false);
const modelError = ref('');
// Model probe (#346, I4): admin-triggered official catalog fetch
const probeStatus = ref<api.ModelProbeStatus | null>(null);
const probing = ref(false);
const probeError = ref('');

// #552 model test-run (console 在线调试): one real chat call per credential×model
const testRunVisible = ref(false);
const testRunModelId = ref('');
const testRunPrompt = ref('');
const testRunning = ref(false);
const testRunError = ref('');
const testRunResult = ref<api.ModelTestRunResult | null>(null);

function openTestRun(modelId: string) {
  testRunModelId.value = modelId;
  testRunPrompt.value = '';
  testRunError.value = '';
  testRunResult.value = null;
  testRunVisible.value = true;
}

async function runTestRun() {
  const product = modelsProduct.value;
  if (!product || !testRunModelId.value) {
    return;
  }
  testRunning.value = true;
  testRunError.value = '';
  testRunResult.value = null;
  try {
    testRunResult.value = await api.adminTestRunModel(
      product.id,
      testRunModelId.value,
      testRunPrompt.value.trim() || undefined,
    );
  } catch (error) {
    testRunError.value = error instanceof ApiError ? error.message : '试调失败，请稍后重试。';
  } finally {
    testRunning.value = false;
  }
}

// #440: request-sequence guard — a slow catalog load for product A must not
// land after the dialog re-targets product B (manual row actions would then
// act under the wrong product's header).
let modelsRequestSeq = 0;

async function openModels(product: ProviderProductView) {
  const seq = ++modelsRequestSeq;
  modelsProduct.value = product;
  models.value = [];
  modelsError.value = '';
  modelForm.value = { modelId: '', displayName: '' };
  modelError.value = '';
  modelsVisible.value = true;
  modelsLoading.value = true;
  probeError.value = '';
  probeStatus.value = null;
  try {
    const rows = await api.adminListModels(product.id);
    if (seq !== modelsRequestSeq) {
      return; // a newer dialog target won — this response is stale
    }
    models.value = rows;
  } catch (error) {
    if (seq === modelsRequestSeq) {
      modelsError.value = error instanceof ApiError ? error.message : '加载模型目录失败。';
    }
  } finally {
    if (seq === modelsRequestSeq) {
      modelsLoading.value = false;
    }
  }
  try {
    const status = await api.adminModelProbeStatus(product.id);
    if (seq === modelsRequestSeq) {
      probeStatus.value = status;
    }
  } catch {
    // Probe status is best-effort; the dialog works without it.
  }
}

async function probeModels() {
  const target = modelsProduct.value;
  if (!target) {
    return;
  }
  const seq = ++modelsRequestSeq;
  probing.value = true;
  probeError.value = '';
  try {
    const report = await api.adminProbeModels(target.id);
    toast.success(`探测完成：发现 ${report.modelCount} 个模型`);
    const rows = await api.adminListModels(target.id);
    if (seq === modelsRequestSeq) {
      models.value = rows;
    }
  } catch (error) {
    probeError.value = error instanceof ApiError ? error.message : '探测失败，请稍后重试。';
  } finally {
    probing.value = false;
  }
  try {
    const status = await api.adminModelProbeStatus(target.id);
    if (seq === modelsRequestSeq) {
      probeStatus.value = status;
    }
  } catch {
    // Best-effort status refresh.
  }
}

async function addManualModel() {
  const target = modelsProduct.value;
  if (!target) {
    return;
  }
  const modelId = modelForm.value.modelId.trim();
  if (!modelId) {
    modelError.value = '模型 ID 必填。';
    return;
  }
  modelSaving.value = true;
  modelError.value = '';
  try {
    await api.adminCreateModel(target.id, {
      modelId,
      displayName: modelForm.value.displayName.trim() || undefined,
    });
    modelForm.value = { modelId: '', displayName: '' };
    toast.success('人工模型已录入');
    const seq = ++modelsRequestSeq;
    const rows = await api.adminListModels(target.id);
    if (seq === modelsRequestSeq) {
      models.value = rows;
    }
  } catch (error) {
    modelError.value = error instanceof ApiError ? error.message : '录入失败，请稍后重试。';
  } finally {
    modelSaving.value = false;
  }
}

async function removeManualModel(row: ModelCatalogRow) {
  // Model rows come from adminListModels; the server always issues ids.
  try {
    await api.adminDeleteModel(row.id!);
    toast.success(`已删除 ${row.modelId}`);
    const target = modelsProduct.value;
    if (target) {
      const seq = ++modelsRequestSeq;
      const rows = await api.adminListModels(target.id);
      if (seq === modelsRequestSeq) {
        models.value = rows;
      }
    }
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

const catalogCountByProduct = computed(() => {
  const counts = new Map<string, number>();
  for (const row of catalogModels.value) {
    if (row.providerProductId) {
      counts.set(row.providerProductId, (counts.get(row.providerProductId) ?? 0) + 1);
    }
  }
  return counts;
});

const credentialCountByProduct = computed(() => {
  const productBySubscription = new Map(
    subscriptions.value.map((s) => [s.id, s.providerProductId]),
  );
  const counts = new Map<string, number>();
  for (const c of credentials.value) {
    const productId = c.subscriptionId ? productBySubscription.get(c.subscriptionId) : undefined;
    if (productId) counts.set(productId, (counts.get(productId) ?? 0) + 1);
  }
  return counts;
});

const grantCountByProduct = computed(() => {
  const counts = new Map<string, number>();
  for (const g of grants.value) {
    if (g.providerProductId) {
      counts.set(g.providerProductId, (counts.get(g.providerProductId) ?? 0) + 1);
    }
  }
  return counts;
});

function catalogCountOf(productId: string): number {
  return catalogCountByProduct.value.get(productId) ?? 0;
}

function credentialCountOf(productId: string): number {
  return credentialCountByProduct.value.get(productId) ?? 0;
}

function grantCountOf(productId: string): number {
  return grantCountByProduct.value.get(productId) ?? 0;
}

async function load() {
  loading.value = true;
  try {
    // 产品目录是主数据（失败必须可见）；订阅/凭证/授权/模型目录是依赖与目录
    // 元数据，任一失败降级为空（计数显示 0 / 未探测），不阻塞列表（#657）。
    const [productList, subscriptionList, credentialList, grantList, modelList] = await Promise.all(
      [
        api.listProviderProducts(),
        api.listSubscriptions().catch(() => []),
        api.listCredentials().catch(() => []),
        api.listGrants().catch(() => []),
        api.adminListModels().catch(() => []),
      ],
    );
    products.value = productList;
    subscriptions.value = subscriptionList;
    credentials.value = credentialList;
    grants.value = grantList;
    catalogModels.value = modelList;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-providers">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">供应商</h1>
        <p class="ui-page-desc">供应商产品实例：协议、Plan 形态、验证状态与余额来源。</p>
      </div>
    </header>

    <UiPageGuide :guide="PROVIDERS_GUIDE" storage-key="providers" />

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ products.length }} 个产品实例</span>
      </div>
      <UiTable
        :columns="columns"
        :data="products"
        :loading="loading"
        row-key="id"
        empty-title="暂无产品实例"
        empty-description="产品目录由签名目录播种；接入从「上游凭证」录入第一把真实密钥开始。"
        data-testid="products-table"
      >
        <template #provider="{ row }">
          <span class="next-providers__provider">
            <ProviderBrandChip
              :slug="productOf(row).providerSlug"
              :name="productOf(row).providerName"
              size="sm"
            />
            <span>{{ productOf(row).providerName }}</span>
          </span>
        </template>
        <template #product="{ row }">
          <div class="next-providers__name">{{ productOf(row).displayName }}</div>
          <div class="ui-mono next-providers__code">{{ productOf(row).productCode }}</div>
        </template>
        <template #protocols="{ row }">
          <span class="ui-mono">{{ productOf(row).protocols }}</span>
        </template>
        <template #baseUrl="{ row }">
          <span class="ui-mono">{{ productOf(row).baseUrlHost || '—' }}</span>
        </template>
        <template #catalog="{ row }">
          <span
            v-if="catalogCountOf(productOf(row).id)"
            class="ui-num"
            data-testid="product-catalog-count"
            >{{ catalogCountOf(productOf(row).id) }} 个模型</span
          >
          <span v-else class="next-providers__muted" data-testid="product-catalog-count"
            >未探测</span
          >
        </template>
        <template #implementationStatus="{ row }">
          <UiTooltip :text="implHintOf(productOf(row).implementationStatus)">
            <UiStatusBadge
              :tone="implTone(productOf(row).implementationStatus)"
              :label="
                implLabel[productOf(row).implementationStatus] ??
                productOf(row).implementationStatus
              "
            />
          </UiTooltip>
        </template>
        <template #balanceAuthority="{ row }">
          <span class="next-providers__balance">{{
            balanceLabel(productOf(row).balanceAuthority)
          }}</span>
        </template>
        <template #deps="{ row }">
          <span class="ui-num" data-testid="product-deps"
            >凭证 {{ credentialCountOf(productOf(row).id) }} · 授权
            {{ grantCountOf(productOf(row).id) }}</span
          >
        </template>
        <template #actions="{ row }">
          <div class="next-providers__actions">
            <UiButton
              variant="link"
              size="sm"
              data-testid="product-models-open"
              @click="openModels(productOf(row))"
              >模型目录</UiButton
            >
            <a
              class="ui-link-action"
              :href="docUrl(productOf(row))"
              target="_blank"
              rel="noopener"
              data-testid="product-doc-open"
              >接入文档<svg
                width="12"
                height="12"
                viewBox="0 0 16 16"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M12 9.5V12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h2.5"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <path
                  d="M9.5 3H13v3.5M13 3 7.5 8.5"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                /></svg
            ></a>
          </div>
        </template>
      </UiTable>
    </section>

    <!-- F18 model catalog (manual entry fallback) -->
    <UiDialog
      :open="modelsVisible"
      :title="modelsProduct ? `模型目录 · ${modelsProduct.displayName}` : '模型目录'"
      width="620px"
      data-testid="product-models-dialog"
      @update:open="modelsVisible = false"
    >
      <div v-if="modelsError" class="ui-alert ui-alert--error">{{ modelsError }}</div>
      <div class="next-providers__probe">
        <UiButton
          variant="secondary"
          size="sm"
          :loading="probing"
          data-testid="product-probe"
          @click="probeModels"
          >探测模型</UiButton
        >
        <span
          v-if="probeStatus?.probedAt"
          class="next-providers__probe-status"
          data-testid="product-probe-status"
        >
          <template v-if="probeStatus.status === 'SUCCEEDED'">
            上次探测成功：{{ probeStatus.modelCount }} 个模型（{{
              probeStatus.probedAt.slice(0, 16).replace('T', ' ')
            }}）
          </template>
          <template v-else>
            上次探测失败：{{ probeStatus.error || '未知原因' }}（{{
              probeStatus.probedAt.slice(0, 16).replace('T', ' ')
            }}）
          </template>
        </span>
      </div>
      <div v-if="probeError" class="ui-alert ui-alert--error" data-testid="product-probe-error">
        {{ probeError }}
      </div>
      <div v-if="modelsLoading" class="ui-panel-sub">加载中…</div>
      <div v-else class="next-providers__model-list" data-testid="product-models-list">
        <div v-for="m in models" :key="m.id" class="next-providers__model-row">
          <div class="next-providers__model-info">
            <span class="ui-mono">{{ m.modelId }}</span>
            <span class="next-providers__balance">{{ m.displayName || '—' }}</span>
          </div>
          <UiStatusBadge
            :tone="m.source === 'MANUAL' ? 'warning' : 'success'"
            :label="m.source === 'MANUAL' ? '人工' : '官方'"
          />
          <UiButton
            variant="link"
            size="sm"
            :data-testid="`product-model-testrun-${m.modelId}`"
            @click="openTestRun(m.modelId ?? '')"
            >试调</UiButton
          >
          <UiButton
            v-if="m.source === 'MANUAL'"
            variant="link-danger"
            size="sm"
            :data-testid="`product-model-delete-${m.modelId}`"
            @click="removeManualModel(m)"
            >删除</UiButton
          >
        </div>
        <p v-if="!models.length" class="next-providers__empty">
          暂无目录模型。探测失败时可在此手工补录。
        </p>
      </div>
      <div class="next-providers__model-form" data-testid="product-models-form">
        <div v-if="modelError" class="ui-alert ui-alert--error">{{ modelError }}</div>
        <div class="next-providers__model-form-row">
          <UiInput
            v-model="modelForm.modelId"
            label="模型 ID"
            placeholder="manual-fallback-model"
            data-testid="product-models-id"
          />
          <UiInput
            v-model="modelForm.displayName"
            label="显示名（可选）"
            data-testid="product-models-name"
          />
          <UiButton
            variant="secondary"
            :loading="modelSaving"
            data-testid="product-models-add"
            @click="addManualModel"
            >录入人工模型</UiButton
          >
        </div>
      </div>
      <template #footer>
        <UiButton variant="secondary" @click="modelsVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

    <!-- #552 model test-run (console 在线调试) -->
    <UiDialog
      :open="testRunVisible"
      :title="`试调 · ${testRunModelId}`"
      width="620px"
      data-testid="model-testrun-dialog"
      @update:open="testRunVisible = false"
    >
      <p class="ui-panel-sub">
        以该产品首个 ACTIVE 凭证向上游发一条真实消息（≤256 token
        预算；正文不落库、不入日志，审计仅记元数据）。
      </p>
      <UiInput
        v-model="testRunPrompt"
        label="消息（留空使用默认「请回复OK」）"
        data-testid="model-testrun-prompt"
      />
      <div v-if="testRunError" class="ui-alert ui-alert--error" data-testid="model-testrun-error">
        {{ testRunError }}
      </div>
      <div
        v-if="testRunResult"
        class="next-providers__testrun-result"
        data-testid="model-testrun-result"
      >
        <div class="next-providers__testrun-meta">
          HTTP {{ testRunResult.httpStatus }} · {{ testRunResult.latencyMs }} ms<template
            v-if="testRunResult.totalTokens != null"
          >
            · tokens {{ testRunResult.totalTokens }}</template
          >
        </div>
        <pre class="next-providers__testrun-content">{{
          testRunResult.content || '（空回复）'
        }}</pre>
      </div>
      <template #footer>
        <UiButton variant="secondary" @click="testRunVisible = false">关闭</UiButton>
        <UiButton
          variant="primary"
          :loading="testRunning"
          data-testid="model-testrun-run"
          @click="runTestRun"
          >发送试调</UiButton
        >
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-providers__muted {
  color: var(--ui-foreground-faint);
}

.next-providers__provider {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-providers__name {
  font-weight: var(--ui-weight-medium);
}

.next-providers__code {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-providers__balance {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-providers__model-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.next-providers__model-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.next-providers__model-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.next-providers__model-form-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.next-providers__actions {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-1);
}
.next-providers__empty {
  color: var(--ui-foreground-faint);
  font-size: var(--ui-font-size-sm);
}

.next-providers__probe {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-3);
  flex-wrap: wrap;
}

.next-providers__probe-status {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-providers__testrun-result {
  margin-top: var(--ui-space-3);
}

.next-providers__testrun-meta {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-faint);
  margin-bottom: var(--ui-space-2);
}

.next-providers__testrun-content {
  margin: 0;
  padding: var(--ui-space-3);
  background: var(--ui-surface-sunken, #f6f7f9);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}
</style>
