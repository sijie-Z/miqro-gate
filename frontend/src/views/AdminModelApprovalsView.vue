<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
import type { ModelApprovalStatus, ModelApprovalView } from '@/types/api';

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

const statusText: Record<ModelApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function statusClass(status: ModelApprovalStatus): string {
  if (status === 'APPROVED') return 'mk-status--success';
  if (status === 'REJECTED') return 'mk-status--danger';
  return 'mk-status--warning';
}

const columns: PrimaryTableCol[] = [
  { colKey: 'requesterName', title: '申请人', width: 120 },
  {
    colKey: 'keyInfo',
    title: 'Virtual Key',
    minWidth: 220,
    cell: (h, { row }: { row: ModelApprovalView }) =>
      h('div', { class: 'mk-cell-stack' }, [
        h('span', row.keyName || '—'),
        h('span', { class: 'mk-cell-sub' }, [
          row.keyDisplay || '',
          row.projectTag ? ` · ${row.projectTag}` : '',
        ]),
      ]),
  },
  { colKey: 'modelId', title: '模型', width: 200 },
  { colKey: 'reason', title: '申请理由', minWidth: 160 },
  {
    colKey: 'createdAt',
    title: '提交时间',
    width: 160,
    cell: (h, { row }: { row: ModelApprovalView }) =>
      h('span', row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'),
  },
  {
    colKey: 'status',
    title: '状态',
    width: 100,
    cell: (h, { row }: { row: ModelApprovalView }) =>
      h('span', { class: `mk-status ${statusClass(row.status)}` }, statusText[row.status]),
  },
  { colKey: 'reviewNote', title: '审核意见', minWidth: 140 },
  {
    colKey: 'reviewedByName',
    title: '审核人',
    width: 110,
    cell: (h, { row }: { row: ModelApprovalView }) => h('span', row.reviewedByName || '—'),
  },
  { colKey: 'actions', title: '操作', width: 140, fixed: 'right' },
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
      MessagePlugin.success(`已通过模型 ${reviewTarget.value.modelId}，立即生效`);
    } else {
      await api.rejectModelApproval(reviewTarget.value.id, note);
      MessagePlugin.success('已驳回申请');
    }
    reviewTarget.value = null;
    await load();
  } catch (err) {
    reviewError.value = err instanceof Error ? err.message : '操作失败';
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="mk-page">
    <PageHeader
      title="审批中心"
      description="用户为 Virtual Key 申请的模型授权；通过后立即写入授权并刷新路由快照。"
    >
    </PageHeader>

    <div class="mk-filter-bar">
      <t-radio-group v-model="filter" variant="default-filled" @change="load">
        <t-radio-button value="PENDING">待审批</t-radio-button>
        <t-radio-button value="APPROVED">已通过</t-radio-button>
        <t-radio-button value="REJECTED">已驳回</t-radio-button>
        <t-radio-button value="ALL">全部</t-radio-button>
      </t-radio-group>
      <t-button variant="text" :loading="loading" @click="load">刷新</t-button>
    </div>

    <div v-if="reviewTarget" class="mk-panel review-panel" data-testid="review-panel">
      <div class="mk-panel-title">
        {{ reviewAction === 'approve' ? '通过申请' : '驳回申请' }}
        <span class="mk-panel-sub">
          {{ reviewTarget.keyName || reviewTarget.keyDisplay }} · {{ reviewTarget.requesterName }} ·
          {{ reviewTarget.modelId
          }}<template v-if="reviewTarget.projectTag"> · {{ reviewTarget.projectTag }}</template>
        </span>
      </div>
      <p v-if="reviewTarget.reason" class="mk-review-reason">申请理由：{{ reviewTarget.reason }}</p>
      <t-form label-align="top">
        <t-form-item label="审核意见（选填）" :help="'≤ 500 字；将展示给申请人并留档'">
          <t-textarea
            v-model="reviewNote"
            :maxlength="500"
            :autosize="{ minRows: 2 }"
            data-testid="review-note"
          />
        </t-form-item>
        <div v-if="reviewError" class="mk-inline-error" role="alert">{{ reviewError }}</div>
        <t-form-item>
          <t-button
            :theme="reviewAction === 'approve' ? 'primary' : 'danger'"
            :loading="submitting"
            :data-testid="
              reviewAction === 'approve' ? 'review-confirm-approve' : 'review-confirm-reject'
            "
            @click="confirmReview"
          >
            {{ reviewAction === 'approve' ? '通过并生效' : '确认驳回' }}
          </t-button>
          <t-button variant="text" @click="cancelReview">取消</t-button>
        </t-form-item>
      </t-form>
    </div>

    <t-alert v-if="loadError" theme="error" class="block-alert" :message="loadError" />
    <t-loading :loading="loading">
      <t-table
        data-testid="approvals-queue-table"
        :data="items"
        :columns="columns"
        row-key="id"
        :table-layout="'fixed'"
      >
        <template #actions="{ row }">
          <span v-if="row.status === 'PENDING'" class="mk-cell-actions">
            <t-button
              theme="primary"
              variant="text"
              size="small"
              data-testid="approve-open"
              @click="openReview('approve', row)"
            >
              通过
            </t-button>
            <t-button
              theme="danger"
              variant="text"
              size="small"
              data-testid="reject-open"
              @click="openReview('reject', row)"
            >
              驳回
            </t-button>
          </span>
          <span v-else>—</span>
        </template>
        <template #empty>
          <div class="mk-empty-hint">暂无{{ filter === 'PENDING' ? '待审批' : '' }}申请。</div>
        </template>
      </t-table>
    </t-loading>
    <div v-if="nextCursor" class="mk-load-more">
      <t-button variant="outline" block @click="loadMore">加载更多</t-button>
    </div>
  </div>
</template>

<style scoped>
.mk-cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.3;
}
.mk-cell-sub {
  color: var(--td-text-color-secondary);
  font-size: 12px;
}
.mk-cell-actions {
  display: inline-flex;
  gap: 4px;
}
.mk-load-more {
  margin-top: 12px;
}
.review-panel {
  margin-bottom: 16px;
  max-width: 560px;
}
.mk-panel-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.mk-panel-sub {
  color: var(--td-text-color-secondary);
  font-weight: 400;
  margin-left: 8px;
}
.mk-review-reason {
  color: var(--td-text-color-secondary);
  margin: 4px 0 10px;
  line-height: 1.6;
}
.mk-inline-error {
  color: var(--td-error-color);
  font-size: 13px;
  margin-top: 8px;
}
</style>
