<script setup lang="ts">
/**
 * NextLoginView — /login page (Vben Admin console edition, 2026-09-06).
 * Split-screen login: deep-blue brand panel + white form column. Login /
 * register dual mode. Logic mirrors the legacy LoginView (register-and-enter,
 * redirect query, error envelope). Visual master: v2.vben.pro login screen.
 */
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChartBarIcon, LockOnIcon, NotificationIcon } from 'tdesign-icons-vue-next';
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

      <!-- Scene illustration on a white surface card (Vben login style):
           gateway console + plant + key, flat palette, no gradients -->
      <div class="next-login__art" aria-hidden="true">
        <svg viewBox="0 0 400 170" fill="none" xmlns="http://www.w3.org/2000/svg">
          <!-- slim ground line -->
          <path d="M18 138h364" stroke="#dbe3f2" stroke-width="2" stroke-linecap="round" />
          <!-- monitor -->
          <rect x="120" y="34" width="170" height="104" rx="14" fill="#ffffff" stroke="#c9d6ec" stroke-width="2" />
          <rect x="130" y="44" width="150" height="84" rx="8" fill="#0f2d6b" />
          <rect x="146" y="100" width="16" height="20" rx="3" fill="#6aa5ff" />
          <rect x="172" y="84" width="16" height="36" rx="3" fill="#93b9ff" />
          <rect x="198" y="66" width="16" height="54" rx="3" fill="#c4d8ff" />
          <path d="M230 106l16-14 14 10 22-20" stroke="#eef4ff" stroke-width="3"
            stroke-linecap="round" stroke-linejoin="round" />
          <circle cx="268" cy="76" r="4" fill="#ffd9a0" />
          <!-- monitor stand -->
          <path d="M205 138v-8M205 130h-28M205 130h28" stroke="#b9c6dd" stroke-width="5"
            stroke-linecap="round" />
          <!-- plant in a small pot -->
          <path d="M46 138h34l-4-14H50l-4 14Z" fill="#f3b45e" />
          <path d="M63 124V96" stroke="#3f9e6f" stroke-width="3" stroke-linecap="round" />
          <path d="M63 100c-4-10-16-12-20-8 6 4 14 6 20 8Zm0 6c6-8 16-8 20-3-7 3-14 4-20 3Z"
            fill="#7fd0a3" />
          <!-- key leaning on the desk -->
          <circle cx="336" cy="104" r="13" stroke="#8fb4e8" stroke-width="4" />
          <path d="M336 117v3m0-4 7 7" stroke="#8fb4e8" stroke-width="4" stroke-linecap="round" />
          <!-- soft floating dots -->
          <circle cx="84" cy="52" r="5" fill="#ffd9a0" opacity="0.9" />
          <circle cx="358" cy="44" r="4" fill="#b9d0f5" />
          <circle cx="62" cy="76" r="3" fill="#ffffff" opacity="0.55" />
        </svg>
      </div>

      <div class="next-login__brand-body">
        <ul class="next-login__brand-features" aria-hidden="true">
          <li class="next-login__brand-feature">
            <LockOnIcon class="next-login__brand-icon" />
            <span>上游密钥加密存放，目录签名后下发</span>
          </li>
          <li class="next-login__brand-feature">
            <ChartBarIcon class="next-login__brand-icon" />
            <span>每次调用按 Key 记账，用量成本可追溯</span>
          </li>
          <li class="next-login__brand-feature">
            <NotificationIcon class="next-login__brand-icon" />
            <span>配额与预算只告警，从不悄悄截断</span>
          </li>
        </ul>
        <div class="next-login__brand-bottom">
          <p class="next-login__brand-title">企业内 AI 编码流量的凭证与用量治理</p>
          <p class="next-login__brand-desc">
            MiQroKey Gateway · 一次签发、处处留痕；把上游密钥和每一分用量管在看得见的地方。
          </p>
        </div>
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
  max-width: 440px;
  margin: var(--ui-space-8) 0 var(--ui-space-4);
  padding: 10px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: var(--ui-shadow-dialog);
}

.next-login__art svg {
  display: block;
  width: 100%;
  height: auto;
}

.next-login__brand-body {
  margin: auto 0;
  max-width: 440px;
}

.next-login__brand-features {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-login__brand-feature {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: rgba(255, 255, 255, 0.78);
}

.next-login__brand-icon {
  width: 16px;
  height: 16px;
  color: rgba(255, 255, 255, 0.92);
  flex-shrink: 0;
  display: block;
}

.next-login__brand-bottom {
  margin-top: var(--ui-space-4);
}

.next-login__brand-title {
  margin: 0;
  font-size: 22px;
  font-weight: var(--ui-weight-semibold);
  line-height: 1.55;
  letter-spacing: -0.01em;
}

.next-login__brand-desc {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-sm);
  line-height: 1.8;
  color: rgba(255, 255, 255, 0.7);
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
