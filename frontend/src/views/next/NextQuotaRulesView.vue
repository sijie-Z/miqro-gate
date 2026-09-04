<script setup lang="ts">
/**
 * NextQuotaRulesView — /app/quota-rules v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy quota rules page: default template panel
 * (configure/enable/toggle), quota rules table with watermark bars and an
 * inline create/edit form. APIs untouched.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {
  AdminUser,
  Project,
  QuotaDefaultTemplateView,
  QuotaLevel,
  QuotaMetric,
  QuotaPeriod,
  QuotaRuleView,
} from '@/types/api';

const rules = ref<QuotaRuleView[]>([]);
const users = ref<AdminUser[]>([]);
const projects = ref<Project[]>([]);
const quotaTemplate = ref<QuotaDefaultTemplateView | null>(null);
const loading = ref(true);
const loadError = ref('');

const editing = ref(false);
const editError = ref('');
const saving = ref(false);
const editId = ref<string | undefined>(undefined);
const form = ref({
  scopeType: 'USER' as 'USER' | 'PROJECT',
  scopeId: '',
  metric: 'TOKENS' as QuotaMetric,
  period: 'DAILY' as QuotaPeriod,
  limitValue: '',
  warnPercent: '80',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
});

const configuring = ref(false);
const templateError = ref('');
const templateSaving = ref(false);
const templateForm = ref({
  metric: 'TOKENS' as QuotaMetric,
  period: 'MONTHLY' as QuotaPeriod,
  limitValue: '',
});

const metricText: Record<QuotaMetric, string> = { TOKENS: 'Token 用量', REQUESTS: '请求次数' };
const periodText: Record<QuotaPeriod, string> = { DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月' };
const levelText: Record<QuotaLevel, string> = { NORMAL: '正常', WARNING: '预警', EXCEEDED: '超限' };

const levelTone: Record<QuotaLevel, 'success' | 'warning' | 'danger' | 'neutral'> = {
  NORMAL: 'success',
  WARNING: 'warning',
  EXCEEDED: 'danger',
};

const scopeOptions = computed<UiSelectOption[]>(() =>
  form.value.scopeType === 'USER'
    ? users.value.map((u) => ({ value: u.id, label: u.username }))
    : projects.value.map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` })),
);

const templateConfigured = computed(
  () =>
    !!quotaTemplate.value &&
    quotaTemplate.value.metric !== null &&
    quotaTemplate.value.limitValue !== null,
);

const templateStateText = computed(() => {
  if (!quotaTemplate.value || quotaTemplate.value.metric === null) return '未配置';
  return quotaTemplate.value.enabled ? '已启用' : '未启用';
});

const templateStateTone = computed<'success' | 'neutral' | 'warning'>(() => {
  if (!quotaTemplate.value || quotaTemplate.value.metric === null) return 'neutral';
  return quotaTemplate.value.enabled ? 'success' : 'warning';
});

const templateDefinitionText = computed(() => {
  if (
    !quotaTemplate.value ||
    quotaTemplate.value.limitValue === null ||
    quotaTemplate.value.metric === null ||
    quotaTemplate.value.period === null
  )
    return '';
  return `${metricText[quotaTemplate.value.metric]} · ${periodText[quotaTemplate.value.period]} · 限额 ${quotaTemplate.value.limitValue.toLocaleString()}`;
});

const columns = [
  { key: 'scope', title: '配额对象', minWidth: '220px' },
  { key: 'metric', title: '维度', width: '130px' },
  { key: 'period', title: '周期', width: '100px' },
  { key: 'limitValue', title: '限额', width: '130px', align: 'right' as const },
  { key: 'watermark', title: '本期用量', minWidth: '240px' },
  { key: 'level', title: '状态', width: '110px' },
  { key: 'status', title: '规则', width: '90px' },
  { key: 'actions', title: '操作', width: '140px', align: 'center' as const },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [ruleList, userList, projectList, templateState] = await Promise.all([
      api.listQuotaRules(),
      api.listUsers(),
      api.listProjects(),
      api.getQuotaDefaultTemplate(),
    ]);
    rules.value = ruleList;
    users.value = userList;
    projects.value = projectList;
    quotaTemplate.value = templateState;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
    }
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
    limitValue: '',
    warnPercent: '80',
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
    limitValue: String(row.limitValue),
    warnPercent: String(row.warnPercent),
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
    toast.success(editId.value ? '配额规则已更新' : `已为 ${saved.scopeName ?? ''} 创建配额规则`);
    await load();
  } catch (err) {
    editError.value = err instanceof Error ? err.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function requestRemove(row: QuotaRuleView) {
  confirmState.value = {
    title: '删除配额规则',
    body: `删除「${row.scopeName ?? row.scopeId}」的${metricText[row.metric]}${periodText[row.period]}规则？历史用量不受影响。`,
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.deleteQuotaRule(row.id);
        toast.success('配额规则已删除');
        await load();
      } catch (err) {
        if (err instanceof Error) {
          toast.error(err.message);
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

// ---- default quota template ----
function openTemplateConfig() {
  templateForm.value = {
    metric: quotaTemplate.value?.metric ?? 'TOKENS',
    period: quotaTemplate.value?.period ?? 'MONTHLY',
    limitValue: quotaTemplate.value?.limitValue?.toString() ?? '',
  };
  templateError.value = '';
  configuring.value = true;
}

function cancelTemplateConfig() {
  configuring.value = false;
  templateError.value = '';
}

async function saveTemplate() {
  const limitValue = Number(templateForm.value.limitValue);
  if (!Number.isInteger(limitValue) || limitValue <= 0) {
    templateError.value = '限额必须是正整数';
    return;
  }
  templateSaving.value = true;
  templateError.value = '';
  try {
    await api.putQuotaDefaultTemplate({
      metric: templateForm.value.metric,
      period: templateForm.value.period,
      limitValue,
    });
    configuring.value = false;
    toast.success('默认配额模板已保存');
    await load();
  } catch (err) {
    templateError.value = err instanceof Error ? err.message : '保存失败';
  } finally {
    templateSaving.value = false;
  }
}

async function toggleTemplate() {
  if (!templateConfigured.value || !quotaTemplate.value) {
    toast.info('请先配置默认配额模板');
    return;
  }
  try {
    if (quotaTemplate.value.enabled) {
      await api.disableQuotaDefaultTemplate();
      toast.success('已停用：仅影响之后新建的用户，已分配的规则保留');
    } else {
      await api.enableQuotaDefaultTemplate();
      toast.success('已启用：新建用户将自动获得配额规则');
    }
    await load();
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败');
  }
}

function levelFill(level: QuotaLevel): string {
  if (level === 'EXCEEDED') return 'var(--ui-danger-fg)';
  if (level === 'WARNING') return 'var(--ui-warning-fg)';
  return 'var(--ui-primary)';
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-quota">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">配额规则</h1>
        <p class="ui-page-desc">
          用量配额（Token / 请求次数 × 日/周/月）。只预警不阻断——超限不拦截流量。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="quota-rule-create-open" @click="openCreate">
          新建规则
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">{{ loadError }}</div>

    <!-- Default quota template -->
    <section class="ui-panel next-quota__template" data-testid="quota-template-panel">
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">默认配额模板</h2>
          <span class="ui-panel-sub"
            >{{ templateStateText
            }}{{
              templateDefinitionText ? ' · ' + templateDefinitionText : ''
            }}；新建用户时自动复制快照</span
          >
        </div>
        <div class="next-quota__template-actions">
          <UiStatusBadge
            variant="pill"
            :tone="templateStateTone"
            :label="templateStateText"
            data-testid="quota-template-state"
          />
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="quota-template-open"
            @click="openTemplateConfig"
          >
            {{ templateConfigured ? '重新配置' : '配置模板' }}
          </UiButton>
          <UiButton
            variant="secondary"
            size="sm"
            data-testid="quota-template-toggle"
            @click="toggleTemplate"
          >
            {{ quotaTemplate?.enabled ? '停用' : '启用' }}
          </UiButton>
        </div>
      </div>
      <div v-if="configuring" class="ui-panel-body">
        <div class="next-quota__template-form">
          <UiSelect
            v-model="templateForm.metric"
            label="配额类型"
            :options="[
              { value: 'TOKENS', label: 'Token 用量' },
              { value: 'REQUESTS', label: '请求次数' },
            ]"
            data-testid="template-metric"
          />
          <UiSelect
            v-model="templateForm.period"
            label="统计周期"
            :options="[
              { value: 'DAILY', label: '每日' },
              { value: 'WEEKLY', label: '每周' },
              { value: 'MONTHLY', label: '每月' },
            ]"
            data-testid="template-period"
          />
          <UiInput
            v-model="templateForm.limitValue"
            label="限额"
            required
            type="number"
            data-testid="template-limit"
          />
          <p v-if="templateError" class="ui-form-error">{{ templateError }}</p>
          <div class="next-quota__actions">
            <UiButton
              variant="primary"
              :loading="templateSaving"
              data-testid="template-save"
              @click="saveTemplate"
            >
              保存模板
            </UiButton>
            <UiButton variant="ghost" @click="cancelTemplateConfig">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <!-- Inline create/edit form -->
    <section v-if="editing" class="ui-panel next-quota__rule-form" data-testid="quota-rule-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">{{ editId ? '编辑配额规则' : '新建配额规则' }}</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-quota__rule-grid">
          <UiSelect
            v-model="form.scopeType"
            label="对象类型"
            :options="[
              { value: 'USER', label: '用户' },
              { value: 'PROJECT', label: '项目' },
            ]"
            data-testid="quota-scope-type"
            @change="form.scopeId = ''"
          />
          <UiSelect
            v-model="form.scopeId"
            label="配额对象"
            required
            placeholder="选择对象"
            :options="scopeOptions"
            data-testid="quota-scope"
          />
          <UiSelect
            v-model="form.metric"
            label="维度"
            :options="[
              { value: 'TOKENS', label: 'Token 用量' },
              { value: 'REQUESTS', label: '请求次数' },
            ]"
            data-testid="quota-metric"
          />
          <UiSelect
            v-model="form.period"
            label="周期"
            :options="[
              { value: 'DAILY', label: '每日' },
              { value: 'WEEKLY', label: '每周' },
              { value: 'MONTHLY', label: '每月' },
            ]"
            data-testid="quota-period"
          />
          <UiInput
            v-model="form.limitValue"
            label="限额"
            required
            type="number"
            data-testid="quota-limit"
          />
          <UiInput
            v-model="form.warnPercent"
            label="预警阈值（%）"
            type="number"
            hint="达到限额的此百分比时进入预警；≥100% 为超限"
            data-testid="quota-warn"
          />
          <UiSelect
            v-if="editId"
            v-model="form.status"
            label="状态"
            :options="[
              { value: 'ACTIVE', label: '启用' },
              { value: 'DISABLED', label: '停用' },
            ]"
            data-testid="quota-status"
          />
          <p v-if="editError" class="ui-form-error" role="alert">{{ editError }}</p>
          <div class="next-quota__actions">
            <UiButton
              variant="primary"
              :loading="saving"
              data-testid="quota-rule-save"
              @click="save"
            >
              {{ editId ? '保存' : '创建' }}
            </UiButton>
            <UiButton variant="ghost" @click="cancelEdit">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ rules.length }} 条规则</span>
      </div>
      <UiTable
        :columns="columns"
        :data="rules"
        :loading="loading"
        row-key="id"
        empty-title="暂无配额规则"
        empty-description="先为用户或项目设置用量限额，超限仅预警不阻断。"
        data-testid="quota-rules-table"
      >
        <template #scope="{ row }">
          <div class="next-quota__scope-name">{{ (row as QuotaRuleView).scopeName ?? '—' }}</div>
          <div v-if="(row as QuotaRuleView).scopeTag" class="ui-mono next-quota__scope-tag">
            {{ (row as QuotaRuleView).scopeTag }}
          </div>
        </template>
        <template #metric="{ row }">{{ metricText[(row as QuotaRuleView).metric] }}</template>
        <template #period="{ row }">{{ periodText[(row as QuotaRuleView).period] }}</template>
        <template #limitValue="{ row }">
          <span class="ui-num">{{ (row as QuotaRuleView).limitValue.toLocaleString() }}</span>
        </template>
        <template #watermark="{ row }">
          <div class="next-quota__bar-row">
            <span class="ui-num next-quota__bar-nums"
              >{{ (row as QuotaRuleView).used.toLocaleString() }} /
              {{ (row as QuotaRuleView).limitValue.toLocaleString() }}</span
            >
            <div class="next-quota__bar-track">
              <div
                class="next-quota__bar-fill"
                :style="{
                  width: `${Math.min(100, (row as QuotaRuleView).usedPct)}%`,
                  background: levelFill((row as QuotaRuleView).level),
                }"
              />
            </div>
          </div>
        </template>
        <template #level="{ row }">
          <UiStatusBadge
            variant="pill"
            :tone="levelTone[(row as QuotaRuleView).level]"
            :label="levelText[(row as QuotaRuleView).level]"
          />
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as QuotaRuleView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as QuotaRuleView).status === 'ACTIVE' ? '生效中' : '已停用'"
          />
        </template>
        <template #actions="{ row }">
          <div class="next-quota__row-actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="quota-rule-edit"
              @click="openEdit(row as QuotaRuleView)"
            >
              编辑
            </UiButton>
            <UiButton
              variant="ghost"
              size="sm"
              class="next-quota__danger"
              data-testid="quota-rule-delete"
              @click="requestRemove(row as QuotaRuleView)"
            >
              删除
            </UiButton>
          </div>
        </template>
      </UiTable>
    </section>

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

.next-quota__template {
  margin-bottom: var(--ui-space-5);
}

.next-quota__template-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-quota__template-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 220px));
  gap: var(--ui-space-4);
  align-items: end;
}

.next-quota__rule-form {
  margin-bottom: var(--ui-space-5);
  max-width: 900px;
}

.next-quota__rule-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-5);
}

.next-quota__actions {
  display: flex;
  gap: var(--ui-space-2);
  align-self: end;
}

.next-quota__scope-name {
  font-weight: var(--ui-weight-medium);
}

.next-quota__scope-tag {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-quota__bar-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-quota__bar-nums {
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-quota__bar-track {
  flex: 1;
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-quota__bar-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
}

.next-quota__row-actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-quota__danger {
  color: var(--ui-danger-fg);
}
</style>
