<script setup lang="ts">
/**
 * NextAdminConfigsView — /app/configs v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy configs page: grouped key-value entries,
 * group filter, inline dialog editor (group/key locked while editing) and
 * confirmed delete. Non-secret config only.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiTable, toast } from '@/ui';
import type {} from '@/types/api';
import type { ConfigEntryView } from '@/types/generated-api';

const entries = ref<ConfigEntryView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'groupName', title: '分组', width: '150px' },
  { key: 'key', title: '键', minWidth: '200px' },
  { key: 'value', title: '值', minWidth: '240px' },
  { key: 'description', title: '描述', minWidth: '180px' },
  { key: 'updatedAt', title: '更新时间', width: '180px' },
  { key: 'actions', title: '操作', width: '150px', align: 'center' as const },
];

const groupOptions = computed(() => {
  const set = new Set<string>();
  entries.value.forEach((e) => set.add(e.groupName));
  return [...set];
});

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

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    entries.value = await api.adminListConfigs();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
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
    : { group: activeGroup.value, key: '', value: '', description: '' };
  dialogVisible.value = true;
}

async function save() {
  if (!form.value.group.trim() || !form.value.key.trim()) {
    formError.value = '分组与键必填';
    return;
  }
  saving.value = true;
  formError.value = '';
  try {
    await api.adminPutConfig({
      group: form.value.group.trim(),
      key: form.value.key.trim(),
      value: form.value.value,
      description: form.value.description.trim() || undefined,
    });
    dialogVisible.value = false;
    toast.success(editing.value ? '配置已更新' : '配置已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function requestRemove(entry: ConfigEntryView) {
  confirmState.value = {
    title: '删除配置',
    body: `删除配置项「${entry.groupName}/${entry.key}」？该配置仅由控制台读取，删除即失效。`,
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDeleteConfig(entry.groupName, entry.key);
        toast.success('配置已删除');
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

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-configs">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">全局配置</h1>
        <p class="ui-page-desc">分组键值配置（非机密）；机密请走环境变量与加密凭证体系。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="config-create-open" @click="openEdit(null)">
          新增配置
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <div class="next-configs__segmented" data-testid="config-group-filter">
          <button
            type="button"
            class="next-configs__seg"
            :class="{ 'next-configs__seg--on': activeGroup === '' }"
            @click="activeGroup = ''"
          >
            全部
          </button>
          <button
            v-for="group in groupOptions"
            :key="group"
            type="button"
            class="next-configs__seg"
            :class="{ 'next-configs__seg--on': activeGroup === group }"
            @click="activeGroup = group"
          >
            {{ group }}
          </button>
        </div>
      </div>
      <UiTable
        :columns="columns"
        :data="filtered"
        :loading="loading"
        row-key="id"
        empty-title="还没有配置项"
        data-testid="configs-table"
      >
        <template #value="{ row }">
          <span class="ui-mono">{{ (row as ConfigEntryView).value }}</span>
        </template>
        <template #description="{ row }">{{
          (row as ConfigEntryView).description || '—'
        }}</template>
        <template #updatedAt="{ row }">{{
          formatTime((row as ConfigEntryView).updatedAt)
        }}</template>
        <template #actions="{ row }">
          <div class="next-configs__actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="config-edit"
              @click="openEdit(row as ConfigEntryView)"
              >编辑</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              class="next-configs__danger"
              data-testid="config-delete"
              @click="requestRemove(row as ConfigEntryView)"
              >删除</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <UiDialog
      :open="dialogVisible"
      :title="editing ? '编辑配置' : '新增配置'"
      width="480px"
      @update:open="dialogVisible = false"
    >
      <div class="next-configs__form">
        <UiInput
          v-model="form.group"
          label="分组"
          required
          :disabled="!!editing"
          placeholder="例如 gateway"
          data-testid="config-group"
        />
        <UiInput
          v-model="form.key"
          label="键"
          required
          :disabled="!!editing"
          placeholder="例如 cache_enabled"
          data-testid="config-key"
        />
        <div class="ui-field">
          <span class="ui-field__label">值</span>
          <textarea
            v-model="form.value"
            class="ui-textarea"
            rows="3"
            placeholder="配置值"
            data-testid="config-value"
          />
        </div>
        <UiInput
          v-model="form.description"
          label="描述"
          placeholder="（可选）"
          data-testid="config-desc"
        />
        <p v-if="formError" class="ui-form-error">{{ formError }}</p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="dialogVisible = false">取消</UiButton>
        <UiButton variant="primary" :loading="saving" data-testid="config-save" @click="save"
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

.next-configs__segmented {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-configs__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 1px solid transparent;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
}

.next-configs__seg--on {
  background: var(--ui-primary-soft);
  border-color: transparent;
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-configs__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-configs__danger {
  color: var(--ui-danger-fg);
}

.next-configs__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
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
  min-height: 72px;
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
</style>
