<script setup lang="ts">
/**
 * NextAdminMcpServicesView — /app/mcp-services v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy MCP console: register Streamable HTTP/SSE
 * servers, manual online/offline (gated), health-check config, tool registry
 * with enable/disable and the two-level access control (Tencent doc 134890):
 * server mode (open/allowlist/denylist) + per-tool overrides while the server
 * stays fully open. Multi-selects render as checkbox groups (the v2 select is
 * single-value; consumer lists stay short at console scale).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type {McpAclMode, McpRouteRule, UpsertMcpRouteRuleRequest} from '@/types/api';
import type { ApiConsumerView, McpAccessView, McpServiceView, McpToolView } from '@/types/generated-api';

const services = ref<McpServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'endpoint', title: '接入地址', minWidth: '220px' },
  { key: 'transport', title: '传输', width: '130px' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'healthStatus', title: '健康', width: '100px' },
  { key: 'healthCheckedAt', title: '最近检查', width: '170px' },
  { key: 'actions', title: '操作', width: '280px' },
];

const transportOptions = [
  { value: 'STREAMABLE_HTTP', label: 'Streamable HTTP' },
  { value: 'SSE', label: 'SSE' },
];

const methodOptions = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' },
  { value: 'PATCH', label: 'PATCH' },
];

const modeOptions: { value: string; label: string }[] = [
  { value: '', label: '继承服务规则' },
  { value: 'ALLOW', label: '仅名单内可调用' },
  { value: 'DENY', label: '名单内禁止' },
];

const registering = ref(false);
const form = ref({
  name: '',
  description: '',
  endpoint: '',
  transport: 'STREAMABLE_HTTP',
});
const formError = ref('');
const submitting = ref(false);

// Health config dialog
const configService = ref<McpServiceView | null>(null);
const configVisible = ref(false);
const configForm = ref({
  checkIntervalSeconds: '30',
  checkTimeoutSeconds: '5',
  failThreshold: '3',
  recoverThreshold: '1',
  checkPath: '/health',
});
const configSaving = ref(false);
const configError = ref('');

// Tools dialog
const toolsService = ref<McpServiceView | null>(null);
const toolsVisible = ref(false);
const tools = ref<McpToolView[]>([]);
const toolsLoading = ref(false);
const toolsError = ref('');
const toolForm = ref({ toolName: '', description: '', method: 'GET', path: '' });
const toolSaving = ref(false);
const toolFormError = ref('');
const toolCreating = ref(false);

// Access control dialog
const accessService = ref<McpServiceView | null>(null);
const accessVisible = ref(false);
const accessLoading = ref(false);
const accessError = ref('');
const access = ref<McpAccessView | null>(null);
const consumers = ref<ApiConsumerView[]>([]);
const serverMode = ref<McpAclMode>('NONE');
const serverIds = ref<string[]>([]);
const serverSaving = ref(false);
const serverResetSaving = ref(false);
const accessNotice = ref('');
/** Draft overrides per tool: null = inherit, otherwise ALLOW/DENY + ids. */
const toolDrafts = ref<Record<string, { mode: McpAclMode | null; ids: string[] }>>({});

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const consumerOptions = computed(() =>
  consumers.value.map((c) => ({
    id: c.id,
    label: `${c.name}（${c.keyPrefix}…）`,
  })),
);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.endpoint.trim().length > 0,
);

const canCreateTool = computed(
  () => toolForm.value.toolName.trim().length > 0 && toolForm.value.path.trim().length > 0,
);

function healthLabel(status: string): string {
  return status === 'HEALTHY' ? '健康' : status === 'UNHEALTHY' ? '不健康' : '未知';
}

function healthTone(status: string): 'success' | 'danger' | 'neutral' {
  return status === 'HEALTHY' ? 'success' : status === 'UNHEALTHY' ? 'danger' : 'neutral';
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    services.value = await api.adminListMcpServices();
  } catch (error) {
    loadError.value = errorText(error, '加载 MCP 服务失败。');
    if (error instanceof ApiError) {
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function registerService() {
  if (!canCreate.value) {
    formError.value = '请填写名称与接入地址。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.adminCreateMcpService({
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      endpoint: form.value.endpoint.trim(),
      transport: form.value.transport,
    });
    registering.value = false;
    form.value = { name: '', description: '', endpoint: '', transport: 'STREAMABLE_HTTP' };
    toast.success('MCP 服务已注册');
    await load();
  } catch (error) {
    formError.value = errorText(error, '创建失败，请稍后重试。');
  } finally {
    submitting.value = false;
  }
}

