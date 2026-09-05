<script setup lang="ts">
/**
 * NextApprovalCenterView — /app/approval-center v2 admin page (U2 org batch).
 * Behaviour parity with the legacy approval center: status filter, cursor
 * pagination, inline review panel with note, approve/reject actions.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { UiButton, UiStatusBadge, UiTable, toast } from '@/ui';
import type {ModelApprovalStatus} from '@/types/api';
import type { ModelApprovalView } from '@/types/generated-api';

const items = ref<ModelApprovalView[]>([]);
const loading = ref(true);
const loadError = ref('');
const filter = ref<'ALL' | ModelApprovalStatus>('PENDING');
const nextCursor = ref<string | undefined>(undefined);

const reviewAction = ref<'approve' | 'reject'>('approve');
const reviewTarget = ref<ModelApprovalView | null>(null);
const reviewNote = ref('');
const reviewError = ref('');
const submitting = ref(false);

const filters = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'ALL', label: '全部' },
] as const;

const statusText: Record<ModelApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function statusTone(status: ModelApprovalStatus): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'APPROVED') return 'success';
  if (status === 'REJECTED') return 'danger';
  return 'warning';
}

const columns = [
  { key: 'requesterName', title: '申请人', width: '96px' },
  { key: 'keyInfo', title: 'Virtual Key', minWidth: '190px' },
  { key: 'modelId', title: '模型', width: '160px' },
  { key: 'reason', title: '申请理由', minWidth: '150px' },
  { key: 'createdAt', title: '提交时间', width: '140px' },
  { key: 'status', title: '状态', width: '90px' },
  { key: 'reviewNote', title: '审核意见', minWidth: '130px' },
  { key: 'reviewedByName', title: '审核人', width: '90px' },
  { key: 'actions', title: '操作', width: '120px', align: 'center' as const },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const page = await api.listModelApprovals(
      filter.value === 'ALL' ? {} : { status: filter.value, size: 20 },
    );
    items.value = page.items;
    nextCursor.value = page.nextCursor;
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (!nextCursor.value) return;
  const page = await api.listModelApprovals({
    ...(filter.value === 'ALL' ? {} : { status: filter.value }),
    size: 20,
    before: nextCursor.value,
  });
  items.value = items.value.concat(page.items);
  nextCursor.value = page.nextCursor;
}

function openReview(action: 'approve' | 'reject', row: ModelApprovalView) {
  reviewAction.value = action;
  reviewTarget.value = row;
  reviewNote.value = '';
  reviewError.value = '';
}

function cancelReview() {
  reviewTarget.value = null;
  reviewError.value = '';
}

async function confirmReview() {
  if (!reviewTarget.value) return;
  submitting.value = true;
  reviewError.value = '';
  try {
    const note = reviewNote.value.trim() || undefined;
    if (reviewAction.value === 'approve') {
      await api.approveModelApproval(reviewTarget.value.id, note);
      toast.success(`已通过模型 ${reviewTarget.value.modelId}，立即生效`);
    } else {
      await api.rejectModelApproval(reviewTarget.value.id, note);
      toast.success('已驳回申请');
    }
    reviewTarget.value = null;
    await load();
  } catch (err) {
    reviewError.value = err instanceof Error ? err.message : '操作失败';
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
  <div class="ui-page next-approvals-center">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">审批中心</h1>
        <p class="ui-page-desc">
          用户为 Virtual Key 申请的模型授权；通过后立即写入授权并刷新路由快照。
        </p>
      </div>
    </header>

    <div class="next-approvals-center__toolbar">
      <div class="next-approvals-center__segmented" role="tablist" aria-label="状态筛选">
        <button
          v-for="item in filters"
          :key="item.value"
          type="button"
          class="next-approvals-center__seg"
          :class="{ 'next-approvals-center__seg--on': filter === item.value }"
          :data-testid="`filter-${item.value.toLowerCase()}`"
          @click="
            filter = item.value;
            load();
          "
        >
          {{ item.label }}
        </button>
      </div>
      <UiButton variant="ghost" size="sm" :loading="loading" @click="load">刷新</UiButton>
    </div>

    <!-- Inline review panel (same interaction spot as legacy) -->
    <section
      v-if="reviewTarget"
      class="ui-panel next-approvals-center__review"
      data-testid="review-panel"
    >
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">
            {{ reviewAction === 'approve' ? '通过申请' : '驳回申请' }}
          </h2>
          <span class="ui-panel-sub">
            {{ reviewTarget.keyName || reviewTarget.keyDisplay }} ·
            {{ reviewTarget.requesterName }} · {{ reviewTarget.modelId
            }}<template v-if="reviewTarget.projectTag"> · {{ reviewTarget.projectTag }}</template>
          </span>
        </div>
      </div>
      <div class="ui-panel-body">
        <p v-if="reviewTarget.reason" class="next-approvals-center__reason">
          申请理由：{{ reviewTarget.reason }}
        </p>
        <div class="ui-field">
          <span class="ui-field__label">审核意见（选填，≤ 500 字；将展示给申请人并留档）</span>
          <textarea
            v-model="reviewNote"
            class="ui-textarea"
            :maxlength="500"
            rows="3"
            data-testid="review-note"
          />
        </div>
        <p v-if="reviewError" class="ui-form-error" role="alert">{{ reviewError }}</p>
        <div class="next-approvals-center__review-actions">
          <UiButton
            :variant="reviewAction === 'approve' ? 'primary' : 'danger'"
            :loading="submitting"
            :data-testid="
              reviewAction === 'approve' ? 'review-confirm-approve' : 'review-confirm-reject'
            "
            @click="confirmReview"
          >
            {{ reviewAction === 'approve' ? '通过并生效' : '确认驳回' }}
          </UiButton>
          <UiButton variant="ghost" @click="cancelReview">取消</UiButton>
        </div>
      </div>
    </section>

    <div v-if="loadError" class="ui-alert ui-alert--error">{{ loadError }}</div>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="items"
        :loading="loading"
        row-key="id"
        :empty-title="filter === 'PENDING' ? '暂无待审批申请' : '暂无申请记录'"
        data-testid="approvals-queue-table"
      >
        <template #keyInfo="{ row }">
          <div class="next-approvals-center__key">
            <span>{{ (row as ModelApprovalView).keyName || '—' }}</span>
            <span class="next-approvals-center__key-sub ui-mono">
              {{ (row as ModelApprovalView).keyDisplay
              }}{{
                (row as ModelApprovalView).projectTag
                  ? ' · ' + (row as ModelApprovalView).projectTag
                  : ''
              }}
            </span>
          </div>
        </template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as ModelApprovalView).modelId }}</span>
        </template>
        <template #createdAt="{ row }">{{
          formatDate((row as ModelApprovalView).createdAt)
        }}</template>
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
          (row as ModelApprovalView).reviewNote || '—'
        }}</template>
        <template #reviewedByName="{ row }">{{
          (row as ModelApprovalView).reviewedByName || '—'
        }}</template>
        <template #actions="{ row }">
          <div
            v-if="(row as ModelApprovalView).status === 'PENDING'"
            class="next-approvals-center__actions"
          >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="approve-open"
              @click="openReview('approve', row as ModelApprovalView)"
            >
              通过
            </UiButton>
            <UiButton
              variant="ghost"
              size="sm"
              class="next-approvals-center__danger"
              data-testid="reject-open"
              @click="openReview('reject', row as ModelApprovalView)"
            >
              驳回
            </UiButton>
          </div>
          <span v-else>—</span>
        </template>
      </UiTable>
    </section>

    <div v-if="nextCursor" class="next-approvals-center__more">
      <UiButton variant="secondary" block data-testid="approvals-load-more" @click="loadMore">
        加载更多
      </UiButton>
    </div>
  </div>
</template>

<style scoped>
.next-approvals-center__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

.next-approvals-center__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-approvals-center__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.next-approvals-center__seg:hover {
  color: var(--ui-foreground);
}

.next-approvals-center__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-approvals-center__review {
  margin-bottom: var(--ui-space-5);
  max-width: 620px;
}

.next-approvals-center__reason {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  max-width: 560px;
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

.next-approvals-center__review-actions {
  display: flex;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-4);
}

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

.next-approvals-center__key {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  max-width: 260px;
}

.next-approvals-center__key > span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-approvals-center__key-sub {
  font-size: 11px;
  color: var(--ui-foreground-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-approvals-center__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-approvals-center__danger {
  color: var(--ui-danger-fg);
}

.next-approvals-center__more {
  margin-top: var(--ui-space-4);
}
</style>
