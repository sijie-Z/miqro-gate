<script setup lang="ts">
/**
 * NextProfileView — /app/profile (#597, GitHub-settings-inspired account page).
 * Identity header + monthly snapshot (icon-chip stats matching the analysis
 * cards) + account facts + security section (password change, current session,
 * "sign out of other sessions"). Behaviour parity with the legacy layout:
 * account facts + password change, forced first-login flow included.
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  ChartBarIcon,
  DesktopIcon,
  LayersIcon,
  LockOnIcon,
  MoneyIcon,
  UserIcon,
} from 'tdesign-icons-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { UiButton, UiDialog, UiInput, UiStatusBadge, toast } from '@/ui';
import type { UsageSummary, VirtualKeyView } from '@/types/generated-api';

const auth = useAuthStore();
const router = useRouter();

const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const submitting = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');

const isForced = computed(() => auth.mustChangePassword);

// ---- identity ----

const displayName = computed(() => auth.user?.displayName || auth.user?.username || '—');

const userInitial = computed(() =>
  (auth.user?.displayName || auth.user?.username || '?').trim().slice(0, 1).toUpperCase(),
);

const roleMeta = computed(() =>
  auth.user?.role === 'SYSTEM_ADMIN'
    ? { label: '系统管理员', tone: 'info' as const }
    : { label: '普通用户', tone: 'neutral' as const },
);

const statusMeta = computed(() =>
  auth.user?.status === 'DISABLED'
    ? { label: '已禁用', tone: 'danger' as const }
    : { label: '正常', tone: 'success' as const },
);

function formatInstant(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

// ---- monthly snapshot (caliber: the usage page's totals row) ----

const keys = ref<VirtualKeyView[] | null>(null);
const summary = ref<UsageSummary | null>(null);
const snapshotError = ref('');

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

async function loadSnapshot() {
  snapshotError.value = '';
  const [keysResult, summaryResult] = await Promise.allSettled([
    api.listVirtualKeys(),
    api.usageSummary('project'),
  ]);
  if (keysResult.status === 'fulfilled') keys.value = keysResult.value;
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value;
  const failed = [keysResult, summaryResult].find((r) => r.status === 'rejected');
  if (failed?.status === 'rejected') {
    const error: unknown = failed.reason;
    snapshotError.value =
      error instanceof ApiError
        ? `${error.message}（requestId: ${error.requestId ?? '-'}）`
        : '用量速览加载失败。';
  }
}

const snapshot = computed(() => {
  const totals = summary.value?.totals;
  const requests =
    (totals?.requests?.upstream ?? 0) +
    (totals?.requests?.coalesced ?? 0) +
    (totals?.requests?.l1Hit ?? 0) +
    (totals?.requests?.l2Hit ?? 0);
  const tokens = (totals?.tokens?.input ?? 0) + (totals?.tokens?.output ?? 0);
  const cost = Number(totals?.cost?.upstreamPaid ?? 0);
  const activeKeys = keys.value?.filter((k) => k.status === 'ACTIVE').length;
  return [
    {
      label: '可用虚拟密钥',
      value: keys.value ? String(activeKeys) : '—',
      prefix: '',
      chip: 'blue',
      icon: LockOnIcon,
      to: '/app/keys',
    },
    {
      label: '本月请求',
      value: summary.value ? formatCount(requests) : '—',
      prefix: '',
      chip: 'green',
      icon: ChartBarIcon,
      to: '/app/usage',
    },
    {
      label: '本月 Token',
      value: summary.value ? formatCount(tokens) : '—',
      prefix: '',
      chip: 'cyan',
      icon: LayersIcon,
      to: '/app/usage',
    },
    {
      label: '本月成本',
      value: summary.value ? cost.toFixed(2) : '—',
      prefix: '¥',
      chip: 'gold',
      icon: MoneyIcon,
      to: '/app/usage',
    },
  ];
});

// ---- account facts ----

const accountRows = computed(() => [
  { label: '用户名', value: auth.user?.username ?? '—', testid: 'account-username' },
  { label: '显示名称', value: auth.user?.displayName || '—', testid: undefined },
  { label: '角色', value: roleMeta.value.label, testid: undefined },
  { label: '账号状态', value: statusMeta.value.label, testid: undefined },
]);

// ---- sign out of other sessions ----

const revokeOpen = ref(false);
const revoking = ref(false);
const revokeError = ref('');

async function confirmRevokeOthers() {
  revoking.value = true;
  revokeError.value = '';
  try {
    await api.logoutOtherSessions();
    toast.success('已退出其他会话');
    revokeOpen.value = false;
  } catch (error) {
    revokeError.value =
      error instanceof ApiError
        ? `${error.message}（requestId: ${error.requestId ?? '-'}）`
        : '退出其他会话失败，请稍后重试。';
  } finally {
    revoking.value = false;
  }
}

// ---- password change ----

async function submit() {
  errorMessage.value = '';
  if (newPassword.value.length < 8) {
    errorMessage.value = '新密码至少需要 8 个字符。';
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的新密码不一致。';
    return;
  }
  submitting.value = true;
  // #440: capture the forced-change flag BEFORE awaiting — changePassword flips
  // mustChangePassword to false, so reading isForced afterwards is always false
  // and the redirect below was dead code (the old spec passed only because its
  // non-reactive mock never invalidated the computed).
  const forced = isForced.value;
  try {
    await auth.changePassword(currentPassword.value, newPassword.value);
    toast.success('密码已修改');
    if (forced) {
      await router.push('/app-new/keys');
    }
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      errorRequestId.value = error.requestId ?? '';
    } else {
      errorMessage.value = '修改密码失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  // Refresh the profile facts (lastLoginAt / sessionExpiresAt) and the
  // account snapshot; the store keeps any identity on transient failures.
  await auth.fetchMe();
  if (!isForced.value) {
    await loadSnapshot();
  }
});
</script>

<template>
  <div class="ui-page next-profile">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">{{ isForced ? '设置新密码' : '资料' }}</h1>
        <p class="ui-page-desc">
          {{ isForced ? '首次登录必须修改临时密码后才能使用门户。' : '账号信息与安全设置。' }}
        </p>
      </div>
    </header>

    <div v-if="isForced" class="next-profile__forced" data-testid="forced-password">
      <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M8 1.5 14.5 14h-13L8 1.5ZM8 6v3.2M8 11.6v.2"
          stroke="currentColor"
          stroke-width="1.4"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      账号正在使用临时密码，修改后请重新登录确认。
    </div>

    <!-- Identity header (hidden during the forced first-login flow) -->
    <section
      v-if="!isForced"
      class="ui-panel next-profile__identity"
      data-testid="profile-identity"
    >
      <span class="next-profile__avatar" aria-hidden="true">{{ userInitial }}</span>
      <div class="next-profile__identity-text">
        <h2 class="next-profile__identity-name" data-testid="profile-display-name">
          {{ displayName }}
        </h2>
        <p class="next-profile__identity-handle">@{{ auth.user?.username ?? '—' }}</p>
      </div>
      <div class="next-profile__identity-badges">
        <UiStatusBadge :tone="roleMeta.tone" :label="roleMeta.label" />
        <UiStatusBadge :tone="statusMeta.tone" :label="statusMeta.label" />
      </div>
    </section>

    <!-- Monthly snapshot (same numbers as the usage page totals) -->
    <section
      v-if="!isForced"
      class="ui-panel next-profile__snapshot"
      data-testid="profile-snapshot"
    >
      <router-link
        v-for="card in snapshot"
        :key="card.label"
        :to="card.to"
        class="next-profile__stat"
      >
        <span
          class="next-profile__stat-chip"
          :class="`next-profile__stat-chip--${card.chip}`"
          aria-hidden="true"
        >
          <component :is="card.icon" />
        </span>
        <span class="next-profile__stat-main">
          <span class="next-profile__stat-label">{{ card.label }}</span>
          <span class="next-profile__stat-value ui-num"
            ><i v-if="card.prefix" class="next-profile__stat-currency">{{ card.prefix }}</i
            >{{ card.value }}</span
          >
        </span>
      </router-link>
    </section>
    <p
      v-if="!isForced && snapshotError"
      class="next-profile__snapshot-error"
      data-testid="profile-snapshot-error"
    >
      {{ snapshotError }}
    </p>

    <div class="next-profile__grid" :class="{ 'next-profile__grid--forced': isForced }">
      <div class="next-profile__col">
        <section v-if="!isForced" class="ui-panel next-profile__panel">
          <div class="ui-panel-head">
            <h2 class="ui-panel-title next-profile__card-title">
              <UserIcon class="next-profile__card-icon" aria-hidden="true" />账号
            </h2>
          </div>
          <div class="ui-panel-body">
            <dl class="next-profile__facts">
              <div v-for="row in accountRows" :key="row.label" class="next-profile__fact">
                <dt>{{ row.label }}</dt>
                <dd :data-testid="row.testid">{{ row.value }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section class="ui-panel next-profile__panel">
          <div class="ui-panel-head">
            <h2 class="ui-panel-title next-profile__card-title">
              <LockOnIcon class="next-profile__card-icon" aria-hidden="true" />修改密码
            </h2>
          </div>
          <div class="ui-panel-body">
            <p v-if="!isForced" class="next-profile__note">
              修改密码会同时撤销其他设备上的会话；当前会话保持有效。
            </p>
            <form class="next-profile__form" novalidate @submit.prevent="submit">
              <UiInput
                v-model="currentPassword"
                label="当前密码"
                large
                type="password"
                autocomplete="current-password"
                data-testid="current-password"
              />
              <UiInput
                v-model="newPassword"
                label="新密码"
                large
                type="password"
                autocomplete="new-password"
                hint="至少 8 个字符，包含大小写字母和数字。"
                data-testid="new-password"
              />
              <UiInput
                v-model="confirmPassword"
                label="确认新密码"
                large
                type="password"
                autocomplete="new-password"
                :error="errorMessage || undefined"
                data-testid="confirm-password"
              />
              <p
                v-if="errorRequestId"
                class="ui-request-id next-profile__reqid"
                role="alert"
                data-testid="password-error-reqid"
              >
                requestId: {{ errorRequestId }}
              </p>
              <div class="next-profile__actions">
                <UiButton
                  variant="primary"
                  native-type="submit"
                  :loading="submitting"
                  data-testid="password-submit"
                >
                  修改密码
                </UiButton>
              </div>
            </form>
          </div>
        </section>
      </div>

      <div v-if="!isForced" class="next-profile__col next-profile__col--side">
        <section class="ui-panel next-profile__panel">
          <div class="ui-panel-head">
            <h2 class="ui-panel-title next-profile__card-title">
              <DesktopIcon class="next-profile__card-icon" aria-hidden="true" />当前会话
            </h2>
          </div>
          <div class="ui-panel-body">
            <dl class="next-profile__facts next-profile__facts--single">
              <div class="next-profile__fact">
                <dt>当前会话到期</dt>
                <dd class="ui-mono" data-testid="session-expires">
                  {{ formatInstant(auth.user?.sessionExpiresAt) }}
                </dd>
              </div>
              <div class="next-profile__fact">
                <dt>上次登录</dt>
                <dd class="ui-mono" data-testid="last-login">
                  {{ formatInstant(auth.user?.lastLoginAt) }}
                </dd>
              </div>
            </dl>
            <p class="next-profile__note">
              「退出其他会话」将撤销除当前浏览器外的全部登录会话；其他设备需要重新登录。
            </p>
            <UiButton data-testid="logout-others" @click="revokeOpen = true">退出其他会话</UiButton>
          </div>
        </section>
      </div>
    </div>

    <UiDialog v-model:open="revokeOpen" title="退出其他会话" width="440px">
      <p class="next-profile__dialog-text">
        将撤销当前账号在其他设备（浏览器）上的全部会话；当前会话保持有效，其他设备需要重新登录。
      </p>
      <p v-if="revokeError" class="ui-form-error" data-testid="logout-others-error">
        {{ revokeError }}
      </p>
      <template #footer>
        <UiButton variant="ghost" @click="revokeOpen = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="revoking"
          data-testid="logout-others-confirm"
          @click="confirmRevokeOthers"
        >
          退出其他会话
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.next-profile__forced {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
  border-radius: var(--ui-radius-control);
  background: var(--ui-warning-bg);
  color: var(--ui-warning-fg);
  font-size: var(--ui-font-size-sm);
}

/* ---- identity header ---- */