function requestStatusChange(service: McpServiceView, status: string) {
  const action = status === 'ONLINE' ? '上线' : '下线';
  confirmState.value = {
    title: `${action} MCP 服务「${service.name}」`,
    body:
      status === 'OFFLINE'
        ? '下线后该服务暂停对外提供，健康检查不会自动恢复，需手动上线。'
        : '上线后服务恢复对外提供。',
    confirmLabel: action,
    tone: status === 'OFFLINE' ? 'danger' : 'primary',
    run: async () => {
      try {
        await api.adminSetMcpStatus(service.id, status);
        toast.success(`服务已${action}`);
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

// ---- health config ----

function openConfig(service: McpServiceView) {
  configService.value = service;
  configForm.value = {
    checkIntervalSeconds: String(service.checkIntervalSeconds),
    checkTimeoutSeconds: String(service.checkTimeoutSeconds),
    failThreshold: String(service.failThreshold),
    recoverThreshold: String(service.recoverThreshold),
    checkPath: service.checkPath,
  };
  configError.value = '';
  configVisible.value = true;
}

async function saveConfig() {
  if (!configService.value) {
    return;
  }
  configSaving.value = true;
  configError.value = '';
  try {
    await api.adminUpdateMcpHealthConfig(configService.value.id, {
      checkIntervalSeconds: Number(configForm.value.checkIntervalSeconds),
      checkTimeoutSeconds: Number(configForm.value.checkTimeoutSeconds),
      failThreshold: Number(configForm.value.failThreshold),
      recoverThreshold: Number(configForm.value.recoverThreshold),
      checkPath: configForm.value.checkPath.trim(),
    });
    configVisible.value = false;
    toast.success('健康检查配置已更新');
    await load();
  } catch (error) {
    configError.value = errorText(error, '保存失败，请稍后重试。');
  } finally {
    configSaving.value = false;
  }
}

// ---- tools ----

async function openTools(service: McpServiceView) {
  toolsService.value = service;
  tools.value = [];
  toolsError.value = '';
  toolForm.value = { toolName: '', description: '', method: 'GET', path: '' };
  toolCreating.value = false;
  toolsVisible.value = true;
  toolsLoading.value = true;
  try {
    tools.value = await api.adminListMcpTools(service.id);
  } catch (error) {
    toolsError.value = errorText(error, '加载工具失败。');
  } finally {
    toolsLoading.value = false;
  }
}

async function refreshTools() {
  if (!toolsService.value) {
    return;
  }
  tools.value = await api.adminListMcpTools(toolsService.value.id);
}

async function createTool() {
  if (!toolsService.value || !canCreateTool.value) {
    toolFormError.value = '请填写工具名与路径。';
    return;
  }
  toolSaving.value = true;
  toolFormError.value = '';
  try {
    await api.adminCreateMcpTool(toolsService.value.id, {
      toolName: toolForm.value.toolName.trim(),
      description: toolForm.value.description.trim() || undefined,
      method: toolForm.value.method,
      path: toolForm.value.path.trim(),
    });
    toolCreating.value = false;
    toolForm.value = { toolName: '', description: '', method: 'GET', path: '' };
    toast.success('工具已创建');
    await refreshTools();
  } catch (error) {
    toolFormError.value = errorText(error, '创建失败，请稍后重试。');
  } finally {
    toolSaving.value = false;
  }
}

async function setToolStatus(tool: McpToolView, status: string) {
  if (!toolsService.value) {
    return;
  }
  const action = status === 'ENABLED' ? '启用' : '禁用';
  try {
    await api.adminSetMcpToolStatus(toolsService.value.id, tool.id, status);
    toast.success(`工具已${action}`);
    await refreshTools();
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

// ---- access control ----

function toolDraft(toolId: string, mode: McpAclMode | null, ids: string[]) {
  toolDrafts.value[toolId] = { mode, ids: [...ids] };
}

function toggleToolConsumer(toolId: string, consumerId: string, checked: boolean) {
  const draft = toolDrafts.value[toolId];
  if (!draft) return;
  toolDraft(
    toolId,
    draft.mode,
    checked ? [...draft.ids, consumerId] : draft.ids.filter((id) => id !== consumerId),
  );
}

async function openAccess(service: McpServiceView) {
  accessService.value = service;
  accessVisible.value = true;
  accessError.value = '';
  toolDrafts.value = {};
  await loadAccess();
}

async function loadAccess() {
  if (!accessService.value) return;
  accessLoading.value = true;
  accessError.value = '';
  try {
    const [view, consumerList] = await Promise.all([
      api.getMcpServiceAccess(accessService.value.id),
      api.listApiConsumers(),
    ]);
    access.value = view;
    consumers.value = consumerList;
    serverMode.value = view.mode;
    serverIds.value = view.serverConsumers.map((c) => c.id);
    for (const tool of view.tools) {
      toolDraft(
        tool.toolId,
        tool.mode,
        tool.consumers.map((c) => c.id),
      );
    }
    accessNotice.value =
      view.mode === 'NONE'
        ? '全部开放：任何调用方均可访问。可在下方为单个工具配置更细的名单（工具级规则只会进一步收窄）。'
        : view.mode === 'ALLOW'
          ? '白名单：仅名单内的 API 消费者可调用该服务。'
          : '黑名单：名单内的 API 消费者被禁止调用，其余放行。';
  } catch (err) {
    accessError.value = errorText(err, '加载失败');
  } finally {
    accessLoading.value = false;
  }
}

async function saveServerMode() {
  if (!accessService.value) return;
  serverSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessMode(accessService.value.id, serverMode.value);
    toast.success('服务访问模式已保存');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    serverSaving.value = false;
  }
}

async function saveServerList() {
  if (!accessService.value || serverMode.value === 'NONE') return;
  serverSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessGrants(accessService.value.id, {
      mode: serverMode.value,
      consumerIds: serverIds.value,
    });
    toast.success('服务名单已更新');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    serverSaving.value = false;
  }
}

async function resetServerAccess() {
  if (!accessService.value) return;
  serverResetSaving.value = true;
  accessError.value = '';
  try {
    await api.setMcpAccessMode(accessService.value.id, 'NONE');
    toast.success('已重置为全部开放');
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '重置失败');
  } finally {
    serverResetSaving.value = false;
  }
}

async function saveToolDraft(toolId: string, toolName: string) {
  if (!accessService.value) return;
  const draft = toolDrafts.value[toolId];
  if (!draft) return;
  toolSaving.value = true;
  accessError.value = '';
  try {
    if (draft.mode === null) {
      await api.clearMcpAccessGrants(accessService.value.id, toolId);
      toast.success(`${toolName} 已恢复为继承服务规则`);
    } else {
      await api.setMcpAccessGrants(accessService.value.id, {
        toolId,
        mode: draft.mode,
        consumerIds: draft.ids,
      });
      toast.success(`${toolName} 的访问名单已更新`);
    }
    await loadAccess();
  } catch (err) {
    accessError.value = errorText(err, '保存失败');
  } finally {
    toolSaving.value = false;
  }
}

// ---- route rules (F11, Tencent doc 135482) ----

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];
const MATCH_MODES = [
  { value: '', label: '不限' },
  { value: 'EXACT', label: '精确' },
  { value: 'PREFIX', label: '前缀' },
  { value: 'REGEX', label: '正则' },
] as const;

interface HeaderDraft {
  name: string;
  mode: 'EXACT' | 'PREFIX' | 'REGEX';
  value: string;
}

const rulesService = ref<McpServiceView | null>(null);
const rulesVisible = ref(false);
const rules = ref<McpRouteRule[]>([]);
const rulesLoading = ref(false);
const rulesError = ref('');

const routeDialogVisible = ref(false);
const routeEditing = ref<McpRouteRule | null>(null);
const routeForm = ref({
  name: '',
  description: '',
  priority: '1000',
  pathMode: '' as '' | 'EXACT' | 'PREFIX' | 'REGEX',
  pathValue: '',
  hostMode: '' as '' | 'EXACT' | 'PREFIX' | 'REGEX',
  hostValue: '',
  methods: new Set<string>(),
  headers: [] as HeaderDraft[],
});
const routeSaving = ref(false);
const routeFormError = ref('');

const routeConfirm = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function methodList(rule: McpRouteRule): string {
  if (!rule.methods) return '全部方法';
  const list = rule.methods.split(',');
  return list.length === HTTP_METHODS.length ? '全部方法' : list.join(' / ');
}

function conditionText(rule: McpRouteRule): string {
  const parts: string[] = [];
  if (rule.pathMode) {
    const mode = MATCH_MODES.find((m) => m.value === rule.pathMode)?.label ?? rule.pathMode;
    parts.push(`路径 ${mode} ${rule.pathValue}`);
  }
  if (rule.hostMode) {
    const mode = MATCH_MODES.find((m) => m.value === rule.hostMode)?.label ?? rule.hostMode;
    parts.push(`Host ${mode} ${rule.hostValue}`);
  }
  if (rule.headerConditions.length) {
    parts.push(
      ...rule.headerConditions.map(
        (h) =>
          `${h.name} ${MATCH_MODES.find((m) => m.value === h.mode)?.label ?? h.mode} ${h.value}`,
      ),
    );
  }
  if (!parts.length) return '全部请求（兜底）';
  return parts.join(' · ');
}

async function openRoutes(service: McpServiceView) {
  rulesService.value = service;
  rules.value = [];
  rulesError.value = '';
  rulesVisible.value = true;
  rulesLoading.value = true;
  try {
    rules.value = await api.adminListMcpRouteRules(service.id);
  } catch (error) {
    rulesError.value = errorText(error, '加载路由规则失败。');
  } finally {
    rulesLoading.value = false;
  }
}

function emptyRouteForm() {
  return {
    name: '',
    description: '',
    priority: '1000',
    pathMode: '' as '' | 'EXACT' | 'PREFIX' | 'REGEX',
    pathValue: '',
    hostMode: '' as '' | 'EXACT' | 'PREFIX' | 'REGEX',
    hostValue: '',
    methods: new Set<string>(),
    headers: [] as HeaderDraft[],
  };
}

function openRouteCreate() {
  routeEditing.value = null;
  routeForm.value = emptyRouteForm();
  routeFormError.value = '';
  routeDialogVisible.value = true;
}

function openRouteEdit(rule: McpRouteRule) {
  routeEditing.value = rule;
  routeForm.value = {
    name: rule.name,
    description: rule.description ?? '',
    priority: String(rule.priority),
    pathMode: rule.pathMode ?? '',
    pathValue: rule.pathValue ?? '',
    hostMode: rule.hostMode ?? '',
    hostValue: rule.hostValue ?? '',
    methods: new Set(rule.methods ? rule.methods.split(',') : HTTP_METHODS),
    headers: rule.headerConditions.map((h) => ({ name: h.name, mode: h.mode, value: h.value })),
  };
  routeFormError.value = '';
  routeDialogVisible.value = true;
}

function addHeaderRow() {
  if (routeForm.value.headers.length >= 8) return;
  routeForm.value.headers.push({ name: '', mode: 'EXACT', value: '' });
}

function removeHeaderRow(index: number) {
  routeForm.value.headers.splice(index, 1);
}

async function saveRouteRule() {
  const form = routeForm.value;
  if (!form.name.trim()) {
    routeFormError.value = '路由名称必填。';
    return;
  }
  if ((form.pathMode && !form.pathValue.trim()) || (form.hostMode && !form.hostValue.trim())) {
    routeFormError.value = '选择了匹配方式后必须填写匹配值。';
    return;
  }
  for (const [index, header] of form.headers.entries()) {
    if (!header.name.trim()) {
      routeFormError.value = `第 ${index + 1} 条 Header 条件缺少名称。`;
      return;
    }
    if (!header.value.trim()) {
      routeFormError.value = `第 ${index + 1} 条 Header 条件缺少匹配值。`;
      return;
    }
  }
  if (!rulesService.value) return;
  const serviceId = rulesService.value.id;
  const body: UpsertMcpRouteRuleRequest = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    priority: Number(form.priority) || 1000,
    pathMode: form.pathMode || null,
    pathValue: form.pathMode ? form.pathValue.trim() : null,
    hostMode: form.hostMode || null,
    hostValue: form.hostMode ? form.hostValue.trim() : null,
    methods: form.methods.size === HTTP_METHODS.length ? null : [...form.methods],
    headers: form.headers
      .filter((h) => h.name.trim())
      .map((h) => ({ name: h.name.trim(), mode: h.mode, value: h.value.trim() })),
  };
  routeSaving.value = true;
  routeFormError.value = '';
  try {
    if (routeEditing.value) {
      await api.adminUpdateMcpRouteRule(serviceId, routeEditing.value.id, body);
      toast.success('路由已更新');
    } else {
      await api.adminCreateMcpRouteRule(serviceId, body);
      toast.success('路由已创建');
    }
    routeDialogVisible.value = false;
    rules.value = await api.adminListMcpRouteRules(serviceId);
  } catch (error) {
    routeFormError.value = errorText(
      error,
      routeEditing.value ? '保存失败，请稍后重试。' : '创建失败，请稍后重试。',
    );
  } finally {
    routeSaving.value = false;
  }
}

async function toggleRouteStatus(rule: McpRouteRule) {
  if (!rulesService.value) return;
  const next = rule.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  try {
    await api.adminSetMcpRouteStatus(rulesService.value.id, rule.id, next);
    toast.success(rule.status === 'ENABLED' ? '路由已停用' : '路由已启用');
    rules.value = await api.adminListMcpRouteRules(rulesService.value.id);
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

function requestRouteDelete(rule: McpRouteRule) {
  routeConfirm.value = {
    title: `删除路由「${rule.name}」`,
    body: '删除后该路由不再参与匹配（默认路由不可删除）。',
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      if (!rulesService.value) return;
      try {
        await api.adminDeleteMcpRouteRule(rulesService.value.id, rule.id);
        toast.success('路由已删除');
        rules.value = await api.adminListMcpRouteRules(rulesService.value.id);
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
        }
      }
    },
  };
}

async function routeConfirmAndRun() {
  const state = routeConfirm.value;
  if (!state) return;
  routeConfirm.value = null;
  await state.run();
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-mcp">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">MCP 服务</h1>
        <p class="ui-page-desc">
          MCP Server 管理：注册、手动上下线（下线后健康检查不会自动恢复）、健康检查配置与状态。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="mcp-create-open"
          @click="registering = !registering"
        >
          {{ registering ? '收起表单' : '注册 MCP 服务' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="registering" class="ui-panel next-mcp__register" data-testid="mcp-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">注册 MCP 服务</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-mcp__hint">
          接入地址必须为 https；健康检查默认每 30 秒探测 /health，连续失败 3 次标记不健康。
        </p>
        <div class="next-mcp__register-form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 erp-mcp"
            data-testid="mcp-create-name"
          />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="form.description"
              class="ui-textarea"
              rows="2"
              placeholder="用途说明（可选）"
              data-testid="mcp-create-desc"
            />
          </div>
          <div class="next-mcp__row">
            <UiSelect v-model="form.transport" label="传输类型" :options="transportOptions" />
            <UiInput
              v-model="form.endpoint"
              label="接入地址"
              required
              placeholder="https://erp.internal.example"
              data-testid="mcp-create-endpoint"
            />
          </div>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-mcp__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="mcp-create-submit"
              @click="registerService"
              >注册</UiButton
            >
            <UiButton variant="ghost" @click="registering = false">取消</UiButton>
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
        empty-title="还没有注册的 MCP 服务"
        empty-description="注册后网关定期探测健康状态，Agent 可经网关调用其 Tools。"
        data-testid="mcp-table"
      >
        <template #name="{ row }">
          <span class="next-mcp__name">{{ (row as McpServiceView).name }}</span>
        </template>
        <template #endpoint="{ row }">
          <span class="ui-mono next-mcp__endpoint">{{ (row as McpServiceView).endpoint }}</span>
        </template>
        <template #transport="{ row }">
          <span class="ui-mono">{{ (row as McpServiceView).transport }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as McpServiceView).status === 'ONLINE' ? 'success' : 'neutral'"
            :label="(row as McpServiceView).status === 'ONLINE' ? '在线' : '已下线'"
          />
        </template>
        <template #healthStatus="{ row }">
          <UiStatusBadge
            :tone="healthTone((row as McpServiceView).healthStatus)"
            :label="healthLabel((row as McpServiceView).healthStatus)"
          />
        </template>
        <template #healthCheckedAt="{ row }">{{
          formatTime((row as McpServiceView).healthCheckedAt)
        }}</template>
        <template #actions="{ row }">
          <div class="next-mcp__row-actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-tools"
              @click="openTools(row as McpServiceView)"
              >Tools</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-access"
              @click="openAccess(row as McpServiceView)"
              >访问控制</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-routes"
              @click="openRoutes(row as McpServiceView)"
              >路由规则</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-health-config"
              @click="openConfig(row as McpServiceView)"
              >健康检查</UiButton
            >
            <UiButton
              v-if="(row as McpServiceView).status === 'ONLINE'"
              variant="ghost"
              size="sm"
              class="next-mcp__danger"
              data-testid="mcp-offline"
              @click="requestStatusChange(row as McpServiceView, 'OFFLINE')"
              >下线</UiButton
            >
            <UiButton
              v-else
              variant="ghost"
              size="sm"
              data-testid="mcp-online"
              @click="requestStatusChange(row as McpServiceView, 'ONLINE')"
              >上线</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <!-- Health check configuration -->
    <UiDialog
      :open="configVisible"
      :title="configService ? `健康检查 · ${configService.name}` : '健康检查'"
      width="520px"
      @update:open="configVisible = false"
    >
      <div class="next-mcp__dialog-form">
        <div class="next-mcp__row">
          <UiInput
            v-model="configForm.checkIntervalSeconds"
            label="检查间隔（秒）"
            data-testid="mcp-check-interval"
          />
          <UiInput
            v-model="configForm.checkTimeoutSeconds"
            label="超时（秒）"
            data-testid="mcp-check-timeout"
          />
        </div>
        <div class="next-mcp__row">
          <UiInput
            v-model="configForm.failThreshold"
            label="失败阈值"
            data-testid="mcp-check-fail"
          />
          <UiInput
            v-model="configForm.recoverThreshold"
            label="恢复阈值"
            data-testid="mcp-check-recover"
          />
        </div>
        <UiInput
          v-model="configForm.checkPath"
          label="检查路径"
          placeholder="/health"
          data-testid="mcp-check-path"
        />
        <p v-if="configError" class="ui-form-error">{{ configError }}</p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="configVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="configSaving"
          data-testid="mcp-config-save"
          @click="saveConfig"
          >保存</UiButton
        >
      </template>
    </UiDialog>

    <!-- Tool registry -->
    <UiDialog
      :open="toolsVisible"
      :title="toolsService ? `Tools · ${toolsService.name}` : 'Tools'"
      width="640px"
      @update:open="toolsVisible = false"
    >
      <div v-if="toolsError" class="ui-alert ui-alert--error">{{ toolsError }}</div>
      <div v-if="toolsLoading" class="next-mcp__tools-loading">
        <div v-for="n in 3" :key="n" class="ui-skeleton next-mcp__tool-skeleton">&nbsp;</div>
      </div>
      <template v-else>
        <div v-if="tools.length" class="next-mcp__tool-list" data-testid="mcp-tool-list">
          <div v-for="tool in tools" :key="tool.id" class="next-mcp__tool-row">
            <div class="next-mcp__tool-info">
              <span class="ui-mono next-mcp__tool-name">{{ tool.toolName }}</span>
              <span class="next-mcp__tool-desc">{{ tool.description || '—' }}</span>
              <span class="ui-mono next-mcp__tool-path">{{ tool.method }} {{ tool.path }}</span>
            </div>
            <UiStatusBadge
              :tone="tool.status === 'ENABLED' ? 'success' : 'neutral'"
              :label="tool.status === 'ENABLED' ? '已启用' : '已禁用'"
            />
            <UiButton
              v-if="tool.status === 'ENABLED'"
              variant="ghost"
              size="sm"
              class="next-mcp__danger"
              data-testid="mcp-tool-disable"
              @click="setToolStatus(tool, 'DISABLED')"
              >禁用</UiButton
            >
            <UiButton
              v-else
              variant="ghost"
              size="sm"
              data-testid="mcp-tool-enable"
              @click="setToolStatus(tool, 'ENABLED')"
              >启用</UiButton
            >
          </div>
        </div>
        <p v-else class="next-mcp__tool-empty">
          该服务还没有工具。<br /><span class="next-mcp__hint"
            >手动创建工具后，AI Agent 即可按工具名调用。</span
          >
        </p>
        <div class="next-mcp__tool-create">
          <UiButton
            variant="secondary"
            size="sm"
            data-testid="mcp-tool-create-open"
            @click="toolCreating = !toolCreating"
          >
            {{ toolCreating ? '收起表单' : '新建工具' }}
          </UiButton>
          <div
            v-if="toolCreating"
            class="next-mcp__tool-create-form"
            data-testid="mcp-tool-create-form"
          >
            <div class="next-mcp__row">
              <UiInput
                v-model="toolForm.toolName"
                label="工具名"
                required
                placeholder="query_order"
                data-testid="mcp-tool-name"
              />
              <UiSelect v-model="toolForm.method" label="方法" :options="methodOptions" />
            </div>
            <UiInput
              v-model="toolForm.path"
              label="路径"
              required
              placeholder="/orders/{id}"
              data-testid="mcp-tool-path"
            />
            <UiInput
              v-model="toolForm.description"
              label="描述"
              placeholder="工具用途（可选）"
              data-testid="mcp-tool-desc"
            />
            <p v-if="toolFormError" class="ui-form-error">{{ toolFormError }}</p>
            <div class="next-mcp__actions">
              <UiButton
                variant="primary"
                size="sm"
                :disabled="!canCreateTool"
                :loading="toolSaving"
                data-testid="mcp-tool-create-submit"
                @click="createTool"
                >创建</UiButton
              >
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <UiButton variant="secondary" @click="toolsVisible = false">关闭</UiButton>
      </template>
    </UiDialog>

    <!-- Two-level access control -->
    <UiDialog
      :open="accessVisible"
      :title="'访问控制 · ' + (accessService?.name ?? '')"
      width="780px"
      data-testid="mcp-access-dialog"
      @update:open="accessVisible = false"
    >
      <div v-if="accessError" class="ui-alert ui-alert--error">{{ accessError }}</div>
      <div v-if="accessLoading" class="next-mcp__access-loading">
        <div v-for="n in 3" :key="n" class="ui-skeleton next-mcp__tool-skeleton">&nbsp;</div>
      </div>
      <template v-else-if="access">
        <p class="next-mcp__access-notice">{{ accessNotice }}</p>

        <section class="next-mcp__access-section">
          <h3 class="next-mcp__access-title">服务级访问（谁能调用整个服务）</h3>
          <div class="next-mcp__access-row">
            <div class="next-mcp__segmented" data-testid="mcp-access-mode">
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'NONE' }"
                @click="serverMode = 'NONE'"
              >
                全部开放
              </button>
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'ALLOW' }"
                @click="serverMode = 'ALLOW'"
              >
                白名单
              </button>
              <button
                type="button"
                class="next-mcp__seg"
                :class="{ 'next-mcp__seg--on': serverMode === 'DENY' }"
                @click="serverMode = 'DENY'"
              >
                黑名单
              </button>
            </div>
            <UiButton
              variant="secondary"
              size="sm"
              :loading="serverSaving"
              data-testid="mcp-access-mode-save"
              @click="saveServerMode"
              >保存模式</UiButton
            >
          </div>
          <div v-if="serverMode !== 'NONE'" class="next-mcp__access-list-block">
            <span class="ui-field__label"
              >名单（{{ serverMode === 'ALLOW' ? '白名单' : '黑名单' }}）</span
            >
            <div class="next-mcp__check-list" data-testid="mcp-access-server-list">
              <label v-for="c in consumerOptions" :key="c.id" class="next-mcp__check">
                <input
                  v-model="serverIds"
                  type="checkbox"
                  :value="c.id"
                  data-testid="mcp-access-consumer"
                />
                <span>{{ c.label }}</span>
              </label>
              <p v-if="!consumerOptions.length" class="ui-field__hint">暂无 API 消费者</p>
            </div>
            <div class="next-mcp__actions">
              <UiButton
                variant="primary"
                size="sm"
                :loading="serverSaving"
                data-testid="mcp-access-server-save"
                @click="saveServerList"
                >保存{{ serverMode === 'ALLOW' ? '白' : '黑' }}名单</UiButton
              >
              <UiButton
                variant="secondary"
                size="sm"
                :loading="serverResetSaving"
                data-testid="mcp-access-server-reset"
                @click="resetServerAccess"
                >重置为全部开放</UiButton
              >
            </div>
          </div>
        </section>

        <section v-if="serverMode === 'NONE'" class="next-mcp__access-section">
          <h3 class="next-mcp__access-title">
            工具级访问（谁可调用某个工具；仅服务全开放时可配置）
          </h3>
          <div class="next-mcp__tool-access-list" data-testid="mcp-access-tools">
            <div
              v-for="tool in access.tools"
              :key="tool.toolId"
              class="next-mcp__tool-access"
              :data-tool-id="tool.toolId"
            >
              <div class="next-mcp__tool-access-head">
                <span class="ui-mono next-mcp__tool-name">{{ tool.toolName }}</span>
                <div
                  class="next-mcp__segmented next-mcp__segmented--sm"
                  data-testid="mcp-access-tool-mode"
                >
                  <button
                    v-for="opt in modeOptions"
                    :key="opt.value"
                    type="button"
                    class="next-mcp__seg"
                    :class="{
                      'next-mcp__seg--on':
                        (toolDrafts[tool.toolId]?.mode ?? null) ===
                        (opt.value === '' ? null : opt.value),
                    }"
                    @click="
                      toolDraft(
                        tool.toolId,
                        opt.value === '' ? null : (opt.value as 'ALLOW' | 'DENY'),
                        toolDrafts[tool.toolId]?.ids ?? [],
                      )
                    "
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </div>
              <div
                v-if="toolDrafts[tool.toolId]?.mode"
                class="next-mcp__check-list next-mcp__check-list--nested"
              >
                <label v-for="c in consumerOptions" :key="c.id" class="next-mcp__check">
                  <input
                    :value="c.id"
                    :checked="toolDrafts[tool.toolId]?.ids.includes(c.id) ?? false"
                    type="checkbox"
                    data-testid="mcp-tool-consumer"
                    @change="
                      toggleToolConsumer(
                        tool.toolId,
                        c.id,
                        ($event.target as HTMLInputElement).checked,
                      )
                    "
                  />
                  <span>{{ c.label }}</span>
                </label>
                <p v-if="!consumerOptions.length" class="ui-field__hint">暂无 API 消费者</p>
              </div>
              <div class="next-mcp__actions">
                <UiButton
                  variant="primary"
                  size="sm"
                  :loading="toolSaving"
                  data-testid="mcp-access-tool-save"
                  @click="saveToolDraft(tool.toolId, tool.toolName)"
                  >保存</UiButton
                >
              </div>
            </div>
            <p v-if="!access.tools.length" class="next-mcp__hint">该服务暂无工具。</p>
          </div>
        </section>
      </template>
      <template #footer>
        <UiButton variant="secondary" @click="accessVisible = false">关闭</UiButton>
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

    <!-- Route rules (F11): per-service priority rules gating inbound requests -->
    <UiDrawer
      :open="rulesVisible"
      :title="rulesService ? `路由规则 · ${rulesService.name}` : '路由规则'"
      width="760px"
      data-testid="mcp-routes-drawer"
      @update:open="rulesVisible = false"
    >
      <div v-if="rulesError" class="ui-alert ui-alert--error">{{ rulesError }}</div>
      <div class="next-mcp__routes-head">
        <p class="next-mcp__routes-note">
          规则按优先级匹配（数值大者优先）；全部自定义规则未命中时回落到系统 default 路由兜底。
        </p>
        <UiButton
          variant="primary"
          size="sm"
          data-testid="mcp-route-create-open"
          @click="openRouteCreate"
          >新建规则</UiButton
        >
      </div>
      <div v-if="rulesLoading" class="next-mcp__access-loading">
        <div v-for="n in 3" :key="n" class="ui-skeleton next-mcp__tool-skeleton">&nbsp;</div>
      </div>
      <div v-else-if="rules.length" class="next-mcp__route-list" data-testid="mcp-routes-list">
        <div
          v-for="rule in rules"
          :key="rule.id"
          class="next-mcp__route-row"
          :data-rule-id="rule.id"
        >
          <span class="ui-num next-mcp__route-priority">{{ rule.priority }}</span>
          <div class="next-mcp__route-info">
            <div class="next-mcp__route-name-line">
              <span class="next-mcp__route-name">{{ rule.name }}</span>
              <span v-if="rule.name === 'default'" class="next-mcp__route-default-chip"
                >系统默认</span
              >
              <span v-if="rule.description" class="next-mcp__route-desc">{{
                rule.description
              }}</span>
            </div>
            <div class="ui-mono next-mcp__route-conditions">{{ conditionText(rule) }}</div>
            <div class="next-mcp__route-methods">{{ methodList(rule) }}</div>
          </div>
          <UiStatusBadge
            :tone="rule.status === 'ENABLED' ? 'success' : 'neutral'"
            :label="rule.status === 'ENABLED' ? '已启用' : '已停用'"
          />
          <div v-if="rule.name !== 'default'" class="next-mcp__route-actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="mcp-route-edit"
              @click="openRouteEdit(rule)"
              >编辑</UiButton
            >
            <UiButton variant="ghost" size="sm" @click="toggleRouteStatus(rule)">{{
              rule.status === 'ENABLED' ? '停用' : '启用'
            }}</UiButton>
            <UiButton
              variant="ghost"
              size="sm"
              class="next-mcp__danger"
              data-testid="mcp-route-delete"
              @click="requestRouteDelete(rule)"
              >删除</UiButton
            >
          </div>
        </div>
      </div>
      <p v-else class="next-mcp__route-empty">
        该服务还没有自定义路由。<br /><span class="next-mcp__hint"
          >只有 default 兜底在生效——新建规则后即可按条件分流。</span
        >
      </p>
    </UiDrawer>

    <!-- Route rule editor (create/edit share one form; save = full replace) -->
    <UiDialog
      :open="routeDialogVisible"
      :title="routeEditing ? `编辑路由 · ${routeEditing.name}` : '新建路由'"
      width="680px"
      data-testid="mcp-route-dialog"
      @update:open="routeDialogVisible = false"
    >
      <div class="next-mcp__route-form">
        <div class="next-mcp__row">
          <UiInput
            v-model="routeForm.name"
            label="路由名称"
            required
            placeholder="例如 gray-v2"
            data-testid="mcp-route-name"
          />
          <UiInput
            v-model="routeForm.priority"
            label="优先级"
            placeholder="1000"
            hint="数值越大越先匹配；0 为系统默认保留"
            data-testid="mcp-route-priority"
          />
        </div>
        <div class="ui-field">
          <span class="ui-field__label">描述</span>
          <textarea
            v-model="routeForm.description"
            class="ui-textarea"
            rows="2"
            maxlength="200"
            placeholder="用途说明（可选）"
            data-testid="mcp-route-desc"
          />
        </div>

        <div class="ui-field">
          <span class="ui-field__label">路径匹配</span>
          <div class="next-mcp__segmented" data-testid="mcp-route-path-mode">
            <button
              v-for="m in MATCH_MODES"
              :key="m.value"
              type="button"
              class="next-mcp__seg"
              :class="{ 'next-mcp__seg--on': routeForm.pathMode === m.value }"
              @click="routeForm.pathMode = m.value"
            >
              {{ m.label }}
            </button>
          </div>
          <UiInput
            v-if="routeForm.pathMode"
            v-model="routeForm.pathValue"
            placeholder="例如 /api（精确/前缀）或 ^/api/v[0-9]+$（正则）"
            data-testid="mcp-route-path-value"
          />
        </div>

        <div class="ui-field">
          <span class="ui-field__label">Host 匹配</span>
          <div class="next-mcp__segmented" data-testid="mcp-route-host-mode">
            <button
              v-for="m in MATCH_MODES"
              :key="m.value"
              type="button"
              class="next-mcp__seg"
              :class="{ 'next-mcp__seg--on': routeForm.hostMode === m.value }"
              @click="routeForm.hostMode = m.value"
            >
              {{ m.label }}
            </button>
          </div>
          <UiInput
            v-if="routeForm.hostMode"
            v-model="routeForm.hostValue"
            placeholder="例如 mcp-prod.example.com"
            data-testid="mcp-route-host-value"
          />
        </div>

        <div class="ui-field">
          <span class="ui-field__label">HTTP 方法（全选 = 不限）</span>
          <div class="next-mcp__method-chips" data-testid="mcp-route-methods">
            <label v-for="method in HTTP_METHODS" :key="method" class="next-mcp__check">
              <input
                v-model="routeForm.methods"
                type="checkbox"
                :value="method"
                data-testid="mcp-route-method"
              />
              <span>{{ method }}</span>
            </label>
          </div>
        </div>

        <div class="ui-field">
          <span class="ui-field__label"
            >Header 条件（AND 关系，最多 8 条）
            <UiButton
              v-if="routeForm.headers.length < 8"
              variant="ghost"
              size="sm"
              data-testid="mcp-route-header-add"
              @click="addHeaderRow"
              >+ 添加条件</UiButton
            ></span
          >
          <div class="next-mcp__header-rows" data-testid="mcp-route-headers">
            <div
              v-for="(header, index) in routeForm.headers"
              :key="index"
              class="next-mcp__header-row"
            >
              <UiInput
                v-model="header.name"
                placeholder="名称，如 X-Tenant-Id"
                data-testid="mcp-route-header-name"
              />
              <div class="next-mcp__segmented next-mcp__segmented--sm">
                <button
                  v-for="m in MATCH_MODES.filter((x) => x.value)"
                  :key="m.value"
                  type="button"
                  class="next-mcp__seg"
                  :class="{ 'next-mcp__seg--on': header.mode === m.value }"
                  @click="header.mode = m.value as 'EXACT' | 'PREFIX' | 'REGEX'"
                >
                  {{ m.label }}
                </button>
              </div>
              <UiInput
                v-model="header.value"
                placeholder="匹配值"
                data-testid="mcp-route-header-value"
              />
              <UiButton
                variant="ghost"
                size="sm"
                aria-label="移除该条件"
                @click="removeHeaderRow(index)"
                >移除</UiButton
              >
            </div>
          </div>
        </div>

        <p v-if="routeFormError" class="ui-form-error" data-testid="mcp-route-form-error">
          {{ routeFormError }}
        </p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="routeDialogVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="routeSaving"
          data-testid="mcp-route-save"
          @click="saveRouteRule"
          >保存</UiButton
        >
      </template>
    </UiDialog>

    <UiDialog
      v-if="routeConfirm"
      :open="true"
      :title="routeConfirm.title"
      :description="routeConfirm.body"
      width="440px"
      @update:open="routeConfirm = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="routeConfirm = null">取消</UiButton>
        <UiButton
          :variant="routeConfirm.tone === 'danger' ? 'danger' : 'primary'"
          @click="routeConfirmAndRun"
        >
          {{ routeConfirm.confirmLabel }}
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

