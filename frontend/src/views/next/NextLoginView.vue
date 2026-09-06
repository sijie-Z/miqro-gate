<script setup lang="ts">
/**
 * NextLoginView — /login page (Vben Admin console edition, 2026-09-06).
 * Split-screen login: deep-blue brand panel + white form column. Login /
 * register dual mode. Logic mirrors the legacy LoginView (register-and-enter,
 * redirect query, error envelope). Visual master: v2.vben.pro login screen.
 */
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { UiButton, UiInput } from '@/ui';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

type Mode = 'login' | 'register';

const mode = ref<Mode>('login');
const username = ref('');
const displayName = ref('');
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');

function switchMode(next: Mode) {
  mode.value = next;
  errorMessage.value = '';
  errorRequestId.value = '';
  password.value = '';
  confirmPassword.value = '';
}

async function submit() {
  if (loading.value) return;
  errorMessage.value = '';
  errorRequestId.value = '';
  if (mode.value === 'login') {
    if (!username.value || !password.value) {
      errorMessage.value = '请输入账号和密码。';
      return;
    }
    loading.value = true;
    try {
      await auth.login(username.value.trim(), password.value);
      await afterAuthenticated();
    } catch (error) {
      renderError(error, '登录失败，请稍后重试。');
    } finally {
      loading.value = false;
    }
    return;
  }

  // register
  if (!username.value || !password.value || !confirmPassword.value) {
    errorMessage.value = '请填写账号和密码。';
    return;
  }
  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致。';
    return;
  }
  loading.value = true;
  try {
    await auth.register(
      username.value.trim(),
      displayName.value.trim() || undefined,
      password.value,
    );
    await afterAuthenticated();
  } catch (error) {
    renderError(error, '注册失败，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function afterAuthenticated() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined;
  await router.push(redirect ?? '/app-new/keys');
}

function renderError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    errorMessage.value = error.message;
    errorRequestId.value = error.requestId ?? '';
  } else {
    errorMessage.value = fallback;
  }
}
</script>

<template>
  <div class="next-login">
    <aside class="next-login__brand" aria-hidden="true">
      <div class="next-login__brand-top">
        <span class="next-login__brand-mark">M</span>
        <span class="next-login__brand-name">MiQroGate</span>
      </div>

      <!-- Scene illustration floating on the brand panel (Vben style) —
           white strokes and translucent fills, flat, no gradients -->
      <div class="next-login__art" aria-hidden="true">
        <svg viewBox="0 0 460 210" fill="none" xmlns="http://www.w3.org/2000/svg">
          <!-- ground -->
          <path d="M30 178h400" stroke="rgba(255,255,255,0.35)" stroke-width="2" stroke-linecap="round" />
          <!-- monitor -->
          <rect x="150" y="40" width="170" height="116" rx="16" fill="rgba(255,255,255,0.16)"
            stroke="rgba(255,255,255,0.85)" stroke-width="2" />
          <rect x="161" y="51" width="148" height="92" rx="9" fill="rgba(10,26,66,0.9)" />
          <rect x="177" y="115" width="16" height="20" rx="3" fill="#6aa5ff" />
          <rect x="203" y="97" width="16" height="38" rx="3" fill="#93b9ff" />
          <rect x="229" y="78" width="16" height="57" rx="3" fill="#c4d8ff" />
          <path d="M262 122l16-15 15 11 22-22" stroke="#eef4ff" stroke-width="3"
            stroke-linecap="round" stroke-linejoin="round" />
          <circle cx="294" cy="84" r="4" fill="#ffd9a0" />
          <!-- stand -->
          <path d="M235 156v10M235 166h-30M235 166h30" stroke="rgba(255,255,255,0.6)"
            stroke-width="5" stroke-linecap="round" />
          <!-- shield + check -->
          <path d="M56 96c12-4 22-4 32 0v18c0 15-15 25-32 30-17-5-32-15-32-30V96c10-4 20-4 32 0Z"
            fill="rgba(255,255,255,0.12)" stroke="rgba(255,255,255,0.85)" stroke-width="2" />
          <path d="m68 106-10 11-5-5" stroke="#a8e8c5" stroke-width="3.5" stroke-linecap="round"
            stroke-linejoin="round" />
          <!-- key -->
          <circle cx="392" cy="92" r="18" stroke="rgba(255,255,255,0.85)" stroke-width="4" />
          <path d="M392 110v6m0-8 10 10" stroke="rgba(255,255,255,0.85)" stroke-width="4"
            stroke-linecap="round" />
          <!-- dots -->
          <circle cx="120" cy="50" r="5" fill="#ffd9a0" />
          <circle cx="420" cy="46" r="4" fill="rgba(255,255,255,0.55)" />
          <circle cx="58" cy="140" r="3" fill="rgba(255,255,255,0.4)" />
        </svg>
      </div>

      <div class="next-login__brand-body">
        <p class="next-login__brand-title">企业内 AI 编码流量的凭证与用量治理</p>
        <p class="next-login__brand-desc">
          MiQroKey Gateway · 一次签发、处处留痕；把上游密钥和每一分用量管在看得见的地方。
        </p>
      </div>
    </aside>

    <main class="next-login__form-side" data-testid="login-panel">
      <section class="next-login__form-col">
        <div class="next-login__tabs" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            class="next-login__tab"
            :class="{ 'next-login__tab--active': mode === 'login' }"
            data-testid="tab-login"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            class="next-login__tab"
            :class="{ 'next-login__tab--active': mode === 'register' }"
            data-testid="tab-register"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <header class="next-login__head">
          <h1 class="next-login__title">{{ mode === 'login' ? '登录 MiQroGate' : '创建账号' }}</h1>
          <p class="next-login__subtitle">
            {{ mode === 'login' ? '使用门户账号进入控制台。' : '注册后立即可用，无需审核。' }}
          </p>
        </header>

        <div v-if="errorMessage" class="next-login__error" role="alert" data-testid="login-error">
          {{ errorMessage
          }}<span v-if="errorRequestId" class="ui-request-id">
            requestId: {{ errorRequestId }}</span
          >
        </div>

        <form class="next-login__form" novalidate @submit.prevent="submit">
          <UiInput
            v-model="username"
            label="账号"
            large
            autocomplete="username"
            :placeholder="mode === 'register' ? '设置你的登录账号' : '请输入账号'"
            data-testid="login-username"
          />
          <UiInput
            v-if="mode === 'register'"
            v-model="displayName"
            label="昵称（可选）"
            large
            autocomplete="name"
            placeholder="团队里展示的名字"
            data-testid="register-display-name"
          />
          <UiInput
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            label="密码"
            large
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :placeholder="mode === 'login' ? '请输入密码' : '至少 8 位，含大小写字母和数字'"
            data-testid="login-password"
          >
            <template #suffix>
              <button
                type="button"
                class="next-login__eye"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                data-testid="password-toggle"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
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
          <UiInput
            v-if="mode === 'register'"
            v-model="confirmPassword"
            :type="showPassword ? 'text' : 'password'"
            label="确认密码"
            large
            autocomplete="new-password"
            placeholder="再次输入密码"
            data-testid="register-confirm"
          />
          <UiButton
            variant="primary"
            size="lg"
            native-type="submit"
            :loading="loading"
            class="next-login__submit"
            data-testid="login-submit"
          >
            {{ mode === 'login' ? '登录' : '注册并进入' }}
          </UiButton>
        </form>
        <p class="next-login__foot">MiQroGate · 内部 AI 编码流量凭证治理网关</p>
      </section>
    </main>
  </div>
