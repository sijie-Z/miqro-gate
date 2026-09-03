<script setup lang="ts">
/**
 * NextProfileView — /app-new/profile pilot page (UI U1, PostHog language).
 * Behaviour parity with legacy ProfileView: account facts + password change
 * (forced flow for must-change sessions included).
 */
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { UiButton, UiInput, toast } from '@/ui';

const auth = useAuthStore();
const router = useRouter();

const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const submitting = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');

const isForced = computed(() => auth.mustChangePassword);

const accountRows = computed(() => [
  { label: '用户名', value: auth.user?.username ?? '—' },
  { label: '显示名称', value: auth.user?.displayName || '—' },
  { label: '角色', value: auth.user?.role === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' },
  {
    label: '会话到期',
    value: auth.user?.sessionExpiresAt
      ? new Date(auth.user.sessionExpiresAt).toLocaleString()
      : '—',
    mono: true,
  },
]);

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
  try {
    await auth.changePassword(currentPassword.value, newPassword.value);
    toast.success('密码已修改');
    if (isForced.value) {
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

    <section class="ui-panel next-profile__panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">账号</h2>
      </div>
      <div class="ui-panel-body">
        <dl class="next-profile__grid">
          <template v-for="row in accountRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd
              :class="{ 'ui-mono': row.mono }"
              :data-testid="row.label === '用户名' ? 'account-username' : undefined"
            >
              {{ row.value }}
            </dd>
          </template>
        </dl>
      </div>
    </section>

    <section class="ui-panel next-profile__panel next-profile__panel--narrow">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">修改密码</h2>
      </div>
      <div class="ui-panel-body">
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
            data-testid="confirm-password"
          />
          <p v-if="errorMessage" class="ui-form-error" role="alert" data-testid="password-error">
            {{ errorMessage
            }}<span v-if="errorRequestId" class="ui-request-id">
              requestId: {{ errorRequestId }}</span
            >
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

.next-profile__panel {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-profile__panel--narrow {
  max-width: 560px;
}

.next-profile__grid {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: var(--ui-space-3) var(--ui-space-5);
  margin: 0;
  font-size: var(--ui-font-size-sm);
}

.next-profile__grid dt {
  color: var(--ui-foreground-secondary);
}

.next-profile__grid dd {
  margin: 0;
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
</style>
