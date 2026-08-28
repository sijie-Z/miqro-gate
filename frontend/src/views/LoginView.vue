<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { BrowseIcon, BrowseOffIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const username = ref('');
const password = ref('');
const showPassword = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');

async function submit() {
  if (loading.value) {
    return; // implicit Enter submission and button click share one path
  }
  if (!username.value || !password.value) {
    errorMessage.value = '请输入用户名和密码。';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  errorRequestId.value = '';
  try {
    await auth.login(username.value.trim(), password.value);
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined;
    if (auth.mustChangePassword) {
      router.push('/app/profile');
    } else {
      router.push(redirect ?? '/app/overview');
    }
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message;
      errorRequestId.value = error.requestId ?? '';
    } else {
      errorMessage.value = '登录失败，请稍后重试。';
    }
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <aside class="login-intro">
      <div class="intro-brand">MiQroGate</div>
      <h1 class="intro-headline">凭证与额度的账本，<br />安静地坐在中间。</h1>
      <p class="intro-copy">
        MiQroGate 是内部 AI 编码流量的凭证治理网关：一个 Virtual Key
        绑定一个项目、一个供应商产品、一份额度。不跨供应商、不负载均衡、不留 prompt。
      </p>
      <div class="intro-quota">
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
    </aside>
    <div class="login-panel">
      <h2 class="login-title">登录</h2>
      <p class="login-subtitle">使用门户账号登录。首次登录需修改临时密码。</p>
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
        <t-form-item label="用户名">
          <t-input v-model="username" autocomplete="username" data-testid="login-username" />
        </t-form-item>
        <t-form-item label="密码">
          <t-input
            v-model="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
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
        <t-button
          theme="primary"
          type="submit"
          :loading="loading"
          class="login-submit"
          data-testid="login-submit"
        >
          登录
        </t-button>
      </t-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: stretch;
  min-height: 100vh;
  background: var(--miqrokey-bg-canvas);
}

.login-intro {
  flex: 1;
  display: none;
  flex-direction: column;
  justify-content: center;
  padding: var(--miqrokey-space-8);
  background: var(--miqrokey-bg-surface);
  border-right: 1px solid var(--miqrokey-border-default);
}

@media (min-width: 900px) {
  .login-intro {
    display: flex;
  }
}

.intro-brand {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--miqrokey-accent);
  margin-bottom: var(--miqrokey-space-6);
}

.intro-headline {
  margin: 0 0 var(--miqrokey-space-4);
  font-size: 28px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--miqrokey-text-primary);
}

.intro-copy {
  max-width: 440px;
  margin: 0 0 var(--miqrokey-space-8);
  font-size: 14px;
  line-height: 1.7;
  color: var(--miqrokey-text-secondary);
}

.intro-quota {
  max-width: 440px;
}

.intro-quota-note {
  margin: var(--miqrokey-space-3) 0 0;
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
}

.login-panel {
  width: 360px;
  margin: auto;
  padding: 32px;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.login-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.login-subtitle {
  margin: 4px 0 24px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.login-error {
  margin-bottom: 16px;
}

.error-request-id {
  display: block;
  margin-top: 4px;
  color: var(--miqrokey-text-secondary);
}

.login-submit {
  width: 100%;
}
</style>