.next-profile__identity {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  padding: var(--ui-space-5) var(--ui-space-6);
  margin-bottom: var(--ui-space-5);
}

.next-profile__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--ui-primary-soft);
  color: var(--ui-primary-text);
  font-size: 24px;
  font-weight: var(--ui-weight-semibold);
}

.next-profile__identity-text {
  min-width: 0;
}

.next-profile__identity-name {
  margin: 0;
  font-size: var(--ui-font-size-2xl);
  font-weight: var(--ui-weight-semibold);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground);
}

.next-profile__identity-handle {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-profile__identity-badges {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin-left: auto;
}

/* ---- monthly snapshot (analysis-card anatomy: tinted icon chip + label/value) ---- */

.next-profile__snapshot {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: var(--ui-space-5);
  overflow: hidden;
}

.next-profile__stat {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  min-width: 0;
  padding: var(--ui-space-4) var(--ui-space-5);
  color: inherit;
}

.next-profile__stat + .next-profile__stat {
  border-left: 1px solid var(--ui-border);
}

.next-profile__stat:hover {
  background: var(--ui-muted);
}

.next-profile__stat-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 38px;
  height: 38px;
  border-radius: 10px;
}

.next-profile__stat-chip svg {
  width: 18px;
  height: 18px;
}

.next-profile__stat-chip--blue {
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
}

