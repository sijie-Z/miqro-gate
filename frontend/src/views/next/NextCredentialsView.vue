<script setup lang="ts">
/**
 * NextCredentialsView — /app/credentials v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy credentials page: catalogue with
 * fingerprint sublines, create form with masked secret input, candidate-secret
 * test dialog, rotate dialog, version-history drawer and gated disable.
 * Secrets keep their one-shot semantics; plaintext never persists.
 */
import { computed, onMounted, ref } from 'vue';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuItemIndicator,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from 'radix-vue';
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
import type { UiSelectOption } from '@/ui';
import type {
  CredentialDetailView,
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

const subscriptionOptions = computed<UiSelectOption[]>(() =>
  subscriptions.value.map((s) => ({
    value: s.id,
    label: `${s.productName} · ${s.name}`,
  })),
);

const providerStatusLabel: Record<string, string> = {
  VALID: '有效（供应商已接受）',
  REJECTED: '被供应商拒绝',
  UNREACHABLE: '供应商不可达',
  NOT_CHECKED: '未执行',
};

const statusLabel: Record<string, string> = {
  ACTIVE: '正常',
  DRAINING: '宽限期',
  DISABLED: '已禁用',
};

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'DRAINING':
      return 'warning';
    default:
      return 'danger';
  }
}

