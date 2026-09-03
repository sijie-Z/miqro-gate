<script setup lang="ts">
/**
 * NextModelApprovalsView — /app-new/model-approvals pilot page (UI U1).
 * Behaviour parity with legacy ModelApprovalsView: apply for out-of-scope
 * models per Virtual Key, list own applications with statuses.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { UiButton, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { ModelApprovalStatus, ModelApprovalView, VirtualKeyView } from '@/types/api';

const keys = ref<VirtualKeyView[]>([]);
const approvals = ref<ModelApprovalView[]>([]);
const loading = ref(true);
const loadError = ref('');

const creating = ref(false);
const submitError = ref('');
const keyError = ref('');
const modelError = ref('');
const submitting = ref(false);
const form = ref({ virtualKeyId: '', modelId: '', reason: '' });

const statusText: Record<ModelApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function statusTone(status: ModelApprovalStatus): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'APPROVED') return 'success';
  if (status === 'REJECTED') return 'neutral';
  return 'warning';
}

const keyOptions = computed<UiSelectOption[]>(() =>
  keys.value.map((k) => ({
    value: k.id,
    label: `${k.display}${k.name ? ' · ' + k.name : ''}`,
  })),
);

const columns = [
  { key: 'keyDisplay', title: 'Virtual Key', minWidth: '200px' },
  { key: 'modelId', title: '模型', width: '200px' },
  { key: 'reason', title: '申请理由', minWidth: '180px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'reviewNote', title: '审核意见', minWidth: '160px' },
  { key: 'updatedAt', title: '更新时间', width: '170px', align: 'right' as const },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [keyList, approvalList] = await Promise.all([
      api.listVirtualKeys(),
      api.listMyModelApprovals(),
    ]);
    keys.value = keyList.filter((k) => k.status === 'ACTIVE');
    approvals.value = approvalList;
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  form.value = { virtualKeyId: '', modelId: '', reason: '' };
  submitError.value = '';
  keyError.value = '';
  modelError.value = '';
  creating.value = true;
}

async function submit() {
  keyError.value = form.value.virtualKeyId ? '' : '请选择 Virtual Key';
  modelError.value = form.value.modelId.trim() ? '' : '请填写模型 ID';
  if (keyError.value || modelError.value) {
    submitError.value = '';
    return;
  }
  submitting.value = true;
  submitError.value = '';
  try {
    const created = await api.submitModelApproval({
      virtualKeyId: form.value.virtualKeyId,
      modelId: form.value.modelId.trim(),
      reason: form.value.reason.trim() || undefined,
    });
    creating.value = false;
    if (created.status === 'APPROVED') {
      toast.success('模型在白名单中，已自动批准并生效');
    } else {
      toast.success('申请已提交，等待管理员审批');
    }
    await load();
  } catch (err) {
    submitError.value = err instanceof Error ? err.message : '提交失败';
  } finally {
    submitting.value = false;
  }
}

function formatDate(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-approvals">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">模型申请</h1>
        <p class="ui-page-desc">给 Virtual Key 申请授权范围外的模型；审批通过后立即生效。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="model-approval-open" @click="openCreate">
          申请新模型
        </UiButton>
      </div>
    </header>

    <section
      v-if="creating"
      class="ui-panel next-approvals__create"
      data-testid="model-approval-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">申请新模型</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-approvals__form">
          <UiSelect
            v-model="form.virtualKeyId"
            label="Virtual Key"
            required
            placeholder="选择要扩展模型的 Key"
            :options="keyOptions"
            :error="keyError || undefined"
            width="100%"
            data-testid="model-approval-key"
          />
          <UiInput
            v-model="form.modelId"
            label="模型 ID"
            required
            placeholder="精确模型 ID，如 deepseek-v4-flash"
            :error="modelError || undefined"
            data-testid="model-approval-model"
          />
          <div class="ui-field">
            <span class="ui-field__label"
              >申请理由 <span class="ui-field__hint-inline">选填，≤ 500 字</span></span
            >
            <textarea
              v-model="form.reason"
              class="ui-textarea"
              :maxlength="500"
              rows="4"
              placeholder="为什么需要这个模型？"
              data-testid="model-approval-reason"
            />
          </div>
          <p
            v-if="submitError"
            class="ui-form-error"
            role="alert"
            data-testid="model-approval-error"
          >
            {{ submitError }}
          </p>
          <div class="next-approvals__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="model-approval-submit"
              @click="submit"
            >
              提交申请
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="model-approvals-load-error">
      {{ loadError }}
    </div>

    <section class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">我的申请</h2>
      </div>
      <UiTable
        :columns="columns"
        :data="approvals"
        :loading="loading"
        row-key="id"
        empty-title="暂无申请记录"
        empty-description="需要更多模型时点击右上角「申请新模型」。"
        data-testid="model-approvals-table"
      >
        <template #keyDisplay="{ row }">
          <span class="ui-mono">{{ (row as ModelApprovalView).keyDisplay }}</span>
        </template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as ModelApprovalView).modelId }}</span>
        </template>
        <template #reason="{ row }">{{ (row as ModelApprovalView).reason || '—' }}</template>
        <template #status="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="statusTone((row as ModelApprovalView).status)"
            :label="
              statusText[(row as ModelApprovalView).status] ?? (row as ModelApprovalView).status
            "
          />
        </template>
        <template #reviewNote="{ row }">{{
          (row as ModelApprovalView).reviewNote || '暂无'
        }}</template>
        <template #updatedAt="{ row }">{{
          formatDate((row as ModelApprovalView).updatedAt)
        }}</template>
      </UiTable>
    </section>
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

.next-approvals__create {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-approvals__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
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

.ui-field__hint-inline {
  font-weight: var(--ui-weight-regular);
  color: var(--ui-foreground-faint);
}

.ui-textarea {
  width: 100%;
  min-height: 110px;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
  transition:
    border-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.ui-textarea::placeholder {
  color: color-mix(in srgb, var(--ui-foreground) 36%, transparent);
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-approvals__actions {
  display: flex;
  gap: var(--ui-space-2);
}
</style>
