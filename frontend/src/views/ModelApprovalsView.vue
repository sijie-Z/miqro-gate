<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
import type { ModelApprovalStatus, ModelApprovalView, VirtualKeyView } from '@/types/api';

const keys = ref<VirtualKeyView[]>([]);
const approvals = ref<ModelApprovalView[]>([]);
const loading = ref(true);
const loadError = ref('');

const creating = ref(false);
const submitError = ref('');
const submitting = ref(false);
const form = ref({ virtualKeyId: '', modelId: '', reason: '' });

const statusText: Record<ModelApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function statusClass(status: ModelApprovalStatus): string {
  if (status === 'APPROVED') return 'mk-status--success';
  if (status === 'REJECTED') return 'mk-status--neutral';
  return 'mk-status--warning';
}

const columns: PrimaryTableCol[] = [
  { colKey: 'keyDisplay', title: 'Virtual Key', minWidth: 220 },
  { colKey: 'modelId', title: '模型', width: 200 },
  { colKey: 'reason', title: '申请理由', minWidth: 160 },
  {
    colKey: 'status',
    title: '状态',
    width: 100,
    cell: (h, { row }: { row: ModelApprovalView }) =>
      h('span', { class: `mk-status ${statusClass(row.status)}` }, statusText[row.status]),
  },
  { colKey: 'reviewNote', title: '审核意见', minWidth: 140 },
  {
    colKey: 'updatedAt',
    title: '更新时间',
    width: 170,
    cell: (h, { row }: { row: ModelApprovalView }) =>
      h('span', row.updatedAt ? new Date(row.updatedAt).toLocaleString() : '—'),
  },
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
  creating.value = true;
}

async function submit() {
  if (!form.value.virtualKeyId || !form.value.modelId.trim()) {
    submitError.value = '请选择 Virtual Key 并填写模型 ID';
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
      MessagePlugin.success('模型在白名单中，已自动批准并生效');
    } else {
      MessagePlugin.success('申请已提交，等待管理员审批');
    }
    await load();
  } catch (err) {
    submitError.value = err instanceof Error ? err.message : '提交失败';
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="mk-page">
    <PageHeader
      title="模型申请"
      description="给 Virtual Key 申请授权范围外的模型；审批通过后立即生效。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="model-approval-open" @click="openCreate">
          申请新模型
        </t-button>
      </template>
    </PageHeader>

    <div v-if="creating" class="mk-panel create-form" data-testid="model-approval-form">
      <div class="mk-panel-title">申请新模型</div>
      <t-form label-align="top">
        <t-form-item label="Virtual Key" required-mark>
          <t-select
            v-model="form.virtualKeyId"
            placeholder="选择要扩展模型的 Key"
            :options="
              keys.map((k) => ({
                label: `${k.display}${k.name ? ' · ' + k.name : ''}`,
                value: k.id,
              }))
            "
            data-testid="model-approval-key"
          />
        </t-form-item>
        <t-form-item label="模型 ID" required-mark>
          <t-input
            v-model="form.modelId"
            placeholder="精确模型 ID，如 deepseek-v4-flash"
            data-testid="model-approval-model"
          />
        </t-form-item>
        <t-form-item label="申请理由" :help="'选填，≤ 500 字'">
          <t-textarea v-model="form.reason" :maxlength="500" :autosize="{ minRows: 2 }" />
        </t-form-item>
        <div v-if="submitError" class="mk-inline-error" role="alert">{{ submitError }}</div>
        <t-form-item>
          <t-button
            theme="primary"
            :loading="submitting"
            data-testid="model-approval-submit"
            @click="submit"
          >
            提交申请
          </t-button>
          <t-button variant="text" @click="creating = false">取消</t-button>
        </t-form-item>
      </t-form>
    </div>

    <t-alert v-if="loadError" theme="error" class="block-alert" :message="loadError" />
    <t-loading :loading="loading">
      <t-table
        data-testid="model-approvals-table"
        :data="approvals"
        :columns="columns"
        row-key="id"
        :table-layout="'fixed'"
      >
        <template #empty>
          <div class="mk-empty-hint">暂无申请记录。需要更多模型时点击「申请新模型」。</div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.mk-inline-error {
  color: var(--td-error-color);
  font-size: 13px;
  margin-top: 8px;
}
.mk-panel-title {
  font-weight: 600;
  margin-bottom: 12px;
}
</style>
