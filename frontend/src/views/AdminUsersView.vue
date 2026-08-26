<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AdminUser, UserRole } from '@/types/api';

const users = ref<AdminUser[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const createUsername = ref('');
const createDisplayName = ref('');
const createRole = ref<UserRole>('USER');
const formError = ref('');
const formRequestId = ref('');
const submitting = ref(false);

// One-time temporary password reveal (create or reset).
const reveal = ref(false);
const revealUser = ref('');
const revealPassword = ref('');

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    users.value = await api.listUsers();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createUser() {
  if (!createUsername.value.trim()) {
    formError.value = '请输入用户名。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    const response = await api.createUser({
      username: createUsername.value.trim(),
      displayName: createDisplayName.value.trim() || undefined,
      role: createRole.value,
    });
    revealUser.value = response.user.username;
    revealPassword.value = response.temporaryPassword;
    reveal.value = true;
    creating.value = false;
    createUsername.value = '';
    createDisplayName.value = '';
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      formError.value = error.message;
      formRequestId.value = error.requestId ?? '';
    } else {
      formError.value = '创建失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}

async function toggleStatus(user: AdminUser) {
  const disabling = user.status === 'ACTIVE';
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用后「${user.username}」立即无法登录，现有会话全部失效。`
        : `重新启用「${user.username}」的登录。`,
      disabling ? '禁用用户' : '启用用户',
      { confirmButtonText: disabling ? '禁用' : '启用', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await api.updateUserStatus(user.id, disabling ? 'DISABLED' : 'ACTIVE');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function resetPassword(user: AdminUser) {
  try {
    await ElMessageBox.confirm(
      `将重置「${user.username}」的密码并撤销其全部会话，新密码仅显示一次。`,
      '重置密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    const response = await api.resetUserPassword(user.id);
    revealUser.value = response.user.username;
    revealPassword.value = response.temporaryPassword;
    reveal.value = true;
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function handleCommand(command: string, user: AdminUser) {
  if (command === 'status') {
    await toggleStatus(user);
  } else if (command === 'reset') {
    await resetPassword(user);
  } else if (command === 'revoke') {
    await revokeSessions(user);
  }
}

async function revokeSessions(user: AdminUser) {
  try {
    await ElMessageBox.confirm(`撤销「${user.username}」的全部登录会话。`, '撤销会话', {
      confirmButtonText: '撤销',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.revokeUserSessions(user.id);
    ElMessage.success('会话已撤销');
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

function statusClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'mk-status--success';
    case 'DISABLED':
      return 'mk-status--danger';
    default:
      return 'mk-status--warning';
  }
}

const statusLabel: Record<string, string> = {
  ACTIVE: 'Active',
  DISABLED: 'Disabled',
  LOCKED: 'Locked',
};

onMounted(load);
</script>

<template>
  <div class="users-page">
    <PageHeader title="Users" description="管理门户账号与登录权限。">
      <template #actions>
        <el-button type="primary" data-testid="user-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建用户' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      class="block-alert"
      data-testid="users-load-error"
    >
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="user-create-form">
      <h3 class="panel-title">创建用户</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="用户名" required>
          <el-input
            v-model="createUsername"
            placeholder="例如 alice"
            data-testid="user-create-username"
          />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input
            v-model="createDisplayName"
            placeholder="例如 Alice"
            data-testid="user-create-display"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="createRole" data-testid="user-create-role">
            <el-option label="USER" value="USER" />
            <el-option label="SYSTEM_ADMIN" value="SYSTEM_ADMIN" />
          </el-select>
        </el-form-item>
        <p v-if="formError" class="form-error mk-num">
          {{ formError
          }}<span v-if="formRequestId" class="mk-mono"> requestId: {{ formRequestId }}</span>
        </p>
        <div class="form-actions">
          <el-button
            type="primary"
            :loading="submitting"
            data-testid="user-create-submit"
            @click="createUser"
          >
            创建用户
          </el-button>
          <el-button @click="creating = false">取消</el-button>
        </div>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="users" class="users-table" data-testid="users-table">
      <el-table-column label="用户名" min-width="160">
        <template #default="{ row }">
          <div class="user-name">{{ row.username }}</div>
          <div class="user-display">{{ row.displayName }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="role" label="角色" width="140" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">{{
            statusLabel[row.status] ?? row.status
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">
          {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
            <el-button link data-testid="user-actions">操作</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="status" data-testid="user-toggle-status">
                  {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
                </el-dropdown-item>
                <el-dropdown-item command="reset" data-testid="user-reset-password"
                  >重置密码</el-dropdown-item
                >
                <el-dropdown-item command="revoke" data-testid="user-revoke-sessions"
                  >撤销会话</el-dropdown-item
                >
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="reveal"
      title="一次性临时密码"
      width="480px"
      :close-on-click-modal="false"
      data-testid="temp-password-dialog"
    >
      <p>
        用户
        <strong>{{ revealUser }}</strong>
        的临时密码如下，<strong>仅显示这一次</strong>，请立即交付本人并提醒其首次登录后修改。
      </p>
      <div class="temp-password-box mk-mono" data-testid="temp-password">{{ revealPassword }}</div>
      <template #footer>
        <el-button type="primary" @click="reveal = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.form-actions {
  display: flex;
  gap: 8px;
}

.user-name {
  font-weight: 500;
}

.user-display {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}

.temp-password-box {
  padding: 12px 16px;
  margin-top: 12px;
  background: var(--miqrokey-bg-subtle);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-control);
  font-size: 14px;
  word-break: break-all;
  user-select: all;
}
</style>