const columns = [
  { key: 'name', title: '名称', minWidth: '220px' },
  { key: 'product', title: '供应商产品', minWidth: '220px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'lastValidated', title: '最近验证', minWidth: '170px' },
  { key: 'version', title: '版本', width: '80px', align: 'right' as const },
  { key: 'actions', title: '操作', width: '70px', align: 'center' as const },
];

const versionColumns = [
  { key: 'status', title: '状态', width: '110px' },
  { key: 'encryptionKeyVersion', title: '密钥版本', width: '110px' },
  { key: 'fingerprintPrefix', title: '指纹', minWidth: '180px' },
  { key: 'validFrom', title: '生效时间', width: '170px' },
  { key: 'retiredAt', title: '退役时间', width: '170px' },
];

// ---- create form ----
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
    toast.success('凭证已创建');
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

// ---- candidate-secret test (never persists) ----
const validateTarget = ref<CredentialView | null>(null);
const candidateSecret = ref('');
const showCandidateSecret = ref(false);
const validating = ref(false);
const validateResult = ref<ValidateCredentialResponse | null>(null);
const validateError = ref('');
const validateRequestId = ref('');

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

// ---- rotate (new secret becomes ACTIVE; old drains) ----
const rotateTarget = ref<CredentialView | null>(null);
const rotateSecret = ref('');
const showRotateSecret = ref(false);
const rotating = ref(false);
const rotateError = ref('');
const rotateRequestId = ref('');

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
    toast.success('凭证已轮换，旧版本进入宽限期');
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

// ---- version history drawer ----
const historyTarget = ref<CredentialView | null>(null);
const versions = ref<CredentialVersionView[]>([]);
const historyLoading = ref(false);

async function openHistory(cred: CredentialView) {
  historyTarget.value = cred;
  historyLoading.value = true;
  versions.value = [];
  try {
    const detail: CredentialDetailView = await api.getCredential(cred.id);
    versions.value = detail.versions;
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  } finally {
    historyLoading.value = false;
  }
}

// ---- disable gate ----
const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function requestDisable(cred: CredentialView) {
  confirmState.value = {
    title: `禁用凭证「${cred.name}」`,
    body: '禁用后该凭证立即从路由快照移除，使用它的 Virtual Key 将无法完成请求。此操作不可撤销。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.disableCredential(cred.id);
        toast.success('凭证已禁用');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
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

function formatTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

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

onMounted(load);
</script>

<template>
  <div class="ui-page next-credentials">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">上游凭证</h1>
        <p class="ui-page-desc">
          真实供应商 API Key 的加密托管与版本管理；Secret 明文仅录入时可见一次。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="credential-create-open"
          @click="creating = !creating"
        >
          {{ creating ? '收起表单' : '录入凭证' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="credentials-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-credentials__create"
      data-testid="credential-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">录入上游凭证</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-credentials__form">
          <UiInput
            v-model="createName"
            label="名称"
            required
            placeholder="例如 deepseek-main"
            data-testid="credential-create-name"
          />
          <UiSelect
            v-model="createSubscriptionId"
            label="订阅"
            required
            placeholder="选择订阅（决定供应商与产品）"
            :options="subscriptionOptions"
            width="100%"
            data-testid="credential-create-subscription"
          />
          <UiInput
            v-model="createSecret"
            label="Secret"
            required
            :type="showCreateSecret ? 'text' : 'password'"
            placeholder="供应商 API Key（录入后仅显示一次）"
            data-testid="credential-create-secret"
          >
            <template #suffix>
              <button
                type="button"
                class="next-credentials__eye"
                :aria-label="showCreateSecret ? '隐藏 Secret' : '显示 Secret'"
                data-testid="credential-create-secret-toggle"
                @click="showCreateSecret = !showCreateSecret"
              >
                <svg
                  v-if="showCreateSecret"
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="m4.5 4 15 16"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                  />
                </svg>
                <svg
                  v-else
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="M9.8 12a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 0 0-4.4 0Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                </svg>
              </button>
            </template>
          </UiInput>
          <p v-if="formError" class="ui-form-error" data-testid="credential-create-error">
            {{ formError
            }}<span v-if="formRequestId" class="ui-request-id">
              requestId: {{ formRequestId }}</span
            >
          </p>
          <div class="next-credentials__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="creatingLoading"
              data-testid="credential-create-submit"
              @click="createCredential"
            >
              录入凭证
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ credentials.length }} 条凭证</span>
      </div>
      <UiTable
        :columns="columns"
        :data="credentials"
        :loading="loading"
        row-key="id"
        empty-title="还没有上游凭证"
        empty-description="点击右上角「录入凭证」添加第一家供应商的真实 API Key。"
        data-testid="credentials-table"
      >
        <template #name="{ row }">
          <div class="next-credentials__name">{{ (row as CredentialView).name }}</div>
          <div class="ui-mono next-credentials__fpr">
            {{ (row as CredentialView).fingerprintPrefix }}…
          </div>
        </template>
        <template #product="{ row }">{{
          productName((row as CredentialView).subscriptionId)
        }}</template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="statusTone((row as CredentialView).status)"
            :label="statusLabel[(row as CredentialView).status] ?? (row as CredentialView).status"
          />
        </template>
        <template #lastValidated="{ row }">
          <span
            v-if="(row as CredentialView).lastValidatedAt"
            class="next-credentials__time ui-num"
            >{{ formatTime((row as CredentialView).lastValidatedAt) }}</span
          >
          <span
            v-else-if="(row as CredentialView).lastValidationError"
            class="next-credentials__error"
            >{{ (row as CredentialView).lastValidationError }}</span
          >
          <span v-else class="next-credentials__fpr">从未验证</span>
        </template>
        <template #version="{ row }">
          <span class="ui-num">v{{ (row as CredentialView).version }}</span>
        </template>
        <template #actions="{ row }">
          <DropdownMenuRoot>
            <DropdownMenuTrigger
              class="next-credentials__kebab"
              aria-label="操作"
              :data-testid="`credential-actions-${(row as CredentialView).id}`"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <circle cx="3" cy="8" r="1.4" />
                <circle cx="8" cy="8" r="1.4" />
                <circle cx="13" cy="8" r="1.4" />
              </svg>
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent class="next-credentials__menu" :side-offset="4" :align="'end'">
                <DropdownMenuItem
                  class="next-credentials__menu-item"
                  @select="openValidate(row as CredentialView)"
                >
                  <DropdownMenuItemIndicator class="next-credentials__menu-ind" />
                  <span data-testid="credential-validate">测试 Secret</span>
                </DropdownMenuItem>
                <DropdownMenuItem
                  class="next-credentials__menu-item"
                  :disabled="(row as CredentialView).status === 'DISABLED'"
                  @select="openRotate(row as CredentialView)"
                >
                  <DropdownMenuItemIndicator class="next-credentials__menu-ind" />
                  <span data-testid="credential-rotate">轮换</span>
                </DropdownMenuItem>
                <DropdownMenuItem
                  class="next-credentials__menu-item"
                  @select="openHistory(row as CredentialView)"
                >
                  <DropdownMenuItemIndicator class="next-credentials__menu-ind" />
                  <span data-testid="credential-history">版本历史</span>
                </DropdownMenuItem>
                <DropdownMenuSeparator class="next-credentials__menu-sep" />
                <DropdownMenuItem
                  class="next-credentials__menu-item next-credentials__menu-item--danger"
                  :disabled="(row as CredentialView).status === 'DISABLED'"
                  @select="requestDisable(row as CredentialView)"
                >
                  <DropdownMenuItemIndicator class="next-credentials__menu-ind" />
                  <span data-testid="credential-disable">禁用</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </template>
      </UiTable>
    </section>

    <!-- Validate dialog -->
    <UiDialog
      :open="validateTarget !== null"
      title="测试 Secret"
      :description="
        validateTarget
          ? `测试候选 Secret 是否与「${validateTarget.name}」当前生效版本一致。纯校验，不写入任何数据。`
          : ''
      "
      width="460px"
      @update:open="validateTarget = null"
    >
      <template v-if="validateTarget">
        <UiInput
          v-model="candidateSecret"
          :type="showCandidateSecret ? 'text' : 'password'"
          placeholder="输入待测试的 Secret"
          data-testid="credential-validate-secret"
        >
          <template #suffix>
            <button
              type="button"
              class="next-credentials__eye"
              :aria-label="showCandidateSecret ? '隐藏 Secret' : '显示 Secret'"
              data-testid="credential-validate-secret-toggle"
              @click="showCandidateSecret = !showCandidateSecret"
            >
              <svg
                v-if="showCandidateSecret"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="m4.5 4 15 16"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
              </svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="M9.8 12a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 0 0-4.4 0Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
              </svg>
            </button>
          </template>
        </UiInput>
        <div
          v-if="validateResult"
          class="next-credentials__result"
          data-testid="credential-validate-result"
        >
          <p
            :class="validateResult.matchesActive ? 'next-credentials__ok' : 'next-credentials__bad'"
          >
            {{
              validateResult.matchesActive
                ? '与当前生效版本一致。'
                : (validateResult.message ?? '与当前生效版本不一致。')
            }}
          </p>
          <p
            v-if="validateResult.providerStatus !== 'NOT_CHECKED'"
            class="next-credentials__provider"
          >
            供应商验证：{{ providerStatusLabel[validateResult.providerStatus] }}
            <span v-if="validateResult.providerMessage" class="ui-mono"
              >（{{ validateResult.providerMessage }}）</span
            >
          </p>
          <p v-else class="next-credentials__provider">
            供应商验证：未执行（无适配器或候选与生效版本不一致）
          </p>
        </div>
        <p v-if="validateError" class="ui-form-error" data-testid="credential-validate-error">
          {{ validateError
          }}<span v-if="validateRequestId" class="ui-request-id">
            requestId: {{ validateRequestId }}</span
          >
        </p>
      </template>
      <template #footer>
        <UiButton variant="ghost" @click="validateTarget = null">关闭</UiButton>
        <UiButton
          variant="primary"
          :loading="validating"
          data-testid="credential-validate-run"
          @click="runValidate"
        >
          测试
        </UiButton>
      </template>
    </UiDialog>

    <!-- Rotate dialog -->
    <UiDialog
      :open="rotateTarget !== null"
      title="轮换凭证"
      :description="
        rotateTarget
          ? `为「${rotateTarget.name}」提供新的 Secret。轮换是原子操作：新 Secret 立即生效，旧版本按宽限期退役。`
          : ''
      "
      width="460px"
      @update:open="rotateTarget = null"
    >
      <template v-if="rotateTarget">
        <UiInput
          v-model="rotateSecret"
          :type="showRotateSecret ? 'text' : 'password'"
          placeholder="输入新的 Secret"
          data-testid="credential-rotate-secret"
        >
          <template #suffix>
            <button
              type="button"
              class="next-credentials__eye"
              :aria-label="showRotateSecret ? '隐藏 Secret' : '显示 Secret'"
              data-testid="credential-rotate-secret-toggle"
              @click="showRotateSecret = !showRotateSecret"
            >
              <svg
                v-if="showRotateSecret"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="m4.5 4 15 16"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
              </svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="M9.8 12a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 0 0-4.4 0Z"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
              </svg>
            </button>
          </template>
        </UiInput>
        <p v-if="rotateError" class="ui-form-error" data-testid="credential-rotate-error">
          {{ rotateError
          }}<span v-if="rotateRequestId" class="ui-request-id">
            requestId: {{ rotateRequestId }}</span
          >
        </p>
      </template>
      <template #footer>
        <UiButton variant="ghost" @click="rotateTarget = null">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="rotating"
          data-testid="credential-rotate-submit"
          @click="runRotate"
        >
          轮换
        </UiButton>
      </template>
    </UiDialog>

    <!-- Version history -->
    <UiDrawer
      :open="historyTarget !== null"
      :title="`版本历史：${historyTarget?.name ?? ''}`"
      width="560px"
      data-testid="credential-history-drawer"
      @close="historyTarget = null"
    >
      <UiTable
        :columns="versionColumns"
        :data="versions"
        :loading="historyLoading"
        row-key="id"
        empty-title="没有版本记录"
        data-testid="credential-versions"
      >
        <template #status="{ row }">
          <UiStatusBadge
            :tone="statusTone((row as CredentialVersionView).status)"
            :label="
              statusLabel[(row as CredentialVersionView).status] ??
              (row as CredentialVersionView).status
            "
          />
        </template>
        <template #fingerprintPrefix="{ row }">
          <span class="ui-mono">{{ (row as CredentialVersionView).fingerprintPrefix }}</span>
        </template>
        <template #validFrom="{ row }">{{
          formatTime((row as CredentialVersionView).validFrom)
        }}</template>
        <template #retiredAt="{ row }">{{
          formatTime((row as CredentialVersionView).retiredAt)
        }}</template>
      </UiTable>
    </UiDrawer>

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

