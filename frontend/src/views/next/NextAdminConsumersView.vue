<script setup lang="ts">
/**
 * NextAdminConsumersView — /app/consumers v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy consumers page: create external-system
 * API consumers with a one-shot key reveal, list and gated disable.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { ApiConsumerView } from '@/types/generated-api';

const consumers = ref<ApiConsumerView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const createName = ref('');
const submitting = ref(false);
const formError = ref('');

const reveal = ref(false);
const revealName = ref('');
const revealKey = ref('');
const revealAcked = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const scopeTarget = ref<ApiConsumerView | null>(null);
const scopeMode = ref<'full' | 'custom'>('full');
const scopeBilling = ref(true);
const scopeMcp = ref(true);
const scopeSaving = ref(false);
const scopeError = ref('');

function openScope(consumer: ApiConsumerView) {
  const caps = consumer.capabilities ?? null;
  scopeTarget.value = consumer;
  if (caps === null) {
    scopeMode.value = 'full';
    scopeBilling.value = true;
    scopeMcp.value = true;
  } else {
    scopeMode.value = 'custom';
    scopeBilling.value = caps.includes('billing:read');
    scopeMcp.value = caps.includes('mcp:call');
  }
  scopeError.value = '';
}

function capabilityText(consumer: ApiConsumerView): string {
  const caps = consumer.capabilities;
  if (caps == null) return '全量';
  if (caps.length === 0) return '无通道';
  return caps.join('、');
}

async function saveScope() {
  const target = scopeTarget.value;
  if (!target) return;
  scopeSaving.value = true;
  scopeError.value = '';
  try {
    let capabilities: string[] | null = null;
    if (scopeMode.value === 'custom') {
      capabilities = [];
      if (scopeBilling.value) capabilities.push('billing:read');
      if (scopeMcp.value) capabilities.push('mcp:call');
    }
    await api.updateApiConsumerScope(target.id!, capabilities);
    toast.success('能力作用域已更新');
    scopeTarget.value = null;
    await load();
  } catch (error) {
    scopeError.value = error instanceof ApiError ? error.message : '更新失败';
  } finally {
    scopeSaving.value = false;
  }
}

const columns = [
  { key: 'name', title: '名称', minWidth: '200px' },
  { key: 'keyPrefix', title: 'Key 前缀', width: '160px' },
  { key: 'capabilities', title: '能力作用域', minWidth: '150px' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '150px', align: 'center' as const },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    consumers.value = await api.listApiConsumers();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createConsumer() {
  if (!createName.value.trim()) {
    formError.value = '请输入消费者名称。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    const response = await api.createApiConsumer(createName.value.trim());
    creating.value = false;
    createName.value = '';
    revealName.value = response.consumer?.name ?? '';
    revealKey.value = response.apiKey ?? '';
    revealAcked.value = false;
    reveal.value = true;
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败';
  } finally {
    submitting.value = false;
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(revealKey.value);
    toast.success('API Key 已复制');
  } catch {
    toast.error('复制失败，请手动复制');
  }
}

function requestDisable(consumer: ApiConsumerView) {
  // Hub View schemas mark every field optional (springdoc omits `required`);
  // consumer rows always carry the id — the `!` restores the pre-hub contract.
  confirmState.value = {
    title: `吊销消费者「${consumer.name}」`,
    body: '吊销后该 API Key 立即失效，外部系统将无法再调用计费查询接口。',
    confirmLabel: '吊销',
    tone: 'danger',
    run: async () => {
      try {
        await api.disableApiConsumer(consumer.id!);
        toast.success('消费者已吊销');
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

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-consumers">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">API 消费者</h1>
        <p class="ui-page-desc">外部系统专用 Key 访问计费查询接口；Key 仅创建时显示一次。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="consumer-create-open"
          @click="creating = !creating"
        >
          {{ creating ? '收起表单' : '新建消费者' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="consumers-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-consumers__create"
      data-testid="consumer-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">新建消费者</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-consumers__form">
          <UiInput
            v-model="createName"
            label="名称"
            required
            placeholder="例如 billing-sync"
            data-testid="consumer-create-name"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-consumers__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="consumer-create-submit"
              @click="createConsumer"
            >
              创建
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ consumers.length }} 个消费者</span>
      </div>
      <UiTable
        :columns="columns"
        :data="consumers"
        :loading="loading"
        row-key="id"
        empty-title="还没有 API 消费者"
        data-testid="consumers-table"
      >
        <template #name="{ row }">
          <span class="next-consumers__name">{{ (row as ApiConsumerView).name }}</span>
        </template>
        <template #keyPrefix="{ row }">
          <span class="ui-mono">{{ (row as ApiConsumerView).keyPrefix }}</span>
        </template>
        <template #capabilities="{ row }">
          <span
            class="next-consumers__caps"
            :class="{
              'next-consumers__caps--full': (row as ApiConsumerView).capabilities === null,
            }"
            data-testid="consumer-capabilities"
          >
            {{ capabilityText(row as ApiConsumerView) }}
          </span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as ApiConsumerView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as ApiConsumerView).status === 'ACTIVE' ? '正常' : '已吊销'"
          />
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as ApiConsumerView).createdAt)
        }}</template>
        <template #actions="{ row }">
          <UiButton
            v-if="(row as ApiConsumerView).status === 'ACTIVE'"
            variant="ghost"
            size="sm"
            data-testid="consumer-scope"
            @click="openScope(row as ApiConsumerView)"
          >
            作用域
          </UiButton>
          <UiButton
            v-if="(row as ApiConsumerView).status === 'ACTIVE'"
            variant="ghost"
            size="sm"
            class="next-consumers__danger"
            data-testid="consumer-disable"
            @click="requestDisable(row as ApiConsumerView)"
          >
            吊销
          </UiButton>
          <span v-if="(row as ApiConsumerView).status !== 'ACTIVE'">—</span>
        </template>
      </UiTable>
    </section>

    <!-- One-shot API key reveal -->
    <UiDialog
      :open="reveal"
      title="API Key 已生成，仅显示一次"
      :description="`消费者「${revealName}」的 Key 如下，请立即交付并妥善保存；关闭后无法再次查看。`"
      width="540px"
      :dismissible="false"
      @update:open="revealAcked && (reveal = $event)"
    >
      <div class="ui-mono next-consumers__key" data-testid="consumer-key-value">
        {{ revealKey }}
      </div>
      <label class="next-consumers__ack">
        <input
          v-model="revealAcked"
          type="checkbox"
          class="next-consumers__ack-input"
          data-testid="consumer-key-ack"
        />
        <span class="next-consumers__ack-box" aria-hidden="true">
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
        <span>我已保存该 Key</span>
      </label>
      <template #footer>
        <UiButton variant="secondary" data-testid="consumer-key-copy" @click="copyKey"
          >复制</UiButton
        >
        <UiButton
          variant="primary"
          :disabled="!revealAcked"
          data-testid="consumer-key-close"
          @click="reveal = false"
        >
          完成
        </UiButton>
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
    <!-- Channel scope (issue #316) -->
    <UiDialog
      v-if="scopeTarget"
      :open="true"
      :title="`能力作用域 — ${scopeTarget.name}`"
      description="控制这把消费者 Key 能访问哪些通道；能力不足的调用会被拒绝（403 CONSUMER_SCOPE_DENIED / consumer_scope_denied）。"
      width="520px"
      @update:open="scopeTarget = null"
    >
      <div class="next-consumers__scope-mode">
        <label>
          <input v-model="scopeMode" type="radio" value="full" data-testid="scope-mode-full" />
          <span>全量（不裁剪，默认）</span>
        </label>
        <label>
          <input v-model="scopeMode" type="radio" value="custom" data-testid="scope-mode-custom" />
          <span>自定义通道</span>
        </label>
      </div>
      <div v-if="scopeMode === 'custom'" class="next-consumers__scope-caps">
        <label data-testid="scope-cap-billing">
          <input v-model="scopeBilling" type="checkbox" />
          <span>计费查询通道（billing:read）</span>
        </label>
        <label data-testid="scope-cap-mcp">
          <input v-model="scopeMcp" type="checkbox" />
          <span>MCP 数据面通道（mcp:call）</span>
        </label>
        <p class="next-consumers__scope-hint">
          一个都不选 = 无任何通道（该 Key 立即无法访问计费接口与 MCP 服务）。
        </p>
      </div>
      <p v-if="scopeError" class="ui-form-error">{{ scopeError }}</p>
      <template #footer>
        <UiButton variant="ghost" @click="scopeTarget = null">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="scopeSaving"
          data-testid="scope-save"
          @click="saveScope"
        >
          保存
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

.next-consumers__create {
  margin-bottom: var(--ui-space-5);
  max-width: 680px;
}

.next-consumers__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 440px;
}

.next-consumers__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-consumers__name {
  font-weight: var(--ui-weight-medium);
}

.next-consumers__danger {
  color: var(--ui-danger-fg);
}

.next-consumers__caps {
  display: inline-flex;
  align-items: center;
  padding: 1px var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  background: var(--ui-fill-muted, var(--ui-bg-muted));
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-consumers__caps--full {
  background: var(--ui-success-soft, var(--ui-success-bg));
  color: var(--ui-success-fg, var(--ui-color-success));
}

.next-consumers__scope-mode,
.next-consumers__scope-caps {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-3);
}

.next-consumers__scope-mode label,
.next-consumers__scope-caps label {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  font-size: var(--ui-font-size-sm);
  cursor: pointer;
}

.next-consumers__scope-hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  margin: var(--ui-space-1) 0 0 var(--ui-space-5);
}

.next-consumers__key {
  padding: var(--ui-space-3) var(--ui-space-4);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-base);
  line-height: var(--ui-line-height-lg);
  word-break: break-all;
  user-select: all;
}

.next-consumers__ack {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-consumers__ack-input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

.next-consumers__ack-box {
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

.next-consumers__ack-input:checked + .next-consumers__ack-box {
  background: var(--ui-primary);
  border-color: var(--ui-primary);
  color: #fff;
}
</style>
