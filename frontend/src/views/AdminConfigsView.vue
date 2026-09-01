<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { ConfigEntryView } from '@/types/api';

const entries = ref<ConfigEntryView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'groupName', title: '分组', width: 140 },
  { colKey: 'key', title: '键', minWidth: 200 },
  { colKey: 'value', title: '值', minWidth: 220 },
  { colKey: 'description', title: '描述', minWidth: 180 },
  { colKey: 'updatedAt', title: '更新时间', width: 180 },
  { colKey: 'actions', title: '操作', width: 140, fixed: 'right' },
];

const groups = computed(() => [...new Set(entries.value.map((e) => e.groupName))].sort());
const activeGroup = ref('');
const filtered = computed(() =>
  activeGroup.value
    ? entries.value.filter((e) => e.groupName === activeGroup.value)
    : entries.value,
);

const editing = ref<ConfigEntryView | null>(null);
const dialogVisible = ref(false);
const form = ref({ group: '', key: '', value: '', description: '' });
const formError = ref('');
const saving = ref(false);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    entries.value = await api.adminListConfigs();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载配置失败。';
    }
  } finally {
    loading.value = false;
  }
}

function openEdit(entry: ConfigEntryView | null) {
  editing.value = entry;
  formError.value = '';
  form.value = entry
    ? {
        group: entry.groupName,
        key: entry.key,
        value: entry.value,
        description: entry.description ?? '',
      }
    : { group: '', key: '', value: '', description: '' };
  dialogVisible.value = true;
}

async function save() {
  formError.value = '';
  if (!form.value.group.trim() || !form.value.key.trim() || form.value.value === '') {
    formError.value = '分组、键与值均为必填。';
    return;
  }
  saving.value = true;
  try {
    await api.adminPutConfig({
      group: form.value.group.trim(),
      key: form.value.key.trim(),
      value: form.value.value,
      description: form.value.description.trim() || undefined,
    });
    dialogVisible.value = false;
    MessagePlugin.success(editing.value ? '配置已更新' : '配置已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    saving.value = false;
  }
}

async function remove(entry: ConfigEntryView) {
  try {
    await confirmDialog({
      header: `删除配置「${entry.groupName}/${entry.key}」`,
      body: '删除后该配置项从目录移除，应用当前值不受影响。',
      confirmBtn: '删除',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.adminDeleteConfig(entry.groupName, entry.key);
    MessagePlugin.success('配置已删除');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="configs-page">
    <PageHeader
      title="全局配置"
      description="网关侧配置中心：分组键值条目，管理员维护；仅限非机密配置（机密走环境变量/加密凭证体系）。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="config-create-open" @click="openEdit(null)"
          >新增配置</t-button
        >
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div v-if="groups.length" class="mk-filter-bar">
      <t-radio-group
        v-model="activeGroup"
        variant="default-filled"
        data-testid="config-group-filter"
      >
        <t-radio-button value="">全部</t-radio-button>
        <t-radio-button v-for="g in groups" :key="g" :value="g">{{ g }}</t-radio-button>
      </t-radio-group>
    </div>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="filtered"
        class="configs-table"
        data-testid="configs-table"
      >
        <template #groupName="{ row }">
          <span class="mk-status mk-status--neutral">{{ row.groupName }}</span>
        </template>
        <template #key="{ row }">
          <span class="mk-mono config-key">{{ row.key }}</span>
        </template>
        <template #value="{ row }">
          <span class="config-value">{{ row.value }}</span>
        </template>
        <template #description="{ row }">
          <span class="config-desc">{{ row.description || '—' }}</span>
        </template>
        <template #updatedAt="{ row }">{{ formatTime(row.updatedAt) }}</template>
        <template #actions="{ row }">
          <t-button variant="text" data-testid="config-edit" @click="openEdit(row)">编辑</t-button>
          <t-button variant="text" theme="danger" data-testid="config-delete" @click="remove(row)"
            >删除</t-button
          >
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有配置项。</p>
            <p class="hint">点击「新增配置」添加分组键值条目。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="dialogVisible"
      :header="editing ? `编辑配置「${editing.groupName}/${editing.key}」` : '新增配置'"
      width="520px"
      :close-on-overlay-click="false"
    >
      <t-form label-align="top">
        <div class="form-row">
          <t-form-item label="分组" required-mark>
            <t-input
              v-model="form.group"
              :disabled="!!editing"
              placeholder="例如 gateway"
              data-testid="config-group"
            />
          </t-form-item>
          <t-form-item label="键" required-mark>
            <t-input
              v-model="form.key"
              :disabled="!!editing"
              placeholder="例如 max-streams"
              data-testid="config-key"
            />
          </t-form-item>
        </div>
        <t-form-item label="值" required-mark>
          <t-textarea v-model="form.value" placeholder="配置值" data-testid="config-value" />
        </t-form-item>
        <t-form-item label="描述">
          <t-input v-model="form.description" placeholder="用途说明（可选）" :maxlength="500" />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
      </t-form>
      <template #footer>
        <t-button theme="primary" :loading="saving" data-testid="config-save" @click="save"
          >保存</t-button
        >
        <t-button @click="dialogVisible = false">取消</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.configs-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.config-key {
  font-size: 13px;
}

.config-value {
  font-size: 13px;
  overflow-wrap: anywhere;
}

.config-desc {
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

.form-row {
  display: flex;
  gap: 12px;
}

.form-row .t-form__item {
  flex: 1;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}
</style>