</template>

<style scoped>
.next-login {
  display: grid;
  grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  min-height: 100vh;
  color: var(--ui-foreground);
}

/* ---- brand panel ---- */
.next-login__brand {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: var(--ui-space-8) var(--ui-space-10) var(--ui-space-10);
  background: var(--ui-login-panel);
  color: var(--ui-foreground-inverse);
}

.next-login__brand-top {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-login__brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-primary);
  font-size: 18px;
  font-weight: 700;
}

.next-login__brand-name {
  font-size: var(--ui-font-size-xl);
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.next-login__art {
  width: 100%;
  max-width: 560px;
  margin: var(--ui-space-6) auto;
}

.next-login__art svg {
  display: block;
  width: 100%;
  height: auto;
}

.next-login__brand-body {
  max-width: 480px;
}

.next-login__brand-title {
  margin: 0;
  font-size: 26px;
  font-weight: var(--ui-weight-semibold);
  line-height: 1.5;
  letter-spacing: -0.01em;
}

.next-login__brand-desc {
  margin: var(--ui-space-3) 0 0;
  font-size: var(--ui-font-size-sm);
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.72);
}

/* ---- form column ---- */
.next-login__form-side {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--ui-card);
  padding: var(--ui-space-8) var(--ui-space-6);
}

.next-login__form-col {
  width: min(400px, 100%);
  display: flex;
  flex-direction: column;
}

.next-login__tabs {
  display: flex;
  align-self: stretch;
  gap: var(--ui-space-5);
  margin-bottom: var(--ui-space-8);
}

.next-login__tab {
  border: 0;
  height: 40px;
  padding: 0 2px;
  border-bottom: 2px solid transparent;
  background: transparent;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
  transition:
    color var(--ui-ease),
    border-color var(--ui-ease);
}

.next-login__tab:hover {
  color: var(--ui-foreground);
}

.next-login__tab--active {
  border-bottom-color: var(--ui-primary);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-login__tab--active:hover {
  color: var(--ui-primary-active);
}

.next-login__tab:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.next-login__head {
  margin-bottom: var(--ui-space-5);
}

.next-login__title {
  margin: 0;
  font-size: 26px;
  font-weight: var(--ui-weight-semibold);
  line-height: 1.3;
  letter-spacing: -0.01em;
}

.next-login__subtitle {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-base);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.next-login__error {
  margin-bottom: var(--ui-space-4);
  padding: var(--ui-space-3) var(--ui-space-4);
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.next-login__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-5);
}

.next-login__form :deep(.ui-field) {
  gap: var(--ui-space-2);
}

.next-login__form :deep(.ui-field__label) {
  font-size: var(--ui-font-size-base);
}

/* login screen controls sit taller than in-console controls (44px) */
.next-login__form :deep(.ui-field--large .ui-field__input) {
  height: 44px;
}

.next-login__form :deep(.ui-field--large .ui-field__suffix) {
  height: 44px;
}

.next-login__submit {
  width: 100%;
  height: 44px;
  margin-top: var(--ui-space-2);
}

.next-login__eye {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-login__eye:hover {
  color: var(--ui-foreground);
  background: var(--ui-fill-hover);
}

.next-login__eye:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.next-login__foot {
  margin: var(--ui-space-6) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  text-align: center;
}

/* ---- responsive: brand panel yields to a single centered column ---- */
@media (max-width: 959px) {
  .next-login {
    grid-template-columns: 1fr;
  }

  .next-login__brand {
    display: none;
  }

  .next-login__form-side {
    padding: var(--ui-space-10) var(--ui-space-6);
  }
}
</style>
