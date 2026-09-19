<script setup lang="ts">
/**
 * NextAdminAgentsView — /app/agents v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy agents page: register an agent bound to an
 * ACTIVE upstream credential (provider product derives from the credential),
 * view per-agent usage (93-day window) and gated disable.
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
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  UiTooltip,
  toast,
} from '@/ui';
import { costGapNote } from '@/lib/usage-pricing';
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

// The tile below prints a cost that may be short of the whole; say so where it is
// shown rather than letting an unpriced figure read as a paid amount (#801/#857).
const costCaveat = computed(() => costGapNote(usageSummary.value?.totals));

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

// Hub View schemas mark every field optional (springdoc omits `required`);
// the endpoints here always populate the fields asserted below — the `!`
// restore the pre-hub required-field contract.
const credentialOptions = computed(() =>
  credentials.value.map((c) => ({ value: c.id!, label: c.name! })),
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
    toast.success('代理已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

// Rename dialog (#824): the row's `version` rides along so a stale form loses to
// 409 CONCURRENT_MODIFICATION instead of overwriting someone else's edit.
const renaming = ref<AgentView | null>(null);
const renameVisible = ref(false);
const renameForm = ref({ name: '', description: '' });
const renameError = ref('');
const renameSubmitting = ref(false);

function openRename(agent: AgentView) {
  renaming.value = agent;
  renameForm.value = { name: agent.name ?? '', description: agent.description ?? '' };
  renameError.value = '';
  renameVisible.value = true;
}

async function submitRename() {
  const agent = renaming.value;
  if (!agent) return;
  if (!renameForm.value.name.trim()) {
    renameError.value = '请填写名称。';
    return;
  }
  renameSubmitting.value = true;
  renameError.value = '';
  try {
    await api.adminUpdateAgent(agent.id!, {
      name: renameForm.value.name.trim(),
      description: renameForm.value.description.trim() || undefined,
      version: agent.version ?? 0,
    });
    renameVisible.value = false;
    toast.success('代理已更新');
    await load();
  } catch (error) {
    renameError.value = error instanceof ApiError ? error.message : '更新失败，请稍后重试。';
  } finally {
    renameSubmitting.value = false;
  }
}

async function enableAgent(agent: AgentView) {
  try {
    await api.adminEnableAgent(agent.id!);
    toast.success('代理已启用');
    await load();
  } catch (error) {
    toast.error(error instanceof ApiError ? error.message : '启用失败，请稍后重试。');
  }
}

function requestDelete(agent: AgentView) {
  confirmState.value = {
    title: `删除代理「${agent.name}」`,
    body: '删除不可恢复：该代理会被移除，占用的名称与凭证名额随之释放；用量与对账记录不受影响。',
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDeleteAgent(agent.id!);
        toast.success('代理已删除');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
        }
      }
    },
  };
}

function requestDisable(agent: AgentView) {
  confirmState.value = {
    title: `禁用代理「${agent.name}」`,
    body: '禁用后该代理不再计为可用，其凭证不受影响。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDisableAgent(agent.id!);
        toast.success('代理已禁用');
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

// #440: request-sequence guard — a slow usage load for agent A must not land
// after the dialog re-targets agent B.
let usageRequestSeq = 0;

async function showUsage(agent: AgentView) {
  const seq = ++usageRequestSeq;
  usageAgent.value = agent;
  usageSummary.value = null;
  usageError.value = '';
  usageVisible.value = true;
  usageLoading.value = true;
  try {
    const summary = await api.adminAgentUsage(agent.id!);
    if (seq !== usageRequestSeq) {
      return; // a newer dialog target won — this response is stale
    }
    usageSummary.value = summary;
  } catch (error) {
    if (seq === usageRequestSeq) {
      usageError.value = error instanceof ApiError ? error.message : '加载用量失败。';
    }
  } finally {
    if (seq === usageRequestSeq) {
      usageLoading.value = false;
    }
  }
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
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
          {{ creating ? '收起表单' : '创建代理' }}
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
            placeholder="选择可用凭证"
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
        empty-title="还没有代理"
        empty-description="创建代理并绑定出口凭证后，可按代理维度观测用量。"
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
              variant="link"
              size="sm"
              data-testid="agent-usage"
              @click="showUsage(row as AgentView)"
              >用量</UiButton
            >
            <DropdownMenuRoot>
              <DropdownMenuTrigger
                class="next-agents__kebab ui-link-action"
                aria-label="操作"
                :data-testid="`agent-actions-${(row as AgentView).id}`"
              >
                更多
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path
                    d="m4 6 4 4 4-4"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </DropdownMenuTrigger>
              <DropdownMenuPortal>
                <DropdownMenuContent class="ui-menu" :side-offset="4" :align="'end'">
                  <DropdownMenuItem
                    class="ui-menu__item next-agents__menu-item"
                    @select="openRename(row as AgentView)"
                  >
                    <DropdownMenuItemIndicator class="next-agents__menu-ind" />
                    <span data-testid="agent-rename">改名</span>
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    v-if="(row as AgentView).status === 'ACTIVE'"
                    class="ui-menu__item next-agents__menu-item"
                    @select="requestDisable(row as AgentView)"
                  >
                    <DropdownMenuItemIndicator class="next-agents__menu-ind" />
                    <span data-testid="agent-disable">禁用</span>
                  </DropdownMenuItem>
                  <DropdownMenuItem
                    v-else
                    class="ui-menu__item next-agents__menu-item"
                    @select="enableAgent(row as AgentView)"
                  >
                    <DropdownMenuItemIndicator class="next-agents__menu-ind" />
                    <span data-testid="agent-enable">启用</span>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator class="next-agents__menu-sep" />
                  <DropdownMenuItem
                    class="ui-menu__item next-agents__menu-item next-agents__menu-item--danger"
                    @select="requestDelete(row as AgentView)"
                  >
                    <DropdownMenuItemIndicator class="next-agents__menu-ind" />
                    <span data-testid="agent-delete">删除</span>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenuPortal>
            </DropdownMenuRoot>
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
            (usageSummary.totals?.requests?.upstream ?? 0).toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">输入 Tokens</span>
          <span class="next-agents__usage-value ui-num">{{
            (usageSummary.totals?.tokens?.input ?? 0).toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">输出 Tokens</span>
          <span class="next-agents__usage-value ui-num">{{
            (usageSummary.totals?.tokens?.output ?? 0).toLocaleString()
          }}</span>
        </div>
        <div class="next-agents__usage-tile">
          <span class="next-agents__usage-label">分摊成本</span>
          <span class="next-agents__usage-value ui-num"
            >¥{{ Number(usageSummary.totals?.cost?.projectAllocated ?? 0).toFixed(4)
            }}<UiTooltip v-if="costCaveat" :text="costCaveat"
              ><span class="next-agents__usage-caveat" data-testid="agent-cost-caveat"
                >未定价</span
              ></UiTooltip
            ></span
          >
        </div>
      </div>
      <p v-else class="next-agents__usage-empty">近 93 天无用量数据。</p>
      <template #footer>
        <UiButton variant="secondary" @click="usageVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

    <!-- Rename: name + description with the row's version as the lock token (#824) -->
    <UiDialog
      :open="renameVisible"
      :title="renaming ? `改名 · ${renaming.name}` : '改名'"
      width="480px"
      data-testid="agent-rename-dialog"
      @update:open="renameVisible = false"
    >
      <div class="next-agents__form">
        <UiInput
          v-model="renameForm.name"
          label="名称"
          required
          placeholder="例如 miqro-forge"
          data-testid="agent-rename-name"
        />
        <div class="ui-field">
          <span class="ui-field__label">描述</span>
          <textarea
            v-model="renameForm.description"
            class="ui-textarea"
            rows="2"
            maxlength="2000"
            placeholder="用途说明（可选）"
            data-testid="agent-rename-desc"
          />
        </div>
        <p v-if="renameError" class="ui-form-error">{{ renameError }}</p>
      </div>
      <template #footer>
        <UiButton variant="secondary" @click="renameVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :disabled="!renameForm.name.trim()"
          :loading="renameSubmitting"
          data-testid="agent-rename-submit"
          @click="submitRename"
          >保存</UiButton
        >
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

.next-agents__kebab {
  /* Layout only — ink and hover come from the shared .ui-link-action row
     action link style (#651). */
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: none;
  background: transparent;
  font: inherit;
  cursor: pointer;
}

/* .ui-menu panel chrome lives in styles/design-base.css (the radix popper root
   drops the scoped data-v); only the danger variant stays scoped. */
.next-agents__menu-item--danger {
  color: var(--ui-danger-fg);
}

.next-agents__menu-ind {
  display: none;
}

.next-agents__menu-sep {
  height: 1px;
  margin: var(--ui-space-1) 0;
  background: var(--ui-border-muted);
}

.next-agents__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-agents__name {
  font-weight: var(--ui-weight-medium);
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

.next-agents__usage-caveat {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-warning-fg);
  white-space: nowrap;
  margin-left: var(--ui-space-1);
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
