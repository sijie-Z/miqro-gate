<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { confirmDialog } from '@/utils/confirm';
import PageHeader from '@/components/PageHeader.vue';
import type {
  AdminUser,
  Project,
  QuotaLevel,
  QuotaMetric,
  QuotaPeriod,
  QuotaRuleView,
  QuotaScopeType,
} from '@/types/api';

const rules = ref<QuotaRuleView[]>([]);
const users = ref<AdminUser[]>([]);
const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref('');

const editing = ref(false);
const editError = ref('');
const saving = ref(false);
const editId = ref<string | undefined>(undefined);
const form = ref({
  scopeType: 'USER' as QuotaScopeType,
  scopeId: '',
  metric: 'TOKENS' as QuotaMetric,
  period: 'DAILY' as QuotaPeriod,
  limitValue: undefined as number | undefined,
  warnPercent: 80,
  status: 'ACTIVE',
});

const metricText: Record<QuotaMetric, string> = { TOKENS: 'Token 用量', REQUESTS: '请求次数' };
const periodText: Record<QuotaPeriod, string> = { DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月' };

const levelText: Record<QuotaLevel, string> = { NORMAL: '正常', WARNING: '预警', EXCEEDED: '超限' };

function levelClass(level: QuotaLevel): string {
  if (level === 'EXCEEDED') return 'mk-status--danger';
  if (level === 'WARNING') return 'mk-status--warning';
  return 'mk-status--success';
}

const columns: PrimaryTableCol[] = [
  {
    colKey: 'scope',
    title: '对象',
    minWidth: 160,
    cell: (h, { row }: { row: QuotaRuleView }) =>
      h('div', { class: 'mk-cell-stack' }, [
        h('span', row.scopeName || '—'),
        row.scopeTag ? h('span', { class: 'mk-cell-sub' }, row.scopeTag) : undefined,
      ]),
  },
  {
    colKey: 'metric',
    title: '维度',
    width: 100,
    cell: (h, { row }: { row: QuotaRuleView }) => h('span', metricText[row.metric]),
  },
  {
    colKey: 'period',
    title: '周期',
    width: 80,
    cell: (h, { row }: { row: QuotaRuleView }) => h('span', periodText[row.period]),
  },
  {
    colKey: 'limitValue',
    title: '限额',
    width: 120,
    align: 'right',
    cell: (h, { row }: { row: QuotaRuleView }) =>
      h('span', row.limitValue.toLocaleString() + (row.metric === 'TOKENS' ? ' tokens' : ' 次')),
  },
  {
    colKey: 'watermark',
    title: '本期用量 / 水位',
    minWidth: 220,
    cell: (h, { row }: { row: QuotaRuleView }) =>
      h('div', { class: 'mk-cell-stack' }, [
        h('span', `${row.used.toLocaleString()}（${row.usedPct}%）`),
        h('div', { class: 'mk-quota-bar', 'aria-label': `usage ${row.usedPct}%` }, [
          h('div', {
            class: 'mk-quota-bar-fill',
            style: {
              width: `${Math.min(100, row.usedPct)}%`,
              background:
                row.level === 'EXCEEDED'
                  ? 'var(--td-error-color)'
                  : row.level === 'WARNING'
                    ? 'var(--td-warning-color)'
                    : 'var(--td-brand-color)',
            },
          }),
        ]),
      ]),
  },
  {
    colKey: 'level',
    title: '状态',
    width: 90,
    cell: (h, { row }: { row: QuotaRuleView }) =>
      row.status === 'DISABLED'
        ? h('span', { class: 'mk-status mk-status--neutral' }, '停用')
        : h('span', { class: `mk-status ${levelClass(row.level)}` }, levelText[row.level]),
  },
  { colKey: 'actions', title: '操作', width: 120, fixed: 'right' },
];

const scopeOptions = (): { label: string; value: string }[] =>
  form.value.scopeType === 'USER'
    ? users.value
        .filter((u) => u.status === 'ACTIVE')
        .map((u) => ({ label: `${u.displayName}（${u.username}）`, value: u.id }))
    : projects.value
        .filter((p) => p.status === 'ACTIVE')
        .map((p) => ({ label: `${p.name}（${p.code}）`, value: p.id }));

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [ruleList, userList, projectList] = await Promise.all([
      api.listQuotaRules(),
      api.listUsers(),
      api.listProjects(),
    ]);
    rules.value = ruleList;
    users.value = userList;
    projects.value = projectList;
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editId.value = undefined;
  form.value = {
    scopeType: 'USER',
    scopeId: '',
    metric: 'TOKENS',
    period: 'DAILY',
    limitValue: undefined,
    warnPercent: 80,
    status: 'ACTIVE',
  };
  editError.value = '';
  editing.value = true;
}

