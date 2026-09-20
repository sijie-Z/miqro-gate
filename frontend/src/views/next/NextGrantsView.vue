<script setup lang="ts">
/**
 * NextGrantsView — /app/grants v2 admin page (U2 org batch).
 * Behaviour parity with the legacy grants page plus name resolution: rows
 * show project / credential / product display names instead of raw UUID
 * prefixes (same endpoints, no API change).
 *
 * Issue #571: the create form derives the provider product from the selected
 * credential's subscription (the backend enforces the same consistency,
 * #498) instead of offering it as a free "optional" choice, and the model
 * scope is picked from the product's model catalog (ModelScopePicker) rather
 * than typed as free text.
 *
 * Issue #657: the credentials list deep-links here with `?credentialId=…`;
 * the query is a real filter (not a decorative parameter), is announced in
 * the toolbar and is one click away from being cleared.
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiPageGuide,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type { UiSelectOption } from '@/ui';
import ModelScopePicker from '@/components/ModelScopePicker.vue';
import ProviderBrandChip from '@/components/ProviderBrandChip.vue';
import { GRANTS_GUIDE } from '@/content/pageGuides';
import type { Grant, Project, SubscriptionView } from '@/types/generated-api';

interface CredentialOption {
  id: string;
  name: string;
  subscriptionId: string;
  status?: string;
}

interface ProductOption {
  id: string;
  displayName: string;
  productCode: string;
  providerName: string;
  providerSlug?: string;
}

const route = useRoute();

const grants = ref<Grant[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projects = ref<Project[]>([]);
const credentials = ref<CredentialOption[]>([]);
const products = ref<ProductOption[]>([]);
const subscriptions = ref<SubscriptionView[]>([]);

const creating = ref(false);
const form = ref({ projectId: '', credentialId: '', models: [] as string[] });
const formError = ref('');
const submitting = ref(false);

const modelsOpen = ref(false);
const modelsGrant = ref<Grant | null>(null);
const modelsScope = ref<string[]>([]);
const modelsScopeLoading = ref(false);
const modelsSaving = ref(false);
const modelsError = ref('');
// #440: request-sequence guard — a slow load for grant A must never land in the
// drawer after the user has re-targeted it at grant B (replace-all save would
// otherwise write A's model list into B's scope).
let modelsRequestSeq = 0;

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

// listProjects rows always include id (hub Project fields are all optional) —
// the `!` restores the pre-hub required-field contract.
const projectOptions = computed<UiSelectOption[]>(() =>
  projects.value.map((p) => ({ value: p.id!, label: `${p.code} · ${p.name}` })),
);

const productById = computed(() => new Map(products.value.map((p) => [p.id, p])));
const productLabel = computed(() => {
  const map = productById.value;
  return (productId: string | undefined) => {
    const product = productId ? map.get(productId) : undefined;
    return product ? `${product.providerName} · ${product.displayName}` : '';
  };
});

/** subscription id → owning provider product id (grants derive the product). */
const subscriptionProductId = computed(
  () => new Map(subscriptions.value.map((s) => [s.id!, s.providerProductId!])),
);

/** The product a grant on this credential would carry; '' = not resolvable. */
const formProductId = computed(() => {
  const credential = credentials.value.find((c) => c.id === form.value.credentialId);
  if (!credential) return '';
  return subscriptionProductId.value.get(credential.subscriptionId) ?? '';
});

const formProduct = computed(() => productById.value.get(formProductId.value));

const credentialOptions = computed<UiSelectOption[]>(() =>
  credentials.value.map((c) => {
    const product = productLabel.value(subscriptionProductId.value.get(c.subscriptionId));
    const parts = [product || '订阅信息不可用'];
    if (c.status && c.status !== 'ACTIVE') parts.push('已停用');
    return { value: c.id, label: c.name, hint: parts.join(' · ') };
  }),
);

// The backend answers 409 GRANT_EXISTS for a duplicate triple — even when the
// existing grant is disabled (the existence check ignores status). Surface it
// before submitting; the 409 stays as the backstop for stale lists.
const duplicateGrant = computed(
  () =>
    Boolean(form.value.projectId && form.value.credentialId && formProductId.value) &&
    grants.value.some(
      (g) =>
        g.projectId === form.value.projectId &&
        g.upstreamCredentialId === form.value.credentialId &&
        g.providerProductId === formProductId.value,
    ),
);

