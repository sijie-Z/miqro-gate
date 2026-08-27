<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { confirmDialog } from '@/utils/confirm';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import SecretRevealDialog from '@/components/SecretRevealDialog.vue';
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

// Create form
const creating = ref(false);
const submitting = ref(false);
const createName = ref('');
const createProjectId = ref('');
const createGrantId = ref('');
const createPurpose = ref<VirtualKeyPurpose>('CLAUDE_CODE');
const createModels = ref<string[]>([]);
const formError = ref('');
const formRequestId = ref('');

// Secret reveal (create or rotate result)
const reveal = ref(false);
const revealData = ref<CreateVirtualKeyResponse | null>(null);

const statusLabel: Record<string, string> = {
  ACTIVE: 'Active',
  ROTATING: 'Rotating',
  REVOKED: 'Revoked',
  DISABLED: 'Disabled',
};

function statusClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'mk-status--success';
    case 'ROTATING':
      return 'mk-status--warning';
    case 'REVOKED':
      return 'mk-status--neutral';
    default:
      return 'mk-status--danger';
  }
}

const keyColumns = [
  { colKey: 'name', title: '名称', minWidth: 180 },
  { colKey: 'projectTag', title: '项目', width: 140 },
  { colKey: 'purpose', title: '用途', width: 140 },
  { colKey: 'modelIds', title: '允许模型', minWidth: 200 },
  { colKey: 'status', title: '状态', width: 120 },
  { colKey: 'createdAt', title: '创建时间', width: 170 },
  { colKey: 'lastUsedAt', title: '最近使用', width: 170 },
  { colKey: 'actions', title: '操作', width: 90, fixed: 'right' },
];

async function handleCommand(command: string, row: VirtualKeyView) {
  if (command === 'rotate') {
    await rotateKey(row);
  } else if (command === 'revoke') {
    await revokeKey(row);
  }
}

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

const selectedProject = computed(() =>
  (grants.value?.projects ?? []).find((p) => p.id === createProjectId.value),
);

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

const keyStats = computed(() => ({
  total: keys.value.length,
  active: keys.value.filter((k) => k.status === 'ACTIVE').length,
  rotating: keys.value.filter((k) => k.status === 'ROTATING').length,
  revoked: keys.value.filter((k) => k.status === 'REVOKED').length,
}));

const canCreate = computed(
  () =>
    createName.value.trim().length > 0 &&
    createProjectId.value !== '' &&
    createGrantId.value !== '' &&
    createModels.value.length > 0,
);

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
  formError.value = '';
  formRequestId.value = '';
}

