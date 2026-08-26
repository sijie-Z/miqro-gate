<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
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

const statusType: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
  ACTIVE: 'success',
  ROTATING: 'warning',
  REVOKED: 'info',
  DISABLED: 'danger',
};

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
    ElMessage.success('Virtual Key 已创建');
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
    await ElMessageBox.confirm(
      `轮换后旧 Key 进入宽限期并在宽限结束后失效，新 Key 仅在本次弹窗显示一次。`,
      `轮换 Virtual Key「${key.name}」`,
      { confirmButtonText: '轮换', cancelButtonText: '取消', type: 'warning' },
    );
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
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function revokeKey(key: VirtualKeyView) {
  try {
    await ElMessageBox.confirm(
      `吊销后该 Key 立即失效，使用它的客户端将无法继续请求。此操作不可撤销。`,
      `吊销 Virtual Key「${key.name}」`,
      { confirmButtonText: '吊销', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return; // cancelled
  }
  try {
    await api.revokeVirtualKey(key.id);
    ElMessage.success('Virtual Key 已吊销');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
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
        <el-button type="primary" data-testid="create-key-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Virtual Key' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      class="block-alert"
      data-testid="keys-load-error"
    >
      <template #default>
        {{ loadError }}
        <span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
      </template>
    </el-alert>

    <!-- Create form (single page form; dependent fields expand step by step) -->
    <section v-if="creating" class="create-panel" data-testid="create-form">
      <h3 class="panel-title">创建 Virtual Key</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="名称" required>
          <el-input
            v-model="createName"
            placeholder="例如 claude-code-main"
            data-testid="create-name"
          />
        </el-form-item>

        <el-form-item label="项目" required>
          <el-select
            v-model="createProjectId"
            placeholder="选择项目"
            class="full-width"
            data-testid="create-project"
            @change="onProjectChange"
          >
            <el-option
              v-for="project in projectsForGrant"
              :key="project.id"
              :value="project.id"
              :label="`${project.name}（${project.projectTag}）`"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="createProjectId" label="供应商产品 / 授权" required>
          <el-select
            v-model="createGrantId"
            placeholder="选择已授权的供应商产品"
            class="full-width"
            data-testid="create-grant"
            @change="onGrantChange"
          >
            <el-option
              v-for="grant in grantOptions"
              :key="grant.id"
              :value="grant.id"
              :label="`${grant.providerProductId}（${grant.models.length} 个模型）`"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="createGrantId" label="用途" required>
          <el-select v-model="createPurpose" class="full-width" data-testid="create-purpose">
            <el-option value="CLAUDE_CODE" label="Claude Code" />
            <el-option value="CLAUDE_DESKTOP" label="Claude Desktop" />
            <el-option value="CODEX" label="Codex" />
            <el-option value="CUSTOM" label="自定义" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="createGrantId" label="允许模型" required>
          <el-checkbox-group v-model="createModels" data-testid="create-models">
            <el-checkbox v-for="model in modelOptions" :key="model" :value="model">
              <span class="mk-mono">{{ model }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-alert
          v-if="formError"
          type="error"
          :closable="false"
          class="form-error"
          data-testid="create-error"
        >
          <template #default>
            {{ formError }}
            <span v-if="formRequestId" class="mk-mono">requestId: {{ formRequestId }}</span>
          </template>
        </el-alert>

        <div class="form-actions">
          <el-button
            type="primary"
            :disabled="!canCreate"
            :loading="submitting"
            data-testid="create-submit"
            @click="createKey"
          >
            创建 Virtual Key
          </el-button>
          <el-button @click="creating = false">取消</el-button>
        </div>
      </el-form>
    </section>

    <!-- Key table -->
    <el-table v-loading="loading" :data="keys" class="keys-table" data-testid="keys-table">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <div class="key-name">{{ row.name }}</div>
          <div class="mk-mono key-mask">{{ row.display }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="projectTag" label="项目" width="140" />
      <el-table-column label="用途" width="140">
        <template #default="{ row }">{{ row.purpose }}</template>
      </el-table-column>
      <el-table-column label="允许模型" min-width="200">
        <template #default="{ row }">
          <div class="mk-mono model-list">{{ row.modelIds.join(', ') }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType[row.status] ?? 'info'" size="small">{{
            statusLabel[row.status] ?? row.status
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="170">
        <template #default="{ row }">{{ formatTime(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="row.status !== 'ACTIVE'"
            data-testid="key-rotate"
            @click="rotateKey(row)"
          >
            轮换
          </el-button>
          <el-button
            link
            type="danger"
            :disabled="row.status !== 'ACTIVE' && row.status !== 'ROTATING'"
            data-testid="key-revoke"
            @click="revokeKey(row)"
          >
            吊销
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="table-empty">
          <p>还没有 Virtual Key。</p>
          <p class="hint">点击右上角「创建 Virtual Key」开始使用。</p>
        </div>
      </template>
    </el-table>

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
