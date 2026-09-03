<script setup lang="ts">
/**
 * NextLoginView — /login-new pilot page (UI U0, PostHog language).
 * Login / register dual-mode card over warm paper canvas. Logic mirrors the
 * legacy LoginView (register-and-enter, redirect query, error envelope).
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
    <main class="next-login__side">
      <section class="next-login__card" data-testid="login-panel">
        <header class="next-login__head">
          <div class="next-login__mark" aria-hidden="true">M</div>
          <h1 class="next-login__title">{{ mode === 'login' ? '登录 MiQroGate' : '创建账号' }}</h1>
          <p class="next-login__subtitle">
            {{ mode === 'login' ? '使用门户账号进入控制台。' : '注册后立即可用，无需审核。' }}
          </p>
        </header>

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
            placeholder="设置你的登录账号"
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
      </section>
      <p class="next-login__foot">MiQroGate · 内部 AI 编码流量凭证治理网关</p>
    </main>
  </div>
</template>

<style scoped>
.next-login {
  min-height: 100vh;
  display: flex;
  background: var(--ui-background);
  color: var(--ui-foreground);
}

.next-login__side {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--ui-space-8) var(--ui-space-4);
}

.next-login__card {
  width: min(460px, 100%);
  padding: 44px 44px 40px;
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-dialog);
  box-shadow: var(--ui-shadow-dialog);
}

.next-login__head {
  text-align: center;
  margin-bottom: var(--ui-space-8);
}

.next-login__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-primary);
  color: #fff;
  font-size: 21px;
  font-weight: 700;
  margin-bottom: var(--ui-space-5);
}

.next-login__title {
  margin: 0;
  font-size: 21px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.next-login__subtitle {
  margin: var(--ui-space-2) 0 0;
  font-size: 14px;
  color: #56565e;
}

.next-login__tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
  margin-bottom: var(--ui-space-6);
}

.next-login__tab {
  border: 0;
  height: 34px;
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground-secondary);
  background: transparent;
  cursor: pointer;
  transition:
    color var(--ui-ease),
    background-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.next-login__tab:hover {
  color: var(--ui-foreground);
}

.next-login__tab--active {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-login__tab--active:hover {
  color: var(--ui-primary);
}

.next-login__tab:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
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
  gap: 24px;
}

.next-login__form :deep(.ui-field) {
  gap: var(--ui-space-2);
}

.next-login__eye {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
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

.next-login__submit {
  width: 100%;
  margin-top: var(--ui-space-2);
}

.next-login__foot {
  margin: var(--ui-space-6) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  text-align: center;
}
</style>
