<script setup lang="ts">
/**
 * NextGrantsView — /app/grants v2 admin page (U2 org batch).
 * Behaviour parity with the legacy grants page plus name resolution: rows
 * show project / credential / product display names instead of raw UUID
 * prefixes (same endpoints, no API change). Create form, model-scope drawer
 * (one model per line, replace-all on save) and disable gate included.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { Grant, Project } from '@/types/api';

interface CredentialOption {
  id: string;
  name: string;
  subscriptionId: string;
}

interface ProductOption {
  id: string;
  displayName: string;
  productCode: string;
  providerName: string;
}

const grants = ref<Grant[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projects = ref<Project[]>([]);
const credentials = ref<CredentialOption[]>([]);
const products = ref<ProductOption[]>([]);

const creating = ref(false);
const form = ref({ projectId: '', providerProductId: '', credentialId: '', models: '' });
const formError = ref('');
const submitting = ref(false);

const modelsOpen = ref(false);
const modelsGrant = ref<Grant | null>(null);
const modelsText = ref('');
const modelsSaving = ref(false);
const modelsError = ref('');

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const projectOptions = computed<UiSelectOption[]>(() =>
  projects.value.map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` })),
);

const credentialOptions = computed<UiSelectOption[]>(() =>
  credentials.value.map((c) => ({ value: c.id, label: c.name })),
);

const productOptions = computed<UiSelectOption[]>(() =>
  products.value.map((p) => ({
    value: p.id,
    label: `${p.providerName} · ${p.displayName}（${p.productCode}）`,
  })),
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
  const [projectList, credentialList, productList] = await Promise.all([
    api.listProjects(),
    api.listCredentials(),
    api.listProviderProducts(),
  ]);
  projects.value = projectList;
  credentials.value = credentialList as CredentialOption[];
  products.value = productList as ProductOption[];
}

async function createGrant() {
  if (!form.value.projectId || !form.value.credentialId) {
    formError.value = '请选择项目与凭证。';
    return;
  }
  submitting.value = true;
  try {
    await api.createGrant({
      projectId: form.value.projectId,
      providerProductId: form.value.providerProductId,
      credentialId: form.value.credentialId,
      models: parseModels(form.value.models),
    });
    creating.value = false;
    form.value = { projectId: '', providerProductId: '', credentialId: '', models: '' };
    toast.success('Grant 已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

function parseModels(text: string): string[] {
  return text
    .split(/[,，\n]/)
    .map((m) => m.trim())
    .filter(Boolean);
}

async function openModels(grant: Grant) {
  modelsGrant.value = grant;
  modelsError.value = '';
  try {
    modelsText.value = (await api.grantModels(grant.id)).join('\n');
    modelsOpen.value = true;
  } catch {
    toast.error('加载模型范围失败');
  }
}

async function saveModels() {
  if (!modelsGrant.value) return;
  modelsSaving.value = true;
  modelsError.value = '';
  try {
    await api.updateGrantModels(modelsGrant.value.id, parseModels(modelsText.value));
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
    title: '禁用 Grant',
    body: '禁用后该 Grant 不再授权任何 Virtual Key，关联 Key 将无法通过此授权路由。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.disableGrant(grant.id);
        toast.success('Grant 已禁用');
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
          {{ creating ? '收起表单' : '创建 Grant' }}
        </UiButton>
      </div>
    </header>

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
          <UiSelect
            v-model="form.credentialId"
            label="上游凭证"
            required
            placeholder="选择凭证"
            :options="credentialOptions"
            width="100%"
            data-testid="grant-create-credential"
          />
          <UiSelect
            v-model="form.providerProductId"
            label="供应商产品（可选）"
            placeholder="选择产品实例"
            :options="productOptions"
            width="100%"
            data-testid="grant-create-product"
          />
          <div class="ui-field">
            <span class="ui-field__label">模型范围（每行一个，选填）</span>
            <textarea
              v-model="form.models"
              class="ui-textarea"
              rows="4"
              placeholder="例如 claude-3-7-sonnet"
              data-testid="grant-create-models"
            />
          </div>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-grants__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
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
        <span class="ui-panel-sub">共 {{ grants.length }} 条授权</span>
      </div>
      <UiTable
        :columns="columns"
        :data="grants"
        :loading="loading"
        row-key="id"
        empty-title="还没有授权"
        data-testid="grants-table"
      >
        <template #project="{ row }">
          <span class="next-grants__name">{{ nameOf.project((row as Grant).projectId) }}</span>
        </template>
        <template #credential="{ row }">
          <span class="next-grants__name">{{
            nameOf.credential((row as Grant).upstreamCredentialId)
          }}</span>
        </template>
        <template #product="{ row }">
          <span class="next-grants__name">{{
            nameOf.product((row as Grant).providerProductId)
          }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as Grant).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="
              (row as Grant).status === 'ACTIVE'
                ? '正常'
                : (row as Grant).status === 'EXPIRED'
                  ? '已过期'
                  : '停用'
            "
          />
        </template>
        <template #actions="{ row }">
          <div class="next-grants__actions-cell">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="grant-models-open"
              @click="openModels(row as Grant)"
            >
              模型
            </UiButton>
            <UiButton
              v-if="(row as Grant).status === 'ACTIVE'"
              variant="ghost"
              size="sm"
              class="next-grants__danger"
              data-testid="grant-disable"
              @click="requestDisable(row as Grant)"
            >
              禁用
            </UiButton>
          </div>
        </template>
      </UiTable>
    </section>

    <UiDrawer
      :open="modelsOpen"
      :title="`模型范围${modelsGrant ? '：' + nameOf.product(modelsGrant.providerProductId) : ''}`"
      width="560px"
      data-testid="grant-models-drawer"
      @close="modelsOpen = false"
    >
      <p class="next-grants__hint">每行一个模型 ID；保存会整体替换当前范围。</p>
      <textarea
        v-model="modelsText"
        class="ui-textarea next-grants__models-input"
        rows="14"
        data-testid="grant-models-input"
      />
      <p v-if="modelsError" class="ui-form-error">{{ modelsError }}</p>
      <template #footer>
        <UiButton variant="ghost" @click="modelsOpen = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="modelsSaving"
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

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-textarea {
  width: 100%;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-grants__models-input {
  font-family: var(--ui-font-mono);
  font-size: var(--ui-font-size-xs);
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

.next-grants__danger {
  color: var(--ui-danger-fg);
}

.next-grants__hint {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