.ui-field__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
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

.next-mcp__register {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-mcp__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-faint);
}

.next-mcp__register-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 560px;
}

.next-mcp__dialog-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-mcp__row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4);
}

.next-mcp__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

.next-mcp__row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-mcp__name {
  font-weight: var(--ui-weight-medium);
}

.next-mcp__endpoint {
  font-size: var(--ui-font-size-xs);
  overflow-wrap: anywhere;
}

.next-mcp__danger {
  color: var(--ui-danger-fg);
}

/* tools */
.next-mcp__tools-loading,
.next-mcp__access-loading {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__tool-skeleton {
  height: 52px;
}

.next-mcp__tool-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__tool-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__tool-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.next-mcp__tool-name {
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.next-mcp__tool-desc {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-mcp__tool-path {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-mcp__tool-empty {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-mcp__tool-create {
  border-top: 1px solid var(--ui-border);
  padding-top: var(--ui-space-4);
  margin-top: var(--ui-space-4);
}

.next-mcp__tool-create-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-3);
  max-width: 560px;
}

/* access control */
.next-mcp__access-notice {
  margin: 0 0 var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.next-mcp__access-section {
  margin-bottom: var(--ui-space-4);
  padding-bottom: var(--ui-space-4);
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-mcp__access-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

.next-mcp__access-title {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-mcp__access-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
}

.next-mcp__access-list-block {
  margin-top: var(--ui-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__segmented--sm .next-mcp__seg {
  height: 24px;
  padding: 0 var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
}

.next-mcp__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  white-space: nowrap;
}

.next-mcp__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-mcp__check-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  padding: var(--ui-space-1);
}

.next-mcp__check-list--nested {
  margin-top: var(--ui-space-2);
}

.next-mcp__check {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-2);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-mcp__check:hover {
  background: var(--ui-fill-hover);
}

.next-mcp__check input {
  accent-color: var(--ui-primary);
  margin: 0;
}

.next-mcp__tool-access-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-mcp__tool-access {
  padding: var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__tool-access-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

/* route rules (F11) */
.next-mcp__routes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-4);
}

.next-mcp__routes-note {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.next-mcp__route-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__route-row {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-3);
  padding: var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-mcp__route-priority {
  min-width: 44px;
  padding-top: 2px;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-mcp__route-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-mcp__route-name-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

.next-mcp__route-name {
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-mcp__route-default-chip {
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-sm);
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  background: var(--ui-neutral-bg);
  color: var(--ui-neutral-fg);
}

.next-mcp__route-desc {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-mcp__route-conditions {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  overflow-wrap: anywhere;
}

.next-mcp__route-methods {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-mcp__route-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
  justify-content: flex-end;
  flex-shrink: 0;
}

.next-mcp__route-empty {
  margin: 0;
  padding: var(--ui-space-8) 0;
  text-align: center;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-mcp__route-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-mcp__method-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-mcp__header-rows {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-mcp__header-row {
  display: grid;
  grid-template-columns: 150px auto minmax(120px, 1fr) auto;
  align-items: center;
  gap: var(--ui-space-2);
}
</style>
