<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { BrowseIcon, BrowseOffIcon } from 'tdesign-icons-vue-next';
import { confirmDialog } from '@/utils/confirm';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type {
  CredentialVersionView,
  CredentialView,
  SubscriptionView,
  ValidateCredentialResponse,
} from '@/types/api';

const credentials = ref<CredentialView[]>([]);
const subscriptions = ref<SubscriptionView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const subscriptionById = computed(
  () => new Map(subscriptions.value.map((s) => [s.id, s]) as [string, SubscriptionView][]),
);

function productName(subscriptionId: string): string {
  const sub = subscriptionById.value.get(subscriptionId);
  return sub ? `${sub.productName} · ${sub.name}` : '—';
}

const providerStatusLabel: Record<string, string> = {
  VALID: '有效（供应商已接受）',
  REJECTED: '被供应商拒绝',
  UNREACHABLE: '供应商不可达',
  NOT_CHECKED: '未执行',
};

const statusLabel: Record<string, string> = {
  ACTIVE: 'Active',
  DRAINING: 'Draining',
  DISABLED: 'Disabled',
};

function statusClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'mk-status--success';
    case 'DRAINING':
      return 'mk-status--warning';
    default:
      return 'mk-status--danger';
  }
}

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 200 },
  { colKey: 'product', title: '供应商产品', minWidth: 200 },
  { colKey: 'status', title: '状态', width: 110 },
  { colKey: 'lastValidated', title: '最近验证', minWidth: 180 },
  { colKey: 'version', title: '版本', width: 80, align: 'right' },
  { colKey: 'actions', title: '操作', width: 90, fixed: 'right' },
];

const versionColumns: PrimaryTableCol[] = [
  { colKey: 'status', title: '状态', width: 110 },
  { colKey: 'encryptionKeyVersion', title: '密钥版本', width: 100 },
  { colKey: 'fingerprintPrefix', title: '指纹', minWidth: 130 },
  { colKey: 'validFrom', title: '生效时间', width: 170 },
  { colKey: 'retiredAt', title: '退役时间', width: 170 },
];

// Create form
const creating = ref(false);
const createName = ref('');
const createSubscriptionId = ref('');
const createSecret = ref('');
const showCreateSecret = ref(false);
const creatingLoading = ref(false);
const formError = ref('');
const formRequestId = ref('');

const canCreate = computed(
  () =>
    createName.value.trim().length > 0 &&
    createSubscriptionId.value !== '' &&
    createSecret.value.trim().length > 0,
);

function resetCreateForm() {
  createName.value = '';
  createSubscriptionId.value = '';
  createSecret.value = '';
  showCreateSecret.value = false;
  formError.value = '';
  formRequestId.value = '';
}

// Validate (candidate-secret test; never persists)
const validateTarget = ref<CredentialView | null>(null);
const candidateSecret = ref('');
const showCandidateSecret = ref(false);
const validating = ref(false);
const validateResult = ref<ValidateCredentialResponse | null>(null);
const validateError = ref('');
const validateRequestId = ref('');

const validateVisible = computed({
  get: () => validateTarget.value !== null,
  set: (open: boolean) => {
    if (!open) {
      validateTarget.value = null;
    }
  },
});

function openValidate(cred: CredentialView) {
  validateTarget.value = cred;
  candidateSecret.value = '';
  showCandidateSecret.value = false;
  validateResult.value = null;
  validateError.value = '';
  validateRequestId.value = '';
}

async function runValidate() {
  if (!validateTarget.value || !candidateSecret.value.trim()) {
    validateError.value = '请输入要测试的 Secret。';
    return;
  }
  validating.value = true;
  validateError.value = '';
  validateRequestId.value = '';
  try {
    validateResult.value = await api.validateCredential(validateTarget.value.id, {
      secret: candidateSecret.value,
    });
  } catch (error) {
    validateResult.value = null;
    if (error instanceof ApiError) {
      validateError.value = error.message;
      validateRequestId.value = error.requestId ?? '';
    } else {
      validateError.value = '验证失败，请稍后重试。';
    }
  } finally {
    validating.value = false;
  }
}

// Rotate (new secret becomes ACTIVE; old version drains)
const rotateTarget = ref<CredentialView | null>(null);
const rotateSecret = ref('');
const showRotateSecret = ref(false);
const rotating = ref(false);
const rotateError = ref('');
const rotateRequestId = ref('');

const rotateVisible = computed({
  get: () => rotateTarget.value !== null,
  set: (open: boolean) => {
    if (!open) {
      rotateTarget.value = null;
    }
  },
});

