<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const router = useRouter();

const currentPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const submitting = ref(false);
const errorMessage = ref('');
const errorRequestId = ref('');
const successMessage = ref('');

const isForced = computed(() => auth.mustChangePassword);

async function submit() {
  errorMessage.value = '';
  successMessage.value = '';
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
    successMessage.value = '密码已修改。';
    ElMessage.success('密码已修改');
    if (isForced.value) {
      router.push('/app/keys');
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
  <div class="profile-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ isForced ? '设置新密码' : 'Profile' }}</h2>
        <p v-if="isForced" class="page-desc forced-hint">
          首次登录必须修改临时密码后才能使用门户。
        </p>
        <p v-else class="page-desc">账号信息与安全设置。</p>
      </div>
    </div>

    <el-alert
      v-if="isForced"
      type="warning"
      :closable="false"
      class="forced-alert"
      data-testid="forced-password"
    >
      账号使用临时密码，请在修改密码后继续。
    </el-alert>

    <section class="account-panel">
      <h3 class="panel-title">账号</h3>
      <dl class="account-grid">
        <dt>用户名</dt>
        <dd data-testid="account-username">{{ auth.user?.username }}</dd>
        <dt>显示名称</dt>
        <dd>{{ auth.user?.displayName }}</dd>
        <dt>角色</dt>
        <dd>{{ auth.user?.role === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}</dd>
        <dt>会话到期</dt>
        <dd class="mk-mono">
          {{
            auth.user?.sessionExpiresAt
              ? new Date(auth.user.sessionExpiresAt).toLocaleString()
              : '—'
          }}
        </dd>
      </dl>
    </section>

    <section class="password-panel">
      <h3 class="panel-title">修改密码</h3>
      <el-form label-position="top" class="password-form" @submit.prevent="submit">
        <el-form-item label="当前密码" required>
          <el-input
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            show-password
            data-testid="current-password"
          />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            show-password
            data-testid="new-password"
          />
          <div class="field-hint">至少 8 个字符，包含大小写字母和数字。</div>
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            show-password
            data-testid="confirm-password"
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          type="error"
          :closable="false"
          class="form-error"
          data-testid="password-error"
        >
          <template #default>
            {{ errorMessage }}
            <span v-if="errorRequestId" class="mk-mono">requestId: {{ errorRequestId }}</span>
          </template>
        </el-alert>

        <el-button
          type="primary"
          native-type="submit"
          :loading="submitting"
          data-testid="password-submit"
        >
          修改密码
        </el-button>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: var(--miqrokey-font-size-title);
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.forced-hint {
  color: var(--miqrokey-warning);
}

.forced-alert {
  margin-bottom: 16px;
}

.account-panel,
.password-panel {
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.account-grid {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px 16px;
  margin: 0;
}

.account-grid dt {
  color: var(--miqrokey-text-secondary);
  font-size: 13px;
}

.account-grid dd {
  margin: 0;
  font-size: 13px;
}

.password-form {
  max-width: 420px;
}

.field-hint {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
  margin-top: 4px;
}

.form-error {
  margin-bottom: 12px;
}
</style>
