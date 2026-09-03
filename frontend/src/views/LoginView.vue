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
    await auth.register(username.value.trim(), displayName.value.trim() || undefined, password.value);
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
        <div class="intro-quota-card">
          <div class="mk-quota-band">
            <div class="mk-quota-segment">
              <div class="mk-quota-segment-label">
                <span>5 小时</span><span class="mk-num">34%</span>
              </div>
              <div class="mk-quota-track"><div class="mk-quota-fill" style="width: 34%" /></div>
            </div>
            <div class="mk-quota-segment">
              <div class="mk-quota-segment-label">
                <span>本周</span><span class="mk-num">27%</span>
              </div>
              <div class="mk-quota-track"><div class="mk-quota-fill" style="width: 27%" /></div>
            </div>
            <div class="mk-quota-segment">
              <div class="mk-quota-segment-label">
                <span>本月</span><span class="mk-num">20%</span>
              </div>
              <div class="mk-quota-track"><div class="mk-quota-fill" style="width: 20%" /></div>
            </div>
          </div>
          <p class="intro-quota-note">滚动额度窗口 —— 每家供应商的套餐都按这个节拍运转。</p>
        </div>
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

/* Left brand column — content vertically centered with breathing room. */
.login-intro {
  display: none;
  flex-direction: column;
  justify-content: center;
  padding: clamp(32px, 6vw, 96px);
  background: var(--miqrokey-bg-surface);
  border-right: 1px solid var(--miqrokey-border-default);
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
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--miqrokey-accent);
  margin-bottom: var(--miqrokey-space-6);
}

.intro-headline {
  margin: 0 0 var(--miqrokey-space-5);
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 600;
  line-height: 1.4;
  color: var(--miqrokey-text-primary);
}

.intro-accent {
  color: var(--miqrokey-accent);
  font-weight: 650;
}

.intro-copy {
  max-width: 420px;
  margin: 0 0 var(--miqrokey-space-8);
  font-size: 14px;
  line-height: 1.8;
  color: var(--miqrokey-text-secondary);
}

.intro-quota-card {
  max-width: 420px;
  padding: var(--miqrokey-space-5);
  background: var(--miqrokey-bg-canvas);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.intro-quota-note {
  margin: var(--miqrokey-space-3) 0 0;
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
}

/* Right column — card centered with real presence. */
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
  box-shadow: 0 10px 34px rgba(15, 23, 42, 0.08);
}

.panel-head {
  text-align: center;
  margin-bottom: 20px;
}

.login-title {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  color: var(--miqrokey-text-primary);
}

.login-subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

/* Login / register segmented switch */
.mode-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin-bottom: 24px;
  background: var(--miqrokey-bg-canvas);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: 8px;
}

.mode-tab {
  border: 0;
  padding: 8px 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--miqrokey-text-secondary);
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.mode-tab.active {
  background: var(--miqrokey-bg-surface);
  color: var(--miqrokey-text-primary);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.1);
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
  min-height: 40px;
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
  min-height: 44px;
  font-size: 15px;
  margin-top: 4px;
}
</style>