async function createKey() {
  formError.value = '';
  formRequestId.value = '';
  if (!selectedGrant.value || !selectedProject.value) {
    formError.value = '请选择项目与授权组合。';
    return;
  }
  try {
    const response = await api.createVirtualKey({
      name: createName.value.trim(),
      projectId: createProjectId.value,
      providerProductId: selectedGrant.value.providerProductId,
      credentialGrantId: createGrantId.value,
      purpose: createPurpose.value,
      allowedModels: createModels.value,
    });
    revealData.value = response;
    reveal.value = true;
    resetForm();
    await load();
    MessagePlugin.success('Virtual Key 已创建');
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

async function rotateKey(key: VirtualKeyView) {
  try {
    await confirmDialog({
      header: `轮换 Virtual Key「${key.name}」`,
      body: '轮换后旧 Key 进入宽限期并在宽限结束后失效，新 Key 仅在本次弹窗显示一次。',
      confirmBtn: '轮换',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return; // cancelled
  }
  try {
    const response = await api.rotateVirtualKey(key.id);
    revealData.value = response;
    reveal.value = true;
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function revokeKey(key: VirtualKeyView) {
  try {
    await confirmDialog({
      header: `吊销 Virtual Key「${key.name}」`,
      body: '吊销后该 Key 立即失效，使用它的客户端将无法继续请求。此操作不可撤销。',
      confirmBtn: '吊销',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return; // cancelled
  }
  try {
    await api.revokeVirtualKey(key.id);
    MessagePlugin.success('Virtual Key 已吊销');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

function formatTime(iso?: string): string {
  if (!iso) {
    return '—';
  }
  return new Date(iso).toLocaleString();
}
</script>

<template>
  <div class="keys-page">
    <PageHeader title="Virtual Keys" description="通过 CC Switch 使用这些 Key 访问授权模型。">
      <template #actions>
        <t-button theme="primary" data-testid="create-key-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Virtual Key' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert
      v-if="loadError"
      theme="error"
      :close-btn="false"
      class="block-alert"
      data-testid="keys-load-error"
    >
      <template #default>
        {{ loadError }}
        <span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
      </template>
    </t-alert>

    <!-- Create form (single page form; dependent fields expand step by step) -->
    <section v-if="creating" class="create-panel" data-testid="create-form">
      <h3 class="panel-title">创建 Virtual Key</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input
            v-model="createName"
            placeholder="例如 claude-code-main"
            data-testid="create-name"
          />
        </t-form-item>

        <t-form-item label="项目" required-mark>
          <t-select
            v-model="createProjectId"
            placeholder="选择项目"
            class="full-width"
            data-testid="create-project"
            @change="onProjectChange"
          >
            <t-option
              v-for="project in projectsForGrant"
              :key="project.id"
              :value="project.id"
              :label="`${project.name}（${project.projectTag}）`"
            />
          </t-select>
        </t-form-item>

        <t-form-item v-if="createProjectId" label="供应商产品 / 授权" required-mark>
          <t-select
            v-model="createGrantId"
            placeholder="选择已授权的供应商产品"
            class="full-width"
            data-testid="create-grant"
            @change="onGrantChange"
          >
            <t-option
              v-for="grant in grantOptions"
              :key="grant.id"
              :value="grant.id"
              :label="`${grant.providerProductId}（${grant.models.length} 个模型）`"
            />
          </t-select>
        </t-form-item>

        <t-form-item v-if="createGrantId" label="用途" required-mark>
          <t-select v-model="createPurpose" class="full-width" data-testid="create-purpose">
            <t-option value="CLAUDE_CODE" label="Claude Code" />
            <t-option value="CLAUDE_DESKTOP" label="Claude Desktop" />
            <t-option value="CODEX" label="Codex" />
            <t-option value="CUSTOM" label="自定义" />
          </t-select>
        </t-form-item>

        <t-form-item v-if="createGrantId" label="允许模型" required-mark>
          <t-checkbox-group v-model="createModels" data-testid="create-models">
            <t-checkbox v-for="model in modelOptions" :key="model" :value="model">
              <span class="mk-mono">{{ model }}</span>
            </t-checkbox>
          </t-checkbox-group>
        </t-form-item>

        <t-alert
          v-if="formError"
          theme="error"
          :close-btn="false"
          class="form-error"
          data-testid="create-error"
        >
          <template #default>
            {{ formError }}
            <span v-if="formRequestId" class="mk-mono">requestId: {{ formRequestId }}</span>
          </template>
        </t-alert>

        <div class="form-actions">
          <t-button
            theme="primary"
            type="button"
            :disabled="!canCreate"
            :loading="submitting"
            data-testid="create-submit"
            @click="createKey"
          >
            创建 Virtual Key
          </t-button>
          <t-button type="button" @click="creating = false">取消</t-button>
        </div>
      </t-form>
    </section>

    <!-- Stat strip + filter bar (ledger density) -->
    <div v-if="keys.length" class="mk-stat-grid" data-testid="keys-stats">
      <div class="mk-stat-card">
        <span class="mk-stat-label">全部 Key</span>
        <span class="mk-stat-value mk-num">{{ keyStats.total }}</span>
        <span class="mk-stat-hint">你名下的 Virtual Key</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">ACTIVE</span>
        <span class="mk-stat-value mk-num">{{ keyStats.active }}</span>
        <span class="mk-stat-hint">可正常路由</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">ROTATING</span>
        <span class="mk-stat-value mk-num">{{ keyStats.rotating }}</span>
        <span class="mk-stat-hint">宽限期内</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">REVOKED</span>
        <span class="mk-stat-value mk-num">{{ keyStats.revoked }}</span>
        <span class="mk-stat-hint">已吊销</span>
      </div>
    </div>

    <div class="mk-filter-bar" data-testid="keys-filter-bar">
      <t-input
        v-model="keyFilter"
        placeholder="按名称 / 项目标签 / Key 前缀过滤"
        clearable
        data-testid="keys-filter"
        style="width: 320px"
      />
      <span class="mk-stat-hint">共 {{ filteredKeys.length }} 条</span>
    </div>

    <!-- Key table -->
    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="keyColumns"
        :data="filteredKeys"
        class="keys-table"
        data-testid="keys-table"
      >
        <template #name="{ row }">
          <div class="key-name">{{ row.name }}</div>
          <div class="mk-mono key-mask">{{ row.display }}</div>
        </template>
        <template #purpose="{ row }">{{ row.purpose }}</template>
        <template #modelIds="{ row }">
          <div class="mk-mono model-list">{{ row.modelIds.join(', ') }}</div>
        </template>
        <template #status="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">{{
            statusLabel[row.status] ?? row.status
          }}</span>
        </template>
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
        <template #lastUsedAt="{ row }">{{ formatTime(row.lastUsedAt) }}</template>
        <template #actions="{ row }">
          <t-dropdown trigger="click">
            <t-button variant="text" data-testid="key-actions">操作</t-button>
            <template #dropdown>
              <t-dropdown-menu>
                <t-dropdown-item
                  :disabled="row.status !== 'ACTIVE'"
                  @click="handleCommand('rotate', row)"
                >
                  <span data-testid="key-rotate">轮换</span>
                </t-dropdown-item>
                <t-dropdown-item
                  divider
                  :disabled="row.status !== 'ACTIVE' && row.status !== 'ROTATING'"
                  @click="handleCommand('revoke', row)"
                >
                  <span data-testid="key-revoke">吊销</span>
                </t-dropdown-item>
              </t-dropdown-menu>
            </template>
          </t-dropdown>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有 Virtual Key。</p>
            <p class="hint">点击右上角「创建 Virtual Key」开始使用。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <SecretRevealDialog
      v-if="revealData"
      v-model="reveal"
      :base-url="revealData.baseUrl"
      :secret="revealData.secret"
      :display="revealData.display"
    />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: var(--miqrokey-font-size-title);
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.full-width {
  width: 100%;
}

.form-error {
  margin-bottom: 12px;
}

.form-actions {
  display: flex;
  gap: 8px;
}

.keys-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.key-name {
  font-weight: 500;
}

.key-mask {
  color: var(--miqrokey-text-secondary);
}

.model-list {
  color: var(--miqrokey-text-secondary);
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