function openRotate(cred: CredentialView) {
  rotateTarget.value = cred;
  rotateSecret.value = '';
  showRotateSecret.value = false;
  rotateError.value = '';
  rotateRequestId.value = '';
}

async function runRotate() {
  if (!rotateTarget.value || !rotateSecret.value.trim()) {
    rotateError.value = '请输入新的 Secret。';
    return;
  }
  rotating.value = true;
  rotateError.value = '';
  rotateRequestId.value = '';
  try {
    await api.rotateCredential(rotateTarget.value.id, { secret: rotateSecret.value });
    MessagePlugin.success('凭证已轮换，旧版本进入宽限期');
    rotateTarget.value = null;
    rotateSecret.value = '';
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      rotateError.value = error.message;
      rotateRequestId.value = error.requestId ?? '';
    } else {
      rotateError.value = '轮换失败，请稍后重试。';
    }
  } finally {
    rotating.value = false;
  }
}

// Version history drawer
const historyTarget = ref<CredentialView | null>(null);
const versions = ref<CredentialVersionView[]>([]);
const historyLoading = ref(false);

const historyVisible = computed({
  get: () => historyTarget.value !== null,
  set: (open: boolean) => {
    if (!open) {
      historyTarget.value = null;
    }
  },
});

async function openHistory(cred: CredentialView) {
  historyTarget.value = cred;
  historyLoading.value = true;
  versions.value = [];
  try {
    const detail = await api.getCredential(cred.id);
    versions.value = detail.versions;
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  } finally {
    historyLoading.value = false;
  }
}

async function handleCommand(command: string, row: CredentialView) {
  if (command === 'validate') {
    openValidate(row);
  } else if (command === 'rotate') {
    openRotate(row);
  } else if (command === 'history') {
    await openHistory(row);
  } else if (command === 'disable') {
    await disableCredentialFlow(row);
  }
}

