<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { AgentView, CredentialView, UsageSummary } from '@/types/api';

const agents = ref<AgentView[]>([]);
const credentials = ref<CredentialView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 160 },
  { colKey: 'description', title: '描述', minWidth: 200 },
  { colKey: 'credentialName', title: '凭证', minWidth: 160 },
  { colKey: 'providerProductName', title: '供应商产品', minWidth: 160 },
  { colKey: 'status', title: '状态', width: 100 },
  { colKey: 'createdAt', title: '创建时间', width: 180 },
  { colKey: 'actions', title: '操作', width: 140, fixed: 'right' },
];

// Create form
const creating = ref(false);
const form = ref({ name: '', description: '', credentialId: '' });
const formError = ref('');
const submitting = ref(false);

// Usage dialog
const usageAgent = ref<AgentView | null>(null);
const usageVisible = ref(false);
const usageLoading = ref(false);
const usageSummary = ref<UsageSummary | null>(null);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.credentialId.length > 0,
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [agentList, credentialList] = await Promise.all([
      api.adminListAgents(),
      api.listCredentials(),
    ]);
    agents.value = agentList;
    credentials.value = credentialList.filter((c) => c.status === 'ACTIVE');
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载 Agent 列表失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function createAgent() {
  if (!canCreate.value) {
    formError.value = '请填写名称并选择凭证。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.adminCreateAgent({
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      credentialId: form.value.credentialId,
    });
    creating.value = false;
    form.value = { name: '', description: '', credentialId: '' };
    MessagePlugin.success('Agent 已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function disable(agent: AgentView) {
  try {
    await confirmDialog({
      header: `禁用 Agent「${agent.name}」`,
      body: '禁用后该 Agent 不再计为可用，其凭证不受影响。',
      confirmBtn: '禁用',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.adminDisableAgent(agent.id);
    MessagePlugin.success('Agent 已禁用');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

async function showUsage(agent: AgentView) {
  usageAgent.value = agent;
  usageSummary.value = null;
  usageVisible.value = true;
  usageLoading.value = true;
  try {
    usageSummary.value = await api.adminAgentUsage(agent.id);
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  } finally {
    usageLoading.value = false;
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="agents-page">
    <PageHeader
      title="Agents"
      description="管理智能体资源：出口绑定一个上游凭证（供应商产品由凭证派生），用量按凭证聚合观测。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="agent-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Agent' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="agent-create-form">
      <h3 class="panel-title">创建 Agent</h3>
      <p class="hint">Agent 的出口凭证必须是 ACTIVE 状态；供应商产品由凭证所属订阅自动派生。</p>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input
            v-model="form.name"
            placeholder="例如 miqro-forge"
            data-testid="agent-create-name"
          />
        </t-form-item>
        <t-form-item label="描述">
          <t-textarea v-model="form.description" placeholder="用途说明（可选）" :maxlength="2000" />
        </t-form-item>
        <t-form-item label="出口凭证" required-mark>
          <t-select
            v-model="form.credentialId"
            placeholder="选择 ACTIVE 凭证"
            data-testid="agent-create-credential"
          >
            <t-option v-for="c in credentials" :key="c.id" :label="c.name" :value="c.id" />
          </t-select>
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :disabled="!canCreate"
          :loading="submitting"
          data-testid="agent-create-submit"
          @click="createAgent"
        >
          创建
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="agents"
        class="agents-table"
        data-testid="agents-table"
      >
        <template #name="{ row }">
          <span class="agent-name">{{ row.name }}</span>
        </template>
        <template #description="{ row }">
          <span class="agent-desc">{{ row.description || '—' }}</span>
        </template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.status === 'ACTIVE' ? 'Active' : 'Disabled' }}
          </span>
        </template>
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
        <template #actions="{ row }">
          <t-button variant="text" data-testid="agent-usage" @click="showUsage(row)">用量</t-button>
          <t-button
            v-if="row.status === 'ACTIVE'"
            variant="text"
            theme="danger"
            data-testid="agent-disable"
            @click="disable(row)"
          >
            禁用
          </t-button>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有 Agent。</p>
            <p class="hint">创建 Agent 并绑定出口凭证后，可按 Agent 维度观测用量。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="usageVisible"
      :header="usageAgent ? `用量 · ${usageAgent.name}` : '用量'"
      width="520px"
      :close-on-overlay-click="false"
    >
      <t-loading :loading="usageLoading" size="small">
        <div v-if="usageSummary" class="usage-grid" data-testid="agent-usage-grid">
          <div class="usage-card">
            <span class="usage-label">请求</span>
            <span class="usage-value mk-num">{{ usageSummary.totals.requests.upstream }}</span>
          </div>
          <div class="usage-card">
            <span class="usage-label">输入 Tokens</span>
            <span class="usage-value mk-num">{{ usageSummary.totals.tokens.input }}</span>
          </div>
          <div class="usage-card">
            <span class="usage-label">输出 Tokens</span>
            <span class="usage-value mk-num">{{ usageSummary.totals.tokens.output }}</span>
          </div>
          <div class="usage-card">
            <span class="usage-label">分摊成本</span>
            <span class="usage-value mk-num"
              >¥{{ Number(usageSummary.totals.cost.projectAllocated).toFixed(4) }}</span
            >
          </div>
        </div>
        <div v-else-if="!usageLoading" class="usage-empty">近 93 天无用量数据。</div>
      </t-loading>
      <template #footer>
        <t-button @click="usageVisible = false">关闭</t-button>
      </template>
    </t-dialog>
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
  max-width: 760px;
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.create-form {
  max-width: 520px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.agents-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.agent-name {
  font-weight: 500;
}

.agent-desc {
  font-size: 13px;
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

.usage-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.usage-card {
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: var(--miqrokey-radius-panel);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.usage-label {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}

.usage-value {
  font-size: 18px;
  font-weight: 600;
}

.usage-empty {
  padding: 24px 0;
  text-align: center;
  color: var(--miqrokey-text-secondary);
}
</style>
