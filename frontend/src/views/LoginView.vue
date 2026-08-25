<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const username = ref('');
const password = ref('');
const loading = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');

async function submit() {
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
      router.push(redirect ?? '/app/keys');
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
    <div class="login-panel">
      <h1 class="login-title">MiQroKey</h1>
      <p class="login-subtitle">Virtual Key 管理门户</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="username"
            name="username"
            autocomplete="username"
            data-testid="username"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            name="password"
            autocomplete="current-password"
            show-password
            data-testid="password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-alert
          v-if="errorMessage"
          type="error"
          :closable="false"
          class="login-error"
          data-testid="login-error"
        >
          <template #default>
            {{ errorMessage }}
            <span v-if="errorRequestId" class="mk-mono error-request-id"
              >requestId: {{ errorRequestId }}</span
            >
          </template>
        </el-alert>
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
          class="login-submit"
          data-testid="login-submit"
        >
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--miqrokey-bg-canvas);
}

.login-panel {
  width: 360px;
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
