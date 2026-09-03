<script setup lang="ts">
/**
 * NextKeysView — /app-new/keys pilot page (UI U0, PostHog language).
 * Behaviour parity with the legacy KeysView (keys list + single-page create
 * flow + rotate/revoke + one-shot secret), rendered on the v2 component set.
 * APIs and route semantics are untouched.
 */
import { computed, onMounted, ref } from 'vue';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuItemIndicator,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from 'radix-vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiEmptyState,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {
  CreateVirtualKeyResponse,
  MeGrantsResponse,
  VirtualKeyPurpose,
  VirtualKeyView,
} from '@/types/api';

const keys = ref<VirtualKeyView[]>([]);
const grants = ref<MeGrantsResponse | null>(null);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const submitting = ref(false);
const createName = ref('');
const createProjectId = ref('');
const createGrantId = ref('');
const createPurpose = ref<VirtualKeyPurpose>('CLAUDE_CODE');
const createModels = ref<string[]>([]);
const createCachePolicy = ref<'DISABLED' | 'ENABLED'>('DISABLED');
const formError = ref('');
const formRequestId = ref('');

// ---- one-shot secret reveal (create / rotate result) ----
const revealOpen = ref(false);
const revealData = ref<CreateVirtualKeyResponse | null>(null);
const revealAcked = ref(false);
const revealCopied = ref(false);

// ---- confirm gate (replaces TDesign confirmDialog for this page) ----
const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const purposeLabel: Record<string, string> = {
  CLAUDE_CODE: 'Claude Code',
  CLAUDE_DESKTOP: 'Claude Desktop',
  CODEX: 'Codex',
  CUSTOM: '自定义',
};

const statusLabel: Record<string, string> = {
  ACTIVE: '可用',
  ROTATING: '轮换中',
  REVOKED: '已吊销',
  DISABLED: '停用',
};

const purposeOptions = computed<UiSelectOption[]>(() => {
  const available = grants.value?.purposes.length
    ? grants.value.purposes
    : (Object.keys(purposeLabel) as VirtualKeyPurpose[]);
  return available.map((p) => ({ value: p, label: purposeLabel[p] ?? p }));
});

const columns = [
  { key: 'name', title: '名称', minWidth: '220px', sortable: true },
  { key: 'projectTag', title: '项目', width: '120px' },
  { key: 'purpose', title: '用途', width: '130px' },
  { key: 'modelIds', title: '允许模型', minWidth: '220px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'cachePolicy', title: '缓存', width: '90px' },
  { key: 'createdAt', title: '创建时间', width: '170px', sortable: true },
  { key: 'actions', title: '操作', width: '80px', align: 'center' },
];

const keyFilter = ref('');

const filteredKeys = computed(() => {
  const q = keyFilter.value.trim().toLowerCase();
  if (!q) return keys.value;
  return keys.value.filter(
    (k) =>
      k.name.toLowerCase().includes(q) ||
      k.projectTag.toLowerCase().includes(q) ||
      k.display.toLowerCase().includes(q),
  );
});

const keySummary = computed<{ text: string; tone: 'plain' | 'success' | 'warning' | 'danger' }[]>(
  () => {
    const active = keys.value.filter((k) => k.status === 'ACTIVE').length;
    const rotating = keys.value.filter((k) => k.status === 'ROTATING').length;
    const unusual = keys.value.filter(
      (k) => k.status === 'REVOKED' || k.status === 'DISABLED',
    ).length;
    const parts = [{ text: `共 ${keys.value.length} 个`, tone: 'plain' as const }];
    if (active) parts.push({ text: `${active} 可用`, tone: 'success' as const });
    if (rotating) parts.push({ text: `${rotating} 轮换中`, tone: 'warning' as const });
    if (unusual) parts.push({ text: `${unusual} 异常`, tone: 'danger' as const });
    return parts;
  },
);

/** Hide the project column while every key shares one project. */
const tableColumns = computed(() => {
  const distinctProjects = new Set(keys.value.map((k) => k.projectTag));
  if (distinctProjects.size <= 1 && keys.value.length > 0) {
    return columns.filter((c) => c.key !== 'projectTag');
  }
  return columns;
});

/** Registered-but-empty account: has the admin joined this account to a project yet? */
const hasNoProjects = computed(() => (grants.value?.projects.length ?? 0) === 0);

// ---- create form derived lists (identical semantics to legacy page) ----

const projectsForGrant = computed(() => {
  const list = grants.value?.projects ?? [];
  const used = new Set((grants.value?.grants ?? []).map((g) => g.projectId));
  return list.filter((p) => used.has(p.id));
});

const grantOptions = computed(
  () => (grants.value?.grants ?? []).filter((g) => g.projectId === createProjectId.value) ?? [],
);

const selectedGrant = computed(() => grantOptions.value.find((g) => g.id === createGrantId.value));

const modelOptions = computed(() => selectedGrant.value?.models ?? []);

const canCreate = computed(
  () =>
    createName.value.trim().length > 0 &&
    createProjectId.value !== '' &&
    createGrantId.value !== '' &&
    createModels.value.length > 0,
);

// ---- load ----

onMounted(load);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [keyList, grantList] = await Promise.all([api.listVirtualKeys(), api.myGrants()]);
    keys.value = keyList;
    grants.value = grantList;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载 Virtual Keys 失败。';
    }
  } finally {
    loading.value = false;
  }
}

