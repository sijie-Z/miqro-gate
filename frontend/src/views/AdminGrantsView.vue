<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { Grant, Project } from '@/types/api';

const grants = ref<Grant[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projects = ref<Project[]>([]);
const credentials = ref<{ id: string; name: string }[]>([]);
const products = ref<{ id: string; displayName: string }[]>([]);

const creating = ref(false);
const form = ref({ projectId: '', providerProductId: '', credentialId: '', models: '' });
const formError = ref('');
const submitting = ref(false);

const modelsDrawer = ref(false);
const modelsGrant = ref<Grant | null>(null);
const modelsText = ref('');
const modelsSaving = ref(false);

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
  const [projectList, credentialList] = await Promise.all([
    api.listProjects(),
    api.listCredentials(),
  ]);
  projects.value = projectList;
  credentials.value = credentialList;
  // Products are resolved from the grants list only; a curated product
  // selector comes with the provider portal (G5.3).
  products.value = [];
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
  modelsText.value = (await api.grantModels(grant.id)).join('\n');
  modelsDrawer.value = true;
}

async function saveModels() {
  if (!modelsGrant.value) return;
  modelsSaving.value = true;
  try {
    await api.updateGrantModels(modelsGrant.value.id, parseModels(modelsText.value));
    ElMessage.success('模型范围已更新');
    modelsDrawer.value = false;
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  } finally {
    modelsSaving.value = false;
  }
}

async function disable(grant: Grant) {
  try {
    await ElMessageBox.confirm(
      `禁用后该 Grant 不再授权任何 Virtual Key，关联 Key 将无法通过此授权路由。`,
      '禁用 Grant',
      { confirmButtonText: '禁用', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await api.disableGrant(grant.id);
  await load();
}

function statusClass(status: string): string {
  return status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--neutral';
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()]);
});
</script>

<template>
  <div class="grants-page">
    <PageHeader title="Grants" description="项目 × 供应商产品 × 凭证的授权组合与模型范围。">
      <template #actions>
        <el-button type="primary" data-testid="grant-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Grant' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="grant-create-form">
      <h3 class="panel-title">创建 Grant</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="项目" required>
          <el-select
            v-model="form.projectId"
            placeholder="选择项目"
            data-testid="grant-create-project"
          >
            <el-option
              v-for="p in projects"
              :key="p.id"
              :label="`${p.code} · ${p.name}`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证" required>
          <el-select
            v-model="form.credentialId"
            placeholder="选择上游凭证"
            data-testid="grant-create-credential"
          >
            <el-option v-for="c in credentials" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型范围（每行一个）">
          <el-input
            v-model="form.models"
            type="textarea"
            :rows="3"
            placeholder="例如 claude-3-7-sonnet"
          />
        </el-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="grant-create-submit"
          @click="createGrant"
        >
          创建 Grant
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="grants" data-testid="grants-table">
      <el-table-column prop="projectId" label="项目" min-width="200">
        <template #default="{ row }">
          <span class="mk-mono">{{ row.projectId.slice(0, 8) }}…</span>
        </template>
      </el-table-column>
      <el-table-column prop="providerProductId" label="供应商产品" min-width="200">
        <template #default="{ row }">
          <span class="mk-mono">{{ row.providerProductId.slice(0, 8) }}…</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">{{ row.status }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-testid="grant-models-open" @click="openModels(row)"
            >模型</el-button
          >
          <el-button
            v-if="row.status === 'ACTIVE'"
            link
            type="danger"
            data-testid="grant-disable"
            @click="disable(row)"
          >
            禁用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="modelsDrawer"
      :title="`模型范围：${modelsGrant?.id.slice(0, 8) ?? ''}…`"
      size="420px"
    >
      <p class="hint">每行一个模型 ID；保存会整体替换当前范围。</p>
      <el-input v-model="modelsText" type="textarea" :rows="10" data-testid="grant-models-input" />
      <template #footer>
        <el-button
          type="primary"
          :loading="modelsSaving"
          data-testid="grant-models-save"
          @click="saveModels"
        >
          保存
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.hint {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}
</style>
