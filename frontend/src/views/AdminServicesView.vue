<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { InternalServiceView } from '@/types/api';

const services = ref<InternalServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 160 },
  { colKey: 'kind', title: '类型', width: 90 },
  { colKey: 'description', title: '描述', minWidth: 180 },
  { colKey: 'baseUrl', title: '服务地址', minWidth: 220 },
  { colKey: 'status', title: '状态', width: 100 },
  { colKey: 'createdAt', title: '创建时间', width: 180 },
  { colKey: 'actions', title: '操作', width: 100, fixed: 'right' },
];

const creating = ref(false);
const form = ref({ name: '', kind: 'HTTP', description: '', baseUrl: '' });
const formError = ref('');
const submitting = ref(false);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.baseUrl.trim().length > 0,
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    services.value = await api.adminListServices();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载服务列表失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function createService() {
  if (!canCreate.value) {
    formError.value = '请填写名称与服务地址。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.adminCreateService({
      name: form.value.name.trim(),
      kind: form.value.kind,
      description: form.value.description.trim() || undefined,
      baseUrl: form.value.baseUrl.trim(),
    });
    creating.value = false;
    form.value = { name: '', kind: 'HTTP', description: '', baseUrl: '' };
    MessagePlugin.success('服务已注册');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function disable(service: InternalServiceView) {
  try {
    await confirmDialog({
      header: `禁用服务「${service.name}」`,
      body: '禁用后该服务从可用注册表中移除，注册信息保留。',
      confirmBtn: '禁用',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.adminDisableService(service.id);
    MessagePlugin.success('服务已禁用');
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
  <div class="services-page">
    <PageHeader
      title="服务管理"
      description="内部服务注册表：平台组件、MCP 端点等经网关集成的服务；服务地址必须是 https。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="service-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '注册服务' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="service-create-form">
      <h3 class="panel-title">注册内部服务</h3>
      <p class="hint">服务地址必须为 https、不含用户信息、查询参数或片段。</p>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input
            v-model="form.name"
            placeholder="例如 platform-api"
            data-testid="service-create-name"
          />
        </t-form-item>
        <t-form-item label="类型">
          <t-select v-model="form.kind">
            <t-option label="HTTP" value="HTTP" />
            <t-option label="MCP" value="MCP" />
            <t-option label="Other" value="OTHER" />
          </t-select>
        </t-form-item>
        <t-form-item label="描述">
          <t-input v-model="form.description" placeholder="用途说明（可选）" />
        </t-form-item>
        <t-form-item label="服务地址" required-mark>
          <t-input
            v-model="form.baseUrl"
            placeholder="https://platform.internal.example"
            data-testid="service-create-url"
          />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :disabled="!canCreate"
          :loading="submitting"
          data-testid="service-create-submit"
          @click="createService"
        >
          注册
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="services"
        class="services-table"
        data-testid="services-table"
      >
        <template #name="{ row }">
          <span class="service-name">{{ row.name }}</span>
        </template>
        <template #kind="{ row }">
          <span class="mk-status mk-status--neutral">{{ row.kind }}</span>
        </template>
        <template #description="{ row }">
          <span class="service-desc">{{ row.description || '—' }}</span>
        </template>
        <template #baseUrl="{ row }">
          <span class="mk-mono service-url">{{ row.baseUrl }}</span>
        </template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.status === 'ACTIVE' ? 'Active' : 'Disabled' }}
          </span>
        </template>
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
        <template #actions="{ row }">
          <t-button
            v-if="row.status === 'ACTIVE'"
            variant="text"
            theme="danger"
            data-testid="service-disable"
            @click="disable(row)"
          >
            禁用
          </t-button>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有注册的内部服务。</p>
            <p class="hint">平台组件、MCP 端点等接入网关前先在此注册。</p>
          </div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.create-form {
  max-width: 520px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.services-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.service-name {
  font-weight: 500;
}

.service-desc {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.service-url {
  font-size: 12px;
  overflow-wrap: anywhere;
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
</style>