// ---- create ----

function onProjectChange() {
  createGrantId.value = '';
  createModels.value = [];
}

function onGrantChange() {
  // Default to all models authorized for the grant.
  createModels.value = [...(selectedGrant.value?.models ?? [])];
}

function resetForm() {
  createName.value = '';
  createProjectId.value = '';
  createGrantId.value = '';
  createPurpose.value = 'CLAUDE_CODE';
  createModels.value = [];
  createCachePolicy.value = 'DISABLED';
  formError.value = '';
  formRequestId.value = '';
}

async function createKey() {
  formError.value = '';
  formRequestId.value = '';
  if (!selectedGrant.value || !createProjectId.value) {
    formError.value = '请选择项目与授权组合。';
    return;
  }
  submitting.value = true;
  try {
    const response = await api.createVirtualKey({
      name: createName.value.trim(),
      projectId: createProjectId.value,
      providerProductId: selectedGrant.value.providerProductId,
      credentialGrantId: createGrantId.value,
      purpose: createPurpose.value,
      allowedModels: createModels.value,
      cachePolicy: createCachePolicy.value,
    });
    resetForm();
    await load();
    toast.success('Virtual Key 已创建');
    openReveal(response);
  } catch (error) {
    if (error instanceof ApiError) {
      formError.value = error.message;
      formRequestId.value = error.requestId ?? '';
    } else {
      formError.value = '创建失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}

// ---- reveal (secret shown once) ----

function openReveal(response: CreateVirtualKeyResponse) {
  revealData.value = response;
  revealAcked.value = false;
  revealCopied.value = false;
  revealOpen.value = true;
}

async function copySecret() {
  if (!revealData.value) return;
  try {
    await navigator.clipboard.writeText(revealData.value.secret);
    revealCopied.value = true;
  } catch {
    toast.error('复制失败，请手动选择复制');
  }
}

// ---- row actions ----

async function handleRotate(key: VirtualKeyView) {
  confirmState.value = {
    title: `轮换 Virtual Key「${key.name}」`,
    body: '轮换后旧 Key 进入宽限期，宽限结束后失效。新 Key 仅在本次弹窗中显示一次。',
    confirmLabel: '轮换',
    tone: 'primary',
    run: async () => {
      try {
        const response = await api.rotateVirtualKey(key.id);
        await load();
        openReveal(response);
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

async function handleRevoke(key: VirtualKeyView) {
  confirmState.value = {
    title: `吊销 Virtual Key「${key.name}」`,
    body: '吊销后该 Key 立即失效，使用它的客户端将无法继续请求。此操作不可撤销。',
    confirmLabel: '吊销',
    tone: 'danger',
    run: async () => {
      try {
        await api.revokeVirtualKey(key.id);
        toast.success('Virtual Key 已吊销');
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

function formatDate(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'ROTATING':
      return 'warning';
    case 'REVOKED':
      return 'danger';
    default:
      return 'neutral';
  }
}
</script>

<template>
  <div class="ui-page next-keys">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">我的 Key</h1>
        <p class="ui-page-desc">通过 CC Switch 使用这些 Key 访问授权模型。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="create-key-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Virtual Key' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="keys-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <!-- Create flow — single-page form, dependent fields expand step by step -->
    <section v-if="creating" class="ui-panel next-keys__create" data-testid="create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建 Virtual Key</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-keys__create-grid">
          <UiInput
            v-model="createName"
            label="名称"
            required
            placeholder="例如 claude-code-main"
            data-testid="create-name"
          />
          <UiSelect
            v-model="createProjectId"
            label="项目"
            required
            placeholder="选择项目"
            :options="
              projectsForGrant.map((p) => ({ value: p.id, label: `${p.name}（${p.projectTag}）` }))
            "
            width="100%"
            data-testid="create-project"
            @change="onProjectChange"
          />
          <UiSelect
            v-if="createProjectId"
            v-model="createGrantId"
            label="供应商产品 / 授权"
            required
            placeholder="选择已授权的供应商产品"
            :options="
              grantOptions.map((g) => ({
                value: g.id,
                label: `${g.providerProductId}（${g.models.length} 个模型）`,
              }))
            "
            width="100%"
            data-testid="create-grant"
            @change="onGrantChange"
          />
          <div v-if="createGrantId" class="next-keys__field">
            <span class="next-keys__field-label">用途</span>
            <div
              class="next-keys__segmented"
              role="radiogroup"
              aria-label="用途"
              data-testid="create-purpose"
            >
              <label
                v-for="option in purposeOptions"
                :key="option.value"
                class="next-keys__seg"
                :class="{ 'next-keys__seg--on': createPurpose === option.value }"
              >
                <input
                  v-model="createPurpose"
                  type="radio"
                  name="purpose"
                  :value="option.value"
                  class="next-keys__seg-input"
                />
                <span>{{ option.label }}</span>
              </label>
            </div>
          </div>
          <div v-if="createGrantId" class="next-keys__field">
            <span class="next-keys__field-label">缓存策略</span>
            <div
              class="next-keys__segmented"
              role="radiogroup"
              aria-label="缓存策略"
              data-testid="create-cache-policy"
            >
              <label
                class="next-keys__seg"
                :class="{ 'next-keys__seg--on': createCachePolicy === 'DISABLED' }"
              >
                <input
                  v-model="createCachePolicy"
                  type="radio"
                  name="cache-policy"
                  value="DISABLED"
                  class="next-keys__seg-input"
                />
                <span>关闭（默认）</span>
              </label>
              <label
                class="next-keys__seg"
                :class="{ 'next-keys__seg--on': createCachePolicy === 'ENABLED' }"
              >
                <input
                  v-model="createCachePolicy"
                  type="radio"
                  name="cache-policy"
                  value="ENABLED"
                  class="next-keys__seg-input"
                />
                <span>开启</span>
              </label>
            </div>
            <p class="next-keys__field-hint">
              开启后网关会缓存该 Key 的响应（需客户端声明 X-MiQroKey-Cacheable:
              1；工具调用永不缓存）。
            </p>
          </div>
          <div v-if="createGrantId" class="next-keys__field">
            <span class="next-keys__field-label">允许模型（已默认勾选授权全集）</span>
            <div class="next-keys__model-list" data-testid="create-models">
              <label
                v-for="model in modelOptions"
                :key="model"
                class="next-keys__model"
                :class="{ 'next-keys__model--on': createModels.includes(model) }"
              >
                <input
                  v-model="createModels"
                  type="checkbox"
                  :value="model"
                  class="next-keys__model-input"
                />
                <svg
                  class="next-keys__model-check"
                  width="12"
                  height="12"
                  viewBox="0 0 16 16"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M3.5 8.5 6.5 11.5 12.5 4.5"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
                <span class="ui-mono">{{ model }}</span>
              </label>
            </div>
          </div>
          <p v-if="formError" class="ui-form-error" data-testid="create-error">
            {{ formError
            }}<span v-if="formRequestId" class="ui-request-id">
              requestId: {{ formRequestId }}</span
            >
          </p>
          <div class="next-keys__form-actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="create-submit"
              @click="createKey"
            >
              创建 Virtual Key
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <!-- Key list -->
    <section class="ui-panel">
      <div class="ui-panel-head next-keys__list-head">
        <div class="next-keys__list-title">
          <h2 class="ui-panel-title">Virtual Key</h2>
          <span class="ui-panel-sub next-keys__summary" data-testid="keys-summary">
            <span
              v-for="part in keySummary"
              :key="part.text"
              :class="`next-keys__summary-${part.tone}`"
              >{{ part.text }}</span
            >
          </span>
        </div>
        <UiInput
          v-model="keyFilter"
          placeholder="按名称 / 项目 / Key 前缀过滤"
          width="240px"
          data-testid="keys-filter"
        />
      </div>
      <UiTable
        :columns="tableColumns"
        :data="filteredKeys"
        :loading="loading"
        row-key="id"
        empty-title="还没有 Virtual Key"
        data-testid="keys-table"
      >
        <template #name="{ row }">
          <div class="next-keys__name">{{ (row as VirtualKeyView).name }}</div>
          <div class="ui-mono next-keys__mask">{{ (row as VirtualKeyView).display }}</div>
        </template>
        <template #purpose="{ row }">{{
          purposeLabel[(row as VirtualKeyView).purpose] ?? (row as VirtualKeyView).purpose
        }}</template>
        <template #modelIds="{ row }">
          <div class="ui-mono next-keys__models">
            {{ (row as VirtualKeyView).modelIds.join(', ') }}
          </div>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="statusTone((row as VirtualKeyView).status)"
            :label="statusLabel[(row as VirtualKeyView).status] ?? (row as VirtualKeyView).status"
          />
        </template>
        <template #cachePolicy="{ row }">
          <span
            :class="
              (row as VirtualKeyView).cachePolicy === 'ENABLED'
                ? 'next-keys__cache next-keys__cache--on'
                : 'next-keys__cache'
            "
            >{{ (row as VirtualKeyView).cachePolicy === 'ENABLED' ? '开启' : '关闭' }}</span
          >
        </template>
        <template #createdAt="{ row }">{{
          formatDate((row as VirtualKeyView).createdAt)
        }}</template>
        <template #actions="{ row }">
          <DropdownMenuRoot>
            <DropdownMenuTrigger
              class="next-keys__kebab"
              aria-label="操作"
              :data-testid="`key-actions-${(row as VirtualKeyView).id}`"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <circle cx="3" cy="8" r="1.4" />
                <circle cx="8" cy="8" r="1.4" />
                <circle cx="13" cy="8" r="1.4" />
              </svg>
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent class="next-keys__menu" :side-offset="4" :align="'end'">
                <DropdownMenuItem
                  class="next-keys__menu-item"
                  :disabled="(row as VirtualKeyView).status !== 'ACTIVE'"
                  @select="handleRotate(row as VirtualKeyView)"
                >
                  <DropdownMenuItemIndicator class="next-keys__menu-ind" />
                  轮换
                </DropdownMenuItem>
                <DropdownMenuSeparator class="next-keys__menu-sep" />
                <DropdownMenuItem
                  class="next-keys__menu-item next-keys__menu-item--danger"
                  :disabled="!(row as VirtualKeyView).status.match(/^(ACTIVE|ROTATING)$/)"
                  @select="handleRevoke(row as VirtualKeyView)"
                >
                  <DropdownMenuItemIndicator class="next-keys__menu-ind" />
                  吊销
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </template>
        <template #empty>
          <div v-if="hasNoProjects" data-testid="onboard-no-project">
            <UiEmptyState title="等待管理员开通" description="你的账号还没有被加入任何项目。">
              <div class="next-keys__onboard-steps">
                <p>
                  <strong>1</strong> 请管理员在「用户管理 →
                  项目成员」中把你加入项目，并配置供应商授权（Grant）。
                </p>
                <p><strong>2</strong> 开通后点击下方按钮刷新，即可看到可用的项目与授权。</p>
              </div>
              <UiButton variant="secondary" data-testid="onboard-refresh" @click="load"
                >刷新检查</UiButton
              >
            </UiEmptyState>
          </div>
          <div v-else data-testid="onboard-has-project">
            <UiEmptyState
              title="还没有 Virtual Key"
              description="点击右上角「创建 Virtual Key」，用已授权的项目与供应商开始调用。"
            >
              <UiButton variant="primary" @click="creating = true">创建第一个 Key</UiButton>
            </UiEmptyState>
          </div>
        </template>
      </UiTable>
    </section>

    <!-- One-shot secret reveal -->
    <UiDialog
      v-if="revealData"
      :open="revealOpen"
      title="Secret 已生成，仅显示一次"
      description="请立即复制并保存到 CC Switch；关闭后无法再次查看明文。"
      width="520px"
      :dismissible="false"
      data-testid="secret-dialog"
      @update:open="revealAcked && (revealOpen = $event)"
    >
      <p class="next-keys__reveal-url">
        接入地址：<span class="ui-mono">{{ revealData.baseUrl }}</span>
      </p>
      <div class="next-keys__secret-box" data-testid="secret-value">
        <code>{{ revealData.secret }}</code>
      </div>
      <label class="next-keys__ack">
        <input
          v-model="revealAcked"
          type="checkbox"
          class="next-keys__ack-input"
          data-testid="secret-ack"
        />
        <span class="next-keys__ack-box" aria-hidden="true">
          <svg width="11" height="11" viewBox="0 0 16 16" fill="none">
            <path
              d="M3.5 8.5 6.5 11.5 12.5 4.5"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <span>我已保存该 Secret</span>
      </label>
      <template #footer>
        <UiButton variant="secondary" data-testid="secret-copy" @click="copySecret">
          {{ revealCopied ? '已复制' : '复制' }}
        </UiButton>
        <UiButton
          variant="primary"
          :disabled="!revealAcked"
          data-testid="secret-close"
          @click="revealOpen = false"
        >
          完成
        </UiButton>
      </template>
    </UiDialog>

    <!-- Action confirm gate -->
    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="460px"
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
  line-height: var(--ui-line-height-base);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-keys__create {
  margin-bottom: var(--ui-space-5);
  max-width: 860px;
}

.next-keys__create-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 760px;
}

.next-keys__field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.next-keys__field-label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.next-keys__field-hint {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  line-height: var(--ui-line-height-base);
}

.next-keys__segmented {
  display: inline-flex;
  width: fit-content;
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  overflow: hidden;
}

.next-keys__seg {
  display: inline-flex;
  align-items: center;
  padding: 0 var(--ui-space-3);
  height: var(--ui-control-height);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
  border-left: 1px solid var(--ui-input-border);
  user-select: none;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.next-keys__seg:first-child {
  border-left: none;
}

.next-keys__seg:hover {
  background: var(--ui-fill-hover);
}

.next-keys__seg--on {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-medium);
}

.next-keys__seg-input,
.next-keys__model-input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.next-keys__model-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

.next-keys__model {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
  user-select: none;
  transition:
    border-color var(--ui-ease),
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.next-keys__model:hover {
  border-color: var(--ui-border-strong);
}

.next-keys__model--on {
  border-color: var(--ui-primary);
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}

.next-keys__model-check {
  display: none;
  flex-shrink: 0;
}

.next-keys__model--on .next-keys__model-check {
  display: inline;
}

.next-keys__model-input:focus-visible + .next-keys__model-check {
  outline: none;
}

.next-keys__model:has(.next-keys__model-input:focus-visible) {
  box-shadow: var(--ui-shadow-focus);
}

.next-keys__form-actions {
  display: flex;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-2);
  grid-column: 1 / -1;
}

.next-keys__list-head {
  align-items: center;
}

.next-keys__list-title {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-3);
}

.next-keys__cache {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-neutral-fg);
}

.next-keys__cache--on {
  color: var(--ui-primary);
  font-weight: var(--ui-weight-medium);
}

.next-keys__summary {
  display: inline-flex;
  align-items: baseline;
  gap: var(--ui-space-3);
}

.next-keys__summary-success {
  color: var(--ui-success-fg);
}

.next-keys__summary-warning {
  color: var(--ui-warning-fg);
}

.next-keys__summary-danger {
  color: var(--ui-danger-fg);
}

.next-keys__name {
  font-weight: var(--ui-weight-semibold);
  line-height: var(--ui-line-height-lg);
}

.next-keys__mask {
  font-size: 11px;
  line-height: var(--ui-line-height-sm);
  color: var(--ui-foreground-faint);
  margin-top: 2px;
}

.next-keys__models {
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-sm);
  color: var(--ui-foreground-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 480px;
}

.next-keys__kebab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-keys__kebab:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.next-keys__kebab:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.next-keys__menu {
  min-width: 160px;
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  box-shadow: var(--ui-shadow-popper);
  padding: var(--ui-space-1);
  z-index: 2000;
}

.next-keys__menu-item {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
  outline: none;
}

.next-keys__menu-item[data-highlighted] {
  background: var(--ui-fill-hover);
}

.next-keys__menu-item[data-disabled] {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.next-keys__menu-item--danger {
  color: var(--ui-danger-fg);
}

.next-keys__menu-ind {
  display: none;
}

.next-keys__menu-sep {
  height: 1px;
  margin: var(--ui-space-1) 0;
  background: var(--ui-border-muted);
}

.next-keys__onboard-steps {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  text-align: left;
  max-width: 460px;
}

.next-keys__onboard-steps p {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-base);
}

.next-keys__onboard-steps strong {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: var(--ui-space-2);
  border-radius: 50%;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 11px;
}

.next-keys__reveal-url {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  word-break: break-all;
}

.next-keys__secret-box {
  padding: var(--ui-space-3) var(--ui-space-4);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  font-family: var(--ui-font-mono);
  font-size: var(--ui-font-size-base);
  line-height: var(--ui-line-height-lg);
  word-break: break-all;
  user-select: all;
}

.next-keys__ack {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-keys__ack-input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

.next-keys__ack-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--ui-input-border);
  border-radius: 4px;
  background: var(--ui-card);
  color: transparent;
  flex-shrink: 0;

  pointer-events: none;
}

.next-keys__ack-input:checked + .next-keys__ack-box {
  background: var(--ui-primary);
  border-color: var(--ui-primary);
  color: #fff;
}

.next-keys__ack:has(.next-keys__ack-input:focus-visible) .next-keys__ack-box {
  box-shadow: var(--ui-shadow-focus);
}
</style>
