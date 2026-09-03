<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { BrowseIcon, BrowseOffIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

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
  if (loading.value) {
    return;
  }
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
  await router.push(redirect ?? '/app/overview');
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
  <div class="login-page">
    <aside class="login-intro">
      <div class="intro-inner">
        <div class="intro-brand">MiQroGate</div>
        <h1 class="intro-headline">
          凭证与额度的账本，<br />
          <span class="intro-accent">安静地坐在中间。</span>
        </h1>
        <p class="intro-copy">
          MiQroGate 是内部 AI 编码流量的凭证治理网关：一个 Virtual Key
          绑定一个项目、一个供应商产品、一份额度。不跨供应商、不负载均衡、不留 prompt。
        </p>
        <ul class="intro-points">
          <li><span class="point-mark"></span>一个 Virtual Key，只绑一个项目、一个供应商产品</li>
          <li><span class="point-mark"></span>用量与额度入账，只告警、不阻断</li>
          <li><span class="point-mark"></span>prompt 与回答正文零留存</li>
        </ul>
      </div>
    </aside>

    <main class="login-side">
      <section class="login-panel" data-testid="login-panel">
        <div class="panel-head">
          <h2 class="login-title">{{ mode === 'login' ? '登录' : '创建账号' }}</h2>
          <p class="login-subtitle">
            {{ mode === 'login' ? '使用门户账号登录。' : '注册后立即可用，无需审核。' }}
          </p>
        </div>

        <div class="mode-tabs" role="tablist">
          <button
            type="button"
            class="mode-tab"
            :class="{ active: mode === 'login' }"
            data-testid="tab-login"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            class="mode-tab"
            :class="{ active: mode === 'register' }"
            data-testid="tab-register"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <t-alert
          v-if="errorMessage"
          :title="errorMessage"
          theme="error"
          class="login-error"
          data-testid="login-error"
        >
          <span v-if="errorRequestId" class="error-request-id mk-mono"
            >requestId: {{ errorRequestId }}</span
          >
        </t-alert>

        <t-form label-align="top" :prevent-submit-default="true" @submit="submit">
          <t-form-item label="账号">
            <t-input
              v-model="username"
              autocomplete="username"
              placeholder="设置你的登录账号"
              data-testid="login-username"
            />
          </t-form-item>
          <t-form-item v-if="mode === 'register'" label="昵称（可选）">
            <t-input
              v-model="displayName"
              autocomplete="name"
              placeholder="团队里展示的名字"
              data-testid="register-display-name"
            />
          </t-form-item>
          <t-form-item label="密码">
            <t-input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              :placeholder="mode === 'login' ? '请输入密码' : '至少 8 位，含大小写字母和数字'"
              data-testid="login-password"
            >
              <template #suffix-icon>
                <component
                  :is="showPassword ? BrowseOffIcon : BrowseIcon"
                  aria-label="切换密码可见性"
                  role="button"
                  @click="showPassword = !showPassword"
                />
              </template>
            </t-input>
          </t-form-item>
          <t-form-item v-if="mode === 'register'" label="确认密码">
            <t-input
              v-model="confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              data-testid="register-confirm"
            />
          </t-form-item>
          <t-button
            theme="primary"
            type="submit"
            :loading="loading"
            class="login-submit"
            data-testid="login-submit"
          >
            {{ mode === 'login' ? '登录' : '注册并进入' }}
          </t-button>
        </t-form>
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 100vh;
  background: var(--miqrokey-bg-canvas);
}

/* Left brand column — deep slate panel, white type: the product face. */
.login-intro {
  display: none;
  flex-direction: column;
  justify-content: center;
  padding: clamp(32px, 6vw, 96px);
  background: #0f172a;
  border-right: 1px solid rgba(15, 23, 42, 0.08);
}

.intro-inner {
  max-width: 480px;
  margin-left: auto;
  margin-right: clamp(0px, 3vw, 48px);
}

@media (min-width: 960px) {
  .login-intro {
    display: flex;
  }
}

.intro-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #7dd3fc;
  margin-bottom: 32px;
}

.intro-brand::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: #38bdf8;
  box-shadow: 0 0 0 4px rgba(56, 189, 248, 0.18);
}

.intro-headline {
  margin: 0 0 var(--miqrokey-space-5);
  font-size: clamp(28px, 2.8vw, 42px);
  font-weight: 700;
  line-height: 1.32;
  color: #f8fafc;
  letter-spacing: -0.02em;
}

.intro-accent {
  color: #7dd3fc;
  font-weight: 650;
}

.intro-copy {
  max-width: 430px;
  margin: 0 0 40px;
  font-size: 14.5px;
  line-height: 2;
  color: rgba(226, 232, 240, 0.72);
}

.intro-points {
  max-width: 430px;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.intro-points li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(226, 232, 240, 0.9);
}

.point-mark {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 0 4px rgba(56, 189, 248, 0.16);
}

.login-side {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
}

.login-panel {
  width: min(400px, 100%);
  padding: 40px 36px 36px;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: 10px;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 12px 32px rgba(15, 23, 42, 0.08);
}

.panel-head {
  text-align: center;
  margin-bottom: 20px;
}

.login-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: #0f172a;
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

/* Login / register underline tabs */
.mode-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  margin-bottom: 26px;
  border-bottom: 1px solid var(--miqrokey-border-default);
}

.mode-tab {
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 0 0 10px;
  font-size: 15px;
  font-weight: 500;
  color: var(--miqrokey-text-secondary);
  background: transparent;
  cursor: pointer;
  margin-bottom: -1px;
  transition:
    color 0.15s ease,
    border-color 0.15s ease;
}

.mode-tab:hover {
  color: var(--miqrokey-text-primary);
}

.mode-tab.active {
  color: var(--miqrokey-accent);
  border-bottom-color: var(--miqrokey-accent);
  font-weight: 600;
}

.mode-tab:focus-visible {
  outline: 2px solid var(--miqrokey-accent);
  outline-offset: 1px;
}

.login-error {
  margin-bottom: 16px;
}

.error-request-id {
  display: block;
  margin-top: 4px;
  color: var(--miqrokey-text-secondary);
}

/* Taller, friendlier inputs on the auth card */
.login-panel :deep(.t-input__wrap) {
  font-size: 14px;
}

.login-panel :deep(.t-input) {
  min-height: 44px;
  border-radius: 8px;
  border-color: var(--miqrokey-border-default);
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.login-panel :deep(.t-input:hover:not(:focus-within)) {
  border-color: #cbd5e1;
}

.login-panel :deep(.t-input:focus-within) {
  border-color: var(--miqrokey-accent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--miqrokey-accent) 18%, transparent);
}

.login-panel :deep(.t-form__label) {
  font-size: 13px;
  font-weight: 500;
}

.login-submit {
  width: 100%;
  min-height: 46px;
  font-size: 15px;
  margin-top: 6px;
  border-radius: 8px;
  transition:
    box-shadow 0.15s ease,
    transform 0.15s ease;
}

.login-submit:hover {
  box-shadow: 0 4px 16px rgba(0, 102, 255, 0.32);
  transform: translateY(-1px);
}

.login-submit:active {
  transform: translateY(0);
  box-shadow: none;
}
</style>