.next-credentials__create {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-credentials__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 540px;
}

.next-credentials__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-credentials__eye {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-credentials__eye:hover {
  color: var(--ui-foreground);
  background: var(--ui-fill-hover);
}

.next-credentials__name {
  font-weight: var(--ui-weight-medium);
}

.next-credentials__fpr {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-credentials__time {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-credentials__error {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-danger-fg);
}

.next-credentials__kebab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-credentials__kebab:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.next-credentials__kebab:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.next-credentials__menu {
  min-width: 170px;
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  box-shadow: var(--ui-shadow-popper);
  padding: var(--ui-space-1);
  z-index: 2000;
}

.next-credentials__menu-item {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
  outline: none;
}

.next-credentials__menu-item[data-highlighted] {
  background: var(--ui-fill-hover);
}

.next-credentials__menu-item[data-disabled] {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.next-credentials__menu-item--danger {
  color: var(--ui-danger-fg);
}

.next-credentials__menu-ind {
  display: none;
}

.next-credentials__menu-sep {
  height: 1px;
  margin: var(--ui-space-1) 0;
  background: var(--ui-border-muted);
}

.next-credentials__result {
  margin-top: var(--ui-space-3);
}

.next-credentials__result p {
  margin: 0 0 var(--ui-space-1);
  font-size: var(--ui-font-size-sm);
}

.next-credentials__ok {
  color: var(--ui-success-fg);
}

.next-credentials__bad {
  color: var(--ui-danger-fg);
}

.next-credentials__provider {
  font-size: var(--ui-font-size-xs) !important;
  color: var(--ui-foreground-secondary);
}
</style>