const canSubmit = computed(
  () =>
    Boolean(form.value.projectId && form.value.credentialId && formProductId.value) &&
    !duplicateGrant.value,
);

const nameOf = computed(() => {
  const projectMap = new Map(projects.value.map((p) => [p.id, `${p.code} · ${p.name}`]));
  const credentialMap = new Map(credentials.value.map((c) => [c.id, c.name]));
  const productMap = new Map(
    products.value.map((p) => [p.id, `${p.providerName} · ${p.displayName}`]),
  );
  return {
    project(id: string) {
      return projectMap.get(id) ?? id.slice(0, 8) + '…';
    },
    credential(id: string) {
      return credentialMap.get(id) ?? id.slice(0, 8) + '…';
    },
    product(id: string) {
      return productMap.get(id) ?? id.slice(0, 8) + '…';
    },
  };
});

const columns = [
  { key: 'project', title: '项目', minWidth: '180px' },
  { key: 'credential', title: '上游凭证', minWidth: '200px' },
  { key: 'product', title: '供应商产品', minWidth: '220px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'actions', title: '操作', width: '150px' },
];

/** #657: credential id arriving from the credentials list' dependency count. */
const credentialFilter = computed(() => {
  const raw = route.query.credentialId;
  // A duplicated query param (`?credentialId=a&credentialId=b`) arrives as an
  // array; treating that as "no filter" would show the full list while the URL
  // still advertises one, so take the first value the URL asked for.
  const value = Array.isArray(raw) ? raw[0] : raw;
  return typeof value === 'string' ? value : '';
});

const filteredGrants = computed(() =>
  credentialFilter.value
    ? grants.value.filter((g) => g.upstreamCredentialId === credentialFilter.value)
    : grants.value,
);

/** #657: the credential-scoped empty copy is a claim about loaded data. After a
 *  failed fetch there is nothing to claim, so the generic title stays; the failure
 *  is reported by its own alert, and this table still renders below it. */
const scopedFilter = computed(() => Boolean(credentialFilter.value) && !loadError.value);

async function load() {
  loading.value = true;
  try {
    grants.value = await api.listGrants();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function loadOptions() {
  const [projectList, credentialList, productList, subscriptionList] = await Promise.all([
    api.listProjects(),
    api.listCredentials(),
    api.listProviderProducts(),
    api.listSubscriptions(),
  ]);
  projects.value = projectList;
  credentials.value = credentialList as CredentialOption[];
  products.value = productList as ProductOption[];
  subscriptions.value = subscriptionList;
}

async function createGrant() {
  if (!form.value.projectId || !form.value.credentialId) {
    formError.value = '请选择项目与上游凭证。';
    return;
  }
  if (!formProductId.value) {
    formError.value = '无法解析该凭证所属的供应商产品（订阅缺失），请检查凭证配置。';
    return;
  }
  if (duplicateGrant.value) {
    formError.value = '该项目已存在同凭证、同产品的授权。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.createGrant({
      projectId: form.value.projectId,
      providerProductId: formProductId.value,
      credentialId: form.value.credentialId,
      models: form.value.models,
    });
    creating.value = false;
    form.value = { projectId: '', credentialId: '', models: [] };
    toast.success('授权已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function openModels(grant: Grant) {
  const seq = ++modelsRequestSeq;
  modelsGrant.value = grant;
  modelsScope.value = [];
  modelsError.value = '';
  modelsScopeLoading.value = true;
  modelsOpen.value = true;
  try {
    const models = await api.grantModels(grant.id!); // list rows always carry ids
    if (seq !== modelsRequestSeq) {
      return; // a newer drawer target won — this response is stale
    }
    modelsScope.value = models;
  } catch {
    if (seq === modelsRequestSeq) {
      modelsOpen.value = false;
      toast.error('加载模型范围失败');
    }
  } finally {
    if (seq === modelsRequestSeq) {
      modelsScopeLoading.value = false;
    }
  }
}

async function saveModels() {
  const target = modelsGrant.value;
  if (!target) return;
  modelsSaving.value = true;
  modelsError.value = '';
  try {
    await api.updateGrantModels(target.id!, modelsScope.value); // drawer row carries id
    toast.success('模型范围已更新');
    modelsOpen.value = false;
  } catch (error) {
    modelsError.value = error instanceof ApiError ? error.message : '保存失败';
  } finally {
    modelsSaving.value = false;
  }
}

function requestDisable(grant: Grant) {
  confirmState.value = {
    title: '禁用授权',
    body: '禁用后该授权不再对任何虚拟密钥生效，关联密钥将无法通过此授权路由。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.disableGrant(grant.id!); // list rows always carry ids
        toast.success('授权已禁用');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

async function confirmAndRun() {
  const state = confirmState.value;
  if (!state) return;
  confirmState.value = null;
  await state.run();
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()]);
});
</script>

<template>
  <div class="ui-page next-grants">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">授权</h1>
        <p class="ui-page-desc">项目 × 上游凭证 × 供应商产品的授权组合与模型范围。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="grant-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建授权' }}
        </UiButton>
      </div>
    </header>

    <UiPageGuide :guide="GRANTS_GUIDE" storage-key="grants" />

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-grants__create" data-testid="grant-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建 Grant</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-grants__form">
          <UiSelect
            v-model="form.projectId"
            label="项目"
            required
            placeholder="选择项目"
            :options="projectOptions"
            width="100%"
            data-testid="grant-create-project"
          />
          <div class="next-grants__field">
            <UiSelect
              v-model="form.credentialId"
              label="上游凭证"
              required
              placeholder="选择凭证"
              :options="credentialOptions"
              width="100%"
              data-testid="grant-create-credential"
            />
            <p class="next-grants__hint">
              网关代该项目调用上游时使用的真实供应商 Key（已加密保存，永不回显）。
            </p>
          </div>
          <div v-if="form.credentialId" class="next-grants__field">
            <span class="next-grants__field-label">供应商产品</span>
            <div class="next-grants__derived" data-testid="grant-create-product">
              <template v-if="formProduct">
                <ProviderBrandChip
                  :slug="formProduct.providerSlug"
                  :name="formProduct.providerName"
                  size="sm"
                />
                <span class="next-grants__derived-name">
                  {{ formProduct.providerName }} · {{ formProduct.displayName }}
                </span>
                <span class="ui-mono next-grants__derived-code">{{ formProduct.productCode }}</span>
              </template>
              <span v-else class="next-grants__derived-missing">
                无法解析该凭证所属的供应商产品（订阅缺失）。
              </span>
            </div>
            <p class="next-grants__hint">由所选凭证的订阅自动确定，无需手选。</p>
          </div>
          <div v-if="formProductId" class="next-grants__field">
            <span class="next-grants__field-label">模型范围</span>
            <p class="next-grants__hint next-grants__hint--lead">
              该项目通过此授权可调用的模型；默认勾选该产品目录全部模型，可取消收窄。
            </p>
            <ModelScopePicker
              v-model="form.models"
              :product-id="formProductId"
              default-all
              data-testid="grant-create-models"
            />
            <p
              v-if="!form.models.length"
              class="next-grants__scope-warning"
              data-testid="grant-create-models-empty"
            >
              未勾选任何模型：该授权暂不含任何模型，经此授权的 Key
              将无法调用（可由模型审批逐条加入）。
            </p>
          </div>
          <p
            v-if="duplicateGrant"
            class="next-grants__scope-warning"
            data-testid="grant-create-duplicate"
          >
            该项目已存在同凭证、同产品的授权（含已停用），不可重复创建。
          </p>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-grants__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              :disabled="!canSubmit"
              data-testid="grant-create-submit"
              @click="createGrant"
            >
              创建 Grant
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">
          共 {{ filteredGrants.length }} 条授权<template v-if="credentialFilter"
            >（全部 {{ grants.length }} 条）</template
          >
        </span>
        <span v-if="credentialFilter" class="next-grants__filter" data-testid="grants-filter">
          仅看凭证「{{ nameOf.credential(credentialFilter) }}」
          <router-link
            class="ui-link-action"
            :to="{ name: 'grants' }"
            data-testid="grants-filter-clear"
          >
            查看全部
          </router-link>
        </span>
      </div>
      <UiTable
        :columns="columns"
        :data="filteredGrants"
        :loading="loading"
        row-key="id"
        :empty-title="scopedFilter ? '该凭证还没有被任何授权引用' : '还没有授权'"
        :empty-action-label="scopedFilter ? '查看全部授权' : ''"
        :empty-action-to="{ name: 'grants' }"
        data-testid="grants-table"
        :error="loadError"
        @retry="load"
      >
        <template #project="{ row }">
          <span class="next-grants__name">{{
            nameOf.project((row as unknown as Grant).projectId!)
          }}</span>
        </template>
        <template #credential="{ row }">
          <span class="next-grants__name">{{
            nameOf.credential((row as unknown as Grant).upstreamCredentialId!)
          }}</span>
        </template>
        <template #product="{ row }">
          <span class="next-grants__name">{{
            nameOf.product((row as unknown as Grant).providerProductId!)
          }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as unknown as Grant).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="
              (row as unknown as Grant).status === 'ACTIVE'
                ? '正常'
                : (row as unknown as Grant).status === 'EXPIRED'
                  ? '已过期'
                  : '停用'
            "
          />
        </template>
        <template #actions="{ row }">
          <div class="next-grants__actions-cell">
            <UiButton
              variant="link"
              size="sm"
              data-testid="grant-models-open"
              @click="openModels(row as unknown as Grant)"
            >
              模型
            </UiButton>
            <UiButton
              v-if="(row as unknown as Grant).status === 'ACTIVE'"
              variant="link-danger"
              size="sm"
              data-testid="grant-disable"
              @click="requestDisable(row as unknown as Grant)"
            >
              禁用
            </UiButton>
          </div>
        </template>
      </UiTable>
    </section>

    <UiDrawer
      :open="modelsOpen"
      :title="`模型范围${modelsGrant ? '：' + nameOf.product(modelsGrant.providerProductId!) : ''}`"
      width="560px"
      data-testid="grant-models-drawer"
      @close="modelsOpen = false"
    >
      <div v-if="modelsScopeLoading" class="next-grants__hint" data-testid="grant-models-loading">
        正在加载模型范围…
      </div>
      <template v-else-if="modelsGrant">
        <p class="next-grants__hint">勾选该项目可通过此授权使用的模型；保存会整体替换当前范围。</p>
        <ModelScopePicker
          :key="modelsGrant.id"
          v-model="modelsScope"
          :product-id="modelsGrant.providerProductId ?? ''"
          data-testid="grant-models-picker"
        />
      </template>
      <p v-if="modelsError" class="ui-form-error">{{ modelsError }}</p>
      <template #footer>
        <UiButton variant="ghost" @click="modelsOpen = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="modelsSaving"
          :disabled="modelsScopeLoading"
          data-testid="grant-models-save"
          @click="saveModels"
        >
          保存
        </UiButton>
      </template>
    </UiDrawer>

    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="440px"
      @update:open="confirmState = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="confirmState = null">取消</UiButton>
        <UiButton
          :variant="confirmState.tone === 'danger' ? 'danger' : 'primary'"
          @click="confirmAndRun"
        >
          {{ confirmState.confirmLabel }}
        </UiButton>
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

.next-grants__create {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-grants__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 560px;
}

.next-grants__field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.next-grants__field-label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.next-grants__derived {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  min-height: var(--ui-control-height);
  padding: 0 var(--ui-space-3);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
}

.next-grants__derived-name {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
}

.next-grants__derived-code {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-grants__derived-missing {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-warning-fg);
}

.next-grants__scope-warning {
  margin: 0;
  padding: var(--ui-space-2) var(--ui-space-3);
  border-radius: var(--ui-radius-control);
  background: var(--ui-warning-bg);
  color: var(--ui-warning-fg);
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-sm);
}

.next-grants__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-grants__actions-cell {
  display: inline-flex;
  gap: var(--ui-space-1);
  justify-content: flex-start;
}

.next-grants__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-sm);
}

.next-grants__hint--lead {
  margin-bottom: var(--ui-space-1);
}

/* #657 filter chip: the count stays left, the active filter sits right. */
.next-grants__filter {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-1);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