async function disableCredentialFlow(cred: CredentialView) {
  try {
    await confirmDialog({
      header: `禁用凭证「${cred.name}」`,
      body: '禁用后该凭证立即从路由快照移除，使用它的 Virtual Key 将无法完成请求。此操作不可撤销。',
      confirmBtn: '禁用',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return; // cancelled
  }
  try {
    await api.disableCredential(cred.id);
    MessagePlugin.success('凭证已禁用');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function createCredential() {
  if (!canCreate.value) {
    formError.value = '名称、订阅与 Secret 必填。';
    return;
  }
  creatingLoading.value = true;
  formError.value = '';
  formRequestId.value = '';
  try {
    await api.createCredential({
      name: createName.value.trim(),
      subscriptionId: createSubscriptionId.value,
      secret: createSecret.value,
    });
    MessagePlugin.success('凭证已创建');
    creating.value = false;
    resetCreateForm();
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      formError.value = error.message;
      formRequestId.value = error.requestId ?? '';
    } else {
      formError.value = '创建失败，请稍后重试。';
    }
  } finally {
    creatingLoading.value = false;
  }
}

function formatTime(iso?: string | null): string {
  if (!iso) {
    return '—';
  }
  return new Date(iso).toLocaleString();
}

onMounted(load);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [credentialList, subscriptionList] = await Promise.all([
      api.listCredentials(),
      api.listSubscriptions(),
    ]);
    credentials.value = credentialList;
    subscriptions.value = subscriptionList;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载上游凭证失败。';
    }
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="credentials-page">
    <PageHeader
      title="上游凭证"
      description="真实供应商 API Key 的加密存储与生命周期：创建、测试、轮换与禁用。"
    >
      <template #actions>
        <t-button
          theme="primary"
          data-testid="credential-create-open"
          @click="creating = !creating"
        >
          {{ creating ? '收起表单' : '录入凭证' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert
      v-if="loadError"
      theme="error"
      class="block-alert"
      data-testid="credentials-load-error"
    >
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <!-- Create form -->
    <section v-if="creating" class="create-panel" data-testid="credential-create-form">
      <h3 class="panel-title">录入上游凭证</h3>
      <p class="hint">
        Secret 仅接受明文输入，落库前以 AES-256-GCM 加密；创建成功后只显示指纹前缀，明文不再出现。
      </p>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input
            v-model="createName"
            placeholder="例如 anthropic-main"
            data-testid="credential-create-name"
          />
        </t-form-item>
        <t-form-item label="订阅（供应商产品）" required-mark>
          <t-select
            v-model="createSubscriptionId"
            placeholder="选择订阅"
            class="full-width"
            data-testid="credential-create-subscription"
          >
            <t-option
              v-for="sub in subscriptions"
              :key="sub.id"
              :value="sub.id"
              :label="`${sub.productName} · ${sub.name}`"
            />
          </t-select>
        </t-form-item>
        <t-form-item label="Secret" required-mark>
          <t-input
            v-model="createSecret"
            :type="showCreateSecret ? 'text' : 'password'"
            placeholder="sk-…"
            data-testid="credential-create-secret"
          >
            <template #suffix-icon>
              <component
                :is="showCreateSecret ? BrowseOffIcon : BrowseIcon"
                aria-label="切换 Secret 可见性"
                role="button"
                @click="showCreateSecret = !showCreateSecret"
              />
            </template>
          </t-input>
        </t-form-item>
        <t-alert
          v-if="formError"
          theme="error"
          class="form-error"
          data-testid="credential-create-error"
        >
          {{ formError
          }}<span v-if="formRequestId" class="mk-mono">requestId: {{ formRequestId }}</span>
        </t-alert>
        <div class="form-actions">
          <t-button
            theme="primary"
            :disabled="!canCreate"
            :loading="creatingLoading"
            data-testid="credential-create-submit"
            @click="createCredential"
          >
            创建凭证
          </t-button>
          <t-button @click="creating = false">取消</t-button>
        </div>
      </t-form>
    </section>

    <div class="mk-filter-bar" data-testid="credentials-filter-bar">
      <span class="mk-stat-hint">共 {{ credentials.length }} 条</span>
    </div>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="credentials"
        class="credentials-table"
        data-testid="credentials-table"
      >
        <template #name="{ row }">
          <div class="credential-name">{{ row.name }}</div>
          <div class="mk-mono credential-fingerprint">{{ row.fingerprintPrefix }}</div>
        </template>
        <template #product="{ row }">{{ productName(row.subscriptionId) }}</template>
        <template #status="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">
            {{ statusLabel[row.status] ?? row.status }}
          </span>
        </template>
        <template #lastValidated="{ row }">
          <div v-if="row.lastValidatedAt" class="validated-time">
            {{ formatTime(row.lastValidatedAt) }}
          </div>
          <div v-if="row.lastValidationError" class="validation-error">
            {{ row.lastValidationError }}
          </div>
          <span v-if="!row.lastValidatedAt && !row.lastValidationError" class="mk-stat-hint"
            >未验证</span
          >
        </template>
        <template #version="{ row }">
          <span class="mk-num">v{{ row.version }}</span>
        </template>
        <template #actions="{ row }">
          <t-dropdown trigger="click">
            <t-button variant="text" data-testid="credential-actions">操作</t-button>
            <template #dropdown>
              <t-dropdown-menu>
                <t-dropdown-item @click="handleCommand('validate', row)">
                  <span data-testid="credential-validate">测试 Secret</span>
                </t-dropdown-item>
                <t-dropdown-item
                  :disabled="row.status !== 'ACTIVE'"
                  @click="handleCommand('rotate', row)"
                >
                  <span data-testid="credential-rotate">轮换</span>
                </t-dropdown-item>
                <t-dropdown-item @click="handleCommand('history', row)">
                  <span data-testid="credential-history">版本历史</span>
                </t-dropdown-item>
                <t-dropdown-item
                  divider
                  :disabled="row.status !== 'ACTIVE' && row.status !== 'DRAINING'"
                  @click="handleCommand('disable', row)"
                >
                  <span data-testid="credential-disable">禁用</span>
                </t-dropdown-item>
              </t-dropdown-menu>
            </template>
          </t-dropdown>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有上游凭证。</p>
            <p class="hint">点击右上角「录入凭证」添加第一家供应商的真实 API Key。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <!-- Validate dialog -->
    <t-dialog v-model:visible="validateVisible" header="测试 Secret" width="440px" :footer="false">
      <template v-if="validateTarget">
        <p class="dialog-hint">
          测试候选 Secret 是否与「{{
            validateTarget.name
          }}」当前生效版本一致。纯校验，不写入任何数据。
        </p>
        <t-input
          v-model="candidateSecret"
          :type="showCandidateSecret ? 'text' : 'password'"
          placeholder="输入待测试的 Secret"
          data-testid="credential-validate-secret"
        >
          <template #suffix-icon>
            <component
              :is="showCandidateSecret ? BrowseOffIcon : BrowseIcon"
              aria-label="切换 Secret 可见性"
              role="button"
              @click="showCandidateSecret = !showCandidateSecret"
            />
          </template>
        </t-input>
        <t-alert
          v-if="validateResult"
          :theme="validateResult.matchesActive ? 'success' : 'error'"
          class="validate-result"
          data-testid="credential-validate-result"
        >
          <div>{{ validateResult.matchesActive ? '与当前生效版本一致。' : (validateResult.message ?? '与当前生效版本不一致。') }}</div>
          <div v-if="validateResult.providerStatus !== 'NOT_CHECKED'" class="provider-check">
            供应商验证：{{ providerStatusLabel[validateResult.providerStatus] }}
            <span v-if="validateResult.providerMessage" class="mk-mono">（{{ validateResult.providerMessage }}）</span>
          </div>
          <div v-else class="provider-check">供应商验证：未执行（无适配器或候选与生效版本不一致）</div>
        </t-alert>
        <t-alert
          v-if="validateError"
          theme="error"
          class="validate-result"
          data-testid="credential-validate-error"
        >
          {{ validateError
          }}<span v-if="validateRequestId" class="mk-mono">requestId: {{ validateRequestId }}</span>
        </t-alert>
        <div class="form-actions">
          <t-button
            theme="primary"
            :loading="validating"
            data-testid="credential-validate-run"
            @click="runValidate"
          >
            测试
          </t-button>
          <t-button @click="validateTarget = null">关闭</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- Rotate dialog -->
    <t-dialog v-model:visible="rotateVisible" header="轮换凭证" width="440px" :footer="false">
      <template v-if="rotateTarget">
        <p class="dialog-hint">
          为「{{ rotateTarget.name }}」提供新的 Secret。轮换是原子操作：新 Secret
          立即生效，旧版本按宽限期退役。
        </p>
        <t-input
          v-model="rotateSecret"
          :type="showRotateSecret ? 'text' : 'password'"
          placeholder="输入新的 Secret"
          data-testid="credential-rotate-secret"
        >
          <template #suffix-icon>
            <component
              :is="showRotateSecret ? BrowseOffIcon : BrowseIcon"
              aria-label="切换 Secret 可见性"
              role="button"
              @click="showRotateSecret = !showRotateSecret"
            />
          </template>
        </t-input>
        <t-alert
          v-if="rotateError"
          theme="error"
          class="validate-result"
          data-testid="credential-rotate-error"
        >
          {{ rotateError
          }}<span v-if="rotateRequestId" class="mk-mono">requestId: {{ rotateRequestId }}</span>
        </t-alert>
        <div class="form-actions">
          <t-button
            theme="primary"
            :loading="rotating"
            data-testid="credential-rotate-submit"
            @click="runRotate"
          >
            轮换
          </t-button>
          <t-button @click="rotateTarget = null">取消</t-button>
        </div>
      </template>
    </t-dialog>

    <!-- Version history drawer -->
    <t-drawer
      v-model:visible="historyVisible"
      :header="`版本历史：${historyTarget?.name ?? ''}`"
      :footer="false"
      size="520px"
    >
      <t-loading :loading="historyLoading" size="small" show-overlay>
        <t-table
          v-if="versions.length"
          row-key="id"
          size="small"
          :columns="versionColumns"
          :data="versions"
          class="versions-table"
          data-testid="credential-versions"
        >
          <template #status="{ row }">
            <span class="mk-status" :class="statusClass(row.status)">
              {{ statusLabel[row.status] ?? row.status }}
            </span>
          </template>
          <template #fingerprintPrefix="{ row }">
            <span class="mk-mono">{{ row.fingerprintPrefix }}</span>
          </template>
          <template #validFrom="{ row }">{{ formatTime(row.validFrom) }}</template>
          <template #retiredAt="{ row }">{{ formatTime(row.retiredAt) }}</template>
        </t-table>
        <div v-else class="table-empty">没有版本记录。</div>
      </t-loading>
    </t-drawer>
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

.full-width {
  width: 100%;
}

.form-error {
  margin-bottom: 12px;
}

.form-actions {
  display: flex;
  gap: 8px;
}

.credentials-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.credential-name {
  font-weight: 500;
}

.credential-fingerprint {
  color: var(--miqrokey-text-secondary);
  font-size: 12px;
}

.validated-time {
  font-size: 12px;
}

.validation-error {
  color: var(--miqrokey-danger);
  font-size: 12px;
}

.dialog-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  line-height: 20px;
}

.validate-result {
  margin-top: 12px;
}

.provider-check {
  margin-top: 4px;
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}

.versions-table {
  width: 100%;
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
