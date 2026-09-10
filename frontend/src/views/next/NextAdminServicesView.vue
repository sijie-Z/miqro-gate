<script setup lang="ts">
/**
 * NextAdminServicesView — /app/services v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy services page: register internal services
 * (platform components, MCP endpoints) that the gateway integrates with and
 * gated disable to remove a service from the usable registry.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { InternalServiceView } from '@/types/generated-api';

const services = ref<InternalServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'kind', title: '类型', width: '90px' },
  { key: 'description', title: '描述', minWidth: '180px' },
  { key: 'baseUrl', title: '服务地址', minWidth: '240px' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'healthStatus', title: '健康', width: '100px' },
  { key: 'healthCheckedAt', title: '最近检查', width: '160px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '190px', align: 'center' as const },
];

const kindOptions = [
  { value: 'HTTP', label: 'HTTP' },
  { value: 'MCP', label: 'MCP' },
  { value: 'OTHER', label: 'Other' },
];

const creating = ref(false);
const form = ref({ name: '', kind: 'HTTP', description: '', baseUrl: '' });
const formError = ref('');
const submitting = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.baseUrl.trim().length > 0,
);

// #326 runtime governance: health badge + config dialog.
const healthTarget = ref<InternalServiceView | null>(null);
const healthVisible = ref(false);
const healthForm = ref({
  checkIntervalSeconds: '30',
  checkTimeoutSeconds: '5',
  failThreshold: '3',
  recoverThreshold: '1',
  checkPath: '/health',
});
const healthSaving = ref(false);
const healthError = ref('');

function healthBadge(service: InternalServiceView): {
  tone: 'success' | 'danger' | 'neutral';
  label: string;
} {
  if (service.status !== 'ACTIVE') return { tone: 'neutral', label: '—' };
  switch (service.healthStatus) {
    case 'HEALTHY':
      return { tone: 'success', label: '健康' };
    case 'UNHEALTHY':
      return { tone: 'danger', label: '异常' };
    default:
      return { tone: 'neutral', label: '未知' };
  }
}

function openHealth(service: InternalServiceView) {
  healthTarget.value = service;
  healthForm.value = {
    checkIntervalSeconds: String(service.checkIntervalSeconds ?? 30),
    checkTimeoutSeconds: String(service.checkTimeoutSeconds ?? 5),
    failThreshold: String(service.failThreshold ?? 3),
    recoverThreshold: String(service.recoverThreshold ?? 1),
    checkPath: service.checkPath ?? '/health',
  };
  healthError.value = '';
  healthVisible.value = true;
}

async function saveHealth() {
  if (!healthTarget.value) return;
  healthSaving.value = true;
  healthError.value = '';
  try {
    await api.adminUpdateServiceHealthConfig(healthTarget.value.id!, {
      checkIntervalSeconds: Number(healthForm.value.checkIntervalSeconds),
      checkTimeoutSeconds: Number(healthForm.value.checkTimeoutSeconds),
      failThreshold: Number(healthForm.value.failThreshold),
      recoverThreshold: Number(healthForm.value.recoverThreshold),
      checkPath: healthForm.value.checkPath.trim(),
    });
    healthVisible.value = false;
    toast.success('健康检查配置已更新');
    await load();
  } catch (error) {
    healthError.value = error instanceof ApiError ? error.message : '更新失败';
  } finally {
    healthSaving.value = false;
  }
}

function requestEnable(service: InternalServiceView) {
  confirmState.value = {
    title: '启用服务「' + service.name + '」',
    body: '启用后该服务重新进入可用注册表并恢复健康探测。',
    confirmLabel: '启用',
    tone: 'primary',
    run: async () => {
      try {
        await api.adminEnableService(service.id!);
        toast.success('服务已启用');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
        }
      }
    },
  };
}

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
    toast.success('服务已注册');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

function requestDisable(service: InternalServiceView) {
  // Hub View schemas mark every field optional (springdoc omits `required`);
  // service rows always carry the id — the `!` restores the pre-hub contract.
  confirmState.value = {
    title: `禁用服务「${service.name}」`,
    body: '禁用后该服务从可用注册表中移除，注册信息保留。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDisableService(service.id!);
        toast.success('服务已禁用');
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
  <div class="ui-page next-services">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">服务管理</h1>
        <p class="ui-page-desc">
          内部服务注册表：平台组件、MCP 端点等经网关集成的服务；服务地址必须是 https。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="service-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '注册服务' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-services__create"
      data-testid="service-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">注册内部服务</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-services__hint">服务地址必须为 https、不含用户信息、查询参数或片段。</p>
        <div class="next-services__form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 platform-api"
            data-testid="service-create-name"
          />
          <UiSelect v-model="form.kind" label="类型" :options="kindOptions" />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="form.description"
              class="ui-textarea"
              rows="2"
              placeholder="用途说明（可选）"
              data-testid="service-create-desc"
            />
          </div>
          <UiInput
            v-model="form.baseUrl"
            label="服务地址"
            required
            placeholder="https://platform.internal.example"
            data-testid="service-create-url"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-services__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="service-create-submit"
              @click="createService"
              >注册</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ services.length }} 个服务</span>
      </div>
      <UiTable
        :columns="columns"
        :data="services"
        :loading="loading"
        row-key="id"
        empty-title="还没有注册的内部服务"
        empty-description="平台组件、MCP 端点等接入网关前先在此注册。"
        data-testid="services-table"
      >
        <template #name="{ row }">
          <span class="next-services__name">{{ (row as InternalServiceView).name }}</span>
        </template>
        <template #kind="{ row }">{{ (row as InternalServiceView).kind }}</template>
        <template #description="{ row }">{{
          (row as InternalServiceView).description || '—'
        }}</template>
        <template #baseUrl="{ row }">
          <span class="ui-mono next-services__url">{{ (row as InternalServiceView).baseUrl }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as InternalServiceView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as InternalServiceView).status === 'ACTIVE' ? '正常' : '已禁用'"
          />
        </template>
        <template #healthStatus="{ row }">
          <UiStatusBadge
            :tone="healthBadge(row as InternalServiceView).tone"
            :label="healthBadge(row as InternalServiceView).label"
            data-testid="service-health"
          />
        </template>
        <template #healthCheckedAt="{ row }">{{
          formatTime((row as InternalServiceView).healthCheckedAt)
        }}</template>
        <template #createdAt="{ row }">{{
          formatTime((row as InternalServiceView).createdAt)
        }}</template>
        <template #actions="{ row }">
          <UiButton
            v-if="(row as InternalServiceView).status === 'ACTIVE'"
            variant="ghost"
            size="sm"
            data-testid="service-health-config"
            @click="openHealth(row as InternalServiceView)"
            >健康检查</UiButton
          >
          <UiButton
            v-if="(row as InternalServiceView).status === 'ACTIVE'"
            variant="ghost"
            size="sm"
            class="next-services__danger"
            data-testid="service-disable"
            @click="requestDisable(row as InternalServiceView)"
            >禁用</UiButton
          >
          <UiButton
            v-if="(row as InternalServiceView).status !== 'ACTIVE'"
            variant="ghost"
            size="sm"
            data-testid="service-enable"
            @click="requestEnable(row as InternalServiceView)"
            >启用</UiButton
          >
        </template>
      </UiTable>
    </section>

    <!-- #326 health probe configuration -->
    <UiDialog
      v-if="healthTarget"
      :open="healthVisible"
      :title="'健康检查 — ' + healthTarget.name"
      description="按各自间隔探测服务地址 + 检查路径（GET，2xx 计健康）；连续失败/成功达阈值后在健康/异常间迁移。禁用状态不被探测。"
      width="540px"
      @update:open="healthVisible = false"
    >
      <div class="next-services__health-grid">
        <UiInput
          v-model="healthForm.checkIntervalSeconds"
          label="探测间隔（秒）"
          data-testid="service-health-interval"
        />
        <UiInput
          v-model="healthForm.checkTimeoutSeconds"
          label="超时（秒）"
          data-testid="service-health-timeout"
        />
        <UiInput
          v-model="healthForm.failThreshold"
          label="失败阈值"
          data-testid="service-health-fail"
        />
        <UiInput
          v-model="healthForm.recoverThreshold"
          label="恢复阈值"
          data-testid="service-health-recover"
        />
        <UiInput
          v-model="healthForm.checkPath"
          label="检查路径"
          data-testid="service-health-path"
        />
      </div>
      <p v-if="healthError" class="ui-form-error">{{ healthError }}</p>
      <template #footer>
        <UiButton variant="ghost" @click="healthVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="healthSaving"
          data-testid="service-health-save"
          @click="saveHealth"
        >
          保存
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
  min-height: 56px;
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

.next-services__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-services__hint {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-services__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.next-services__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-services__name {
  font-weight: var(--ui-weight-medium);
}

.next-services__url {
  font-size: var(--ui-font-size-xs);
  overflow-wrap: anywhere;
}

.next-services__danger {
  color: var(--ui-danger-fg);
}
.next-services__health-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-3);
}
</style>