function openEdit(row: QuotaRuleView) {
  editId.value = row.id;
  form.value = {
    scopeType: row.scopeType,
    scopeId: row.scopeId,
    metric: row.metric,
    period: row.period,
    limitValue: row.limitValue,
    warnPercent: row.warnPercent,
    status: row.status,
  };
  editError.value = '';
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
  editError.value = '';
}

async function save() {
  const limitValue = Number(form.value.limitValue);
  const warnPercent = Number(form.value.warnPercent);
  if (!form.value.scopeId) {
    editError.value = '请选择配额对象';
    return;
  }
  if (!Number.isInteger(limitValue) || limitValue <= 0) {
    editError.value = '限额必须是正整数';
    return;
  }
  if (!Number.isInteger(warnPercent) || warnPercent < 1 || warnPercent > 99) {
    editError.value = '预警阈值必须是 1–99 的整数';
    return;
  }
  saving.value = true;
  editError.value = '';
  try {
    const saved = await api.putQuotaRule({
      scopeType: form.value.scopeType,
      scopeId: form.value.scopeId,
      metric: form.value.metric,
      period: form.value.period,
      limitValue,
      warnPercent,
      status: form.value.status,
    });
    editing.value = false;
    MessagePlugin.success(editId.value ? '配额规则已更新' : `已为 ${saved.scopeName} 创建配额规则`);
    await load();
  } catch (err) {
    editError.value = err instanceof Error ? err.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

async function remove(row: QuotaRuleView) {
  try {
    await confirmDialog({
      header: '删除配额规则',
      body: `删除「${row.scopeName ?? row.scopeId}」的${metricText[row.metric]}${periodText[row.period]}规则？历史用量不受影响。`,
      confirmBtn: '删除',
      theme: 'danger',
    });
  } catch {
    return; // cancelled
  }
  try {
    await api.deleteQuotaRule(row.id);
    MessagePlugin.success('配额规则已删除');
    await load();
  } catch (err) {
    MessagePlugin.error(err instanceof Error ? err.message : '删除失败');
  }
}

onMounted(load);
</script>

<template>
  <div class="mk-page">
    <PageHeader
      title="配额规则"
      description="用量配额（Token / 请求次数 × 日/周/月）。只预警不阻断——超限不拦截流量。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="quota-rule-create-open" @click="openCreate">
          新增规则
        </t-button>
      </template>
    </PageHeader>

    <div v-if="editing" class="mk-panel quota-form" data-testid="quota-rule-form">
      <div class="mk-panel-title">{{ editId ? '编辑配额规则' : '新增配额规则' }}</div>
      <t-form label-align="top" class="create-form">
        <div class="mk-form-row">
          <t-form-item label="对象类型" required-mark>
            <t-radio-group
              v-model="form.scopeType"
              variant="default-filled"
              data-testid="quota-scope-type"
            >
              <t-radio-button value="USER">用户</t-radio-button>
              <t-radio-button value="PROJECT">项目</t-radio-button>
            </t-radio-group>
          </t-form-item>
          <t-form-item label="配额对象" required-mark>
            <t-select
              v-model="form.scopeId"
              placeholder="选择用户或项目"
              :options="scopeOptions()"
              :disabled="!!editId"
              data-testid="quota-scope"
            />
          </t-form-item>
        </div>
        <div class="mk-form-row">
          <t-form-item label="维度" required-mark>
            <t-radio-group
              v-model="form.metric"
              variant="default-filled"
              data-testid="quota-metric"
            >
              <t-radio-button value="TOKENS">Token 用量</t-radio-button>
              <t-radio-button value="REQUESTS">请求次数</t-radio-button>
            </t-radio-group>
          </t-form-item>
          <t-form-item label="周期" required-mark>
            <t-radio-group
              v-model="form.period"
              variant="default-filled"
              data-testid="quota-period"
            >
              <t-radio-button value="DAILY">每日</t-radio-button>
              <t-radio-button value="WEEKLY">每周</t-radio-button>
              <t-radio-button value="MONTHLY">每月</t-radio-button>
            </t-radio-group>
          </t-form-item>
        </div>
        <div class="mk-form-row">
          <t-form-item label="限额" required-mark>
            <t-input-number
              v-model="form.limitValue"
              :min="1"
              :max="9007199254740991"
              :step="1000"
              placeholder="正整数"
              data-testid="quota-limit"
            />
          </t-form-item>
          <t-form-item label="预警阈值（%）" :help="'达到限额的此百分比时进入预警；≥100% 为超限'">
            <t-input-number
              v-model="form.warnPercent"
              :min="1"
              :max="99"
              data-testid="quota-warn"
            />
          </t-form-item>
          <t-form-item v-if="editId" label="状态">
            <t-radio-group v-model="form.status" variant="default-filled">
              <t-radio-button value="ACTIVE">启用</t-radio-button>
              <t-radio-button value="DISABLED">停用</t-radio-button>
            </t-radio-group>
          </t-form-item>
        </div>
        <div v-if="editError" class="mk-inline-error" role="alert">{{ editError }}</div>
        <t-form-item>
          <t-button theme="primary" :loading="saving" data-testid="quota-rule-save" @click="save">
            {{ editId ? '保存' : '创建' }}
          </t-button>
          <t-button variant="text" @click="cancelEdit">取消</t-button>
        </t-form-item>
      </t-form>
    </div>

    <t-alert v-if="loadError" theme="error" class="block-alert" :message="loadError" />
    <t-loading :loading="loading">
      <t-table
        data-testid="quota-rules-table"
        :data="rules"
        :columns="columns"
        row-key="id"
        :table-layout="'fixed'"
      >
        <template #actions="{ row }">
          <span class="mk-cell-actions">
            <t-button
              theme="primary"
              variant="text"
              size="small"
              data-testid="quota-rule-edit"
              @click="openEdit(row)"
            >
              编辑
            </t-button>
            <t-button
              theme="danger"
              variant="text"
              size="small"
              data-testid="quota-rule-delete"
              @click="remove(row)"
            >
              删除
            </t-button>
          </span>
        </template>
        <template #empty>
          <div class="mk-empty-hint">
            暂无配额规则。先为用户或项目设置用量限额，超限仅预警不阻断。
          </div>
        </template>
      </t-table>
    </t-loading>
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
.mk-form-row {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}
.mk-form-row > :deep(.t-form__item) {
  flex: 1;
}
.quota-form {
  margin-bottom: 16px;
  max-width: 760px;
}
.mk-panel-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.mk-quota-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--td-bg-color-component);
  overflow: hidden;
  margin-top: 4px;
}
.mk-quota-bar-fill {
  height: 100%;
  border-radius: 3px;
}
.mk-inline-error {
  color: var(--td-error-color);
  font-size: 13px;
  margin-top: 8px;
}
</style>
