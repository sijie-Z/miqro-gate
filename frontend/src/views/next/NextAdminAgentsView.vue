<script setup lang="ts">
/**
 * NextAdminAgentsView — /app/agents v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy agents page: register an agent bound to an
 * ACTIVE upstream credential (provider product derives from the credential),
 * view per-agent usage (93-day window) and gated disable.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type {} from '@/types/api';
import type { AgentView, CredentialView, UsageSummary } from '@/types/generated-api';

const agents = ref<AgentView[]>([]);
const credentials = ref<CredentialView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'description', title: '描述', minWidth: '200px' },
  { key: 'credentialName', title: '凭证', minWidth: '150px' },
  { key: 'providerProductName', title: '供应商产品', minWidth: '160px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '150px', align: 'center' as const },
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
const usageError = ref('');
const usageSummary = ref<UsageSummary | null>(null);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.credentialId.length > 0,
);

const credentialOptions = computed(() =>
  credentials.value.map((c) => ({ value: c.id, label: c.name })),
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
      loadError.value = '加载智能体列表失败。';
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
    toast.success('Agent 已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

function requestDisable(agent: AgentView) {
  confirmState.value = {
    title: `禁用 Agent「${agent.name}」`,
    body: '禁用后该 Agent 不再计为可用，其凭证不受影响。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDisableAgent(agent.id);
        toast.success('Agent 已禁用');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
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

async function showUsage(agent: AgentView) {
  usageAgent.value = agent;
  usageSummary.value = null;
  usageError.value = '';
  usageVisible.value = true;
  usageLoading.value = true;
  try {
    usageSummary.value = await api.adminAgentUsage(agent.id);
  } catch (error) {
    usageError.value = error instanceof ApiError ? error.message : '加载用量失败。';
  } finally {
    usageLoading.value = false;
  }
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-agents">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">智能体</h1>
        <p class="ui-page-desc">
          管理智能体资源：出口绑定一个上游凭证（供应商产品由凭证派生），用量按凭证聚合观测。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="agent-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Agent' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-agents__create" data-testid="agent-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建 Agent</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-agents__hint">
          Agent 的出口凭证必须是 ACTIVE 状态；供应商产品由凭证所属订阅自动派生。
        </p>
        <div class="next-agents__form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 miqro-forge"
            data-testid="agent-create-name"
          />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="form.description"
              class="ui-textarea"
              rows="2"
              maxlength="2000"
              placeholder="用途说明（可选）"
              data-testid="agent-create-desc"
            />
          </div>
          <UiSelect
            v-model="form.credentialId"
            label="出口凭证"
            required
            :options="credentialOptions"
            placeholder="选择 ACTIVE 凭证"
            data-testid="agent-create-credential"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-agents__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="agent-create-submit"
              @click="createAgent"
              >创建</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ agents.length }} 个智能体</span>
      </div>
      <UiTable
        :columns="columns"
        :data="agents"
        :loading="loading"
        row-key="id"
        empty-title="还没有 Agent"
        empty-description="创建 Agent 并绑定出口凭证后，可按 Agent 维度观测用量。"
        data-testid="agents-table"
      >
        <template #name="{ row }">
          <span class="next-agents__name">{{ (row as AgentView).name }}</span>
        </template>
        <template #description="{ row }">{{ (row as AgentView).description || '—' }}</template>
        <template #credentialName="{ row }">{{ (row as AgentView).credentialName }}</template>
        <template #providerProductName="{ row }">{{
          (row as AgentView).providerProductName
        }}</template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as AgentView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as AgentView).status === 'ACTIVE' ? '正常' : '已禁用'"
          />
        </template>
        <template #createdAt="{ row }">{{ formatTime((row as AgentView).createdAt) }}</template>
        <template #actions="{ row }">
          <div class="next-agents__actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="agent-usage"
              @click="showUsage(row as AgentView)"
              >用量</UiButton
            >
            <UiButton
              v-if="(row as AgentView).status === 'ACTIVE'"
              variant="ghost"
              size="sm"
              class="next-agents__danger"
              data-testid="agent-disable"
              @click="requestDisable(row as AgentView)"
              >禁用</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <!-- Per-agent usage: 93-day window aggregated by the control plane -->
    <UiDialog
      :open="usageVisible"
      :title="usageAgent ? `用量 · ${usageAgent.name}` : '用量'"
      width="520px"
      data-testid="agent-usage-dialog"
      @update:open="usageVisible = false"
    >
      <div v-if="usageError" class="ui-alert ui-alert--error">{{ usageError }}</div>
      <template v-else-if="usageLoading">
        <div class="next-agents__usage-grid">
          <div v-for="n in 4" :key="n" class="next-agents__usage-tile">
            <span class="ui-skeleton" style="height: 14px; width: 60%">&nbsp;</span>
            <span class="ui-skeleton" style="height: 22px; width: 40%">&nbsp;</span>
          </div>
        </div>
      </template>
      <div v-else-if="usageSummary" class="next-agents__usage-grid" data-testid="agent-usage-grid">
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">请求</span>
          <span class="next-agents__usage-value ui-num">{{
            usageSummary.totals.requests.upstream.toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">输入 Tokens</span>
          <span class="next-agents__usage-value ui-num">{{
            usageSummary.totals.tokens.input.toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">输出 Tokens</span>
          <span class="next-agents__usage-value ui-num">{{
            usageSummary.totals.tokens.output.toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">分摊成本</span>
          <span class="next-agents__usage-value ui-num"
            >¥{{ Number(usageSummary.totals.cost.projectAllocated).toFixed(4) }}</span
          >
        </div>
      </div>
      <p v-else class="next-agents__usage-empty">近 93 天无用量数据。</p>
      <template #footer>
        <UiButton variant="secondary" @click="usageVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

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
  min-height: 56px;
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

.next-agents__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-agents__hint {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-agents__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.next-agents__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-agents__name {
  font-weight: var(--ui-weight-medium);
}

.next-agents__danger {
  color: var(--ui-danger-fg);
}

.next-agents__usage-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-3);
}

.next-agents__usage-tile {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding: var(--ui-space-3) var(--ui-space-4);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
}

.next-agents__usage-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-agents__usage-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-agents__usage-empty {
  padding: var(--ui-space-8) 0;
  text-align: center;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
}
</style>