.next-profile__stat-chip--green {
  background: var(--ui-success-bg);
  color: var(--ui-success-fg);
}

.next-profile__stat-chip--cyan {
  background: #e0f4f6;
  color: #0e7490;
}

.next-profile__stat-chip--gold {
  background: #fdf3e0;
  color: #a16207;
}

.next-profile__stat-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-profile__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-profile__stat-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
  color: var(--ui-foreground);
  white-space: nowrap;
}

.next-profile__stat-currency {
  font-style: normal;
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  margin-right: 2px;
}

.next-profile__snapshot-error {
  margin: calc(-1 * var(--ui-space-4)) 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-danger-fg);
}

/* ---- two-column body ---- */

.next-profile__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  align-items: start;
  gap: var(--ui-space-5);
}

.next-profile__grid--forced {
  grid-template-columns: minmax(0, 560px);
}

.next-profile__col {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-5);
  min-width: 0;
}

@media (max-width: 1024px) {
  .next-profile__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .next-profile__snapshot {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .next-profile__stat:nth-child(3) {
    border-left: none;
    border-top: 1px solid var(--ui-border);
  }

  .next-profile__stat:nth-child(4) {
    border-top: 1px solid var(--ui-border);
  }
}

/* ---- cards + facts + form ---- */

.next-profile__card-title {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-profile__card-icon {
  font-size: 15px;
  color: var(--ui-foreground-faint);
}

.next-profile__facts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ui-space-4) var(--ui-space-8);
  margin: 0;
}

.next-profile__facts--single {
  grid-template-columns: 1fr;
}

.next-profile__fact {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding-bottom: var(--ui-space-2);
  min-width: 0;
}

.next-profile__fact dt {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-profile__fact dd {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  overflow-wrap: anywhere;
}

.next-profile__note {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-faint);
}

.next-profile__reqid {
  margin: -var(--ui-space-3) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-profile__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-5);
  max-width: 420px;
}

.next-profile__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-profile__dialog-text {
  margin: 0 0 var(--ui-space-2);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}
</style>
