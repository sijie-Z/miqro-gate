<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { DialogPlugin, MessagePlugin } from 'tdesign-vue-next';
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

const columns = [
  { colKey: 'username', title: '用户名', minWidth: 160 },
  { colKey: 'role', title: '角色', width: 140 },
  { colKey: 'status', title: '状态', width: 120 },
  { colKey: 'lastLoginAt', title: '最近登录', width: 170 },
  { colKey: 'actions', title: '操作', width: 90, fixed: 'right' as const },
];

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
    await DialogPlugin.confirm({
      header: disabling ? '禁用用户' : '启用用户',
      body: disabling
        ? `禁用后「${user.username}」立即无法登录，现有会话全部失效。`
        : `重新启用「${user.username}」的登录。`,
      confirmBtn: disabling ? '禁用' : '启用',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.updateUserStatus(user.id, disabling ? 'DISABLED' : 'ACTIVE');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function resetPassword(user: AdminUser) {
  try {
    await DialogPlugin.confirm({
      header: '重置密码',
      body: `将重置「${user.username}」的密码并撤销其全部会话，新密码仅显示一次。`,
      confirmBtn: '重置',
      cancelBtn: '取消',
      theme: 'warning',
    });
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
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
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
    await DialogPlugin.confirm({
      header: '撤销会话',
      body: `撤销「${user.username}」的全部登录会话。`,
      confirmBtn: '撤销',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.revokeUserSessions(user.id);
    MessagePlugin.success('会话已撤销');
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
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
        <t-button theme="primary" data-testid="user-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建用户' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert
      v-if="loadError"
      theme="error"
      :close-btn="false"
      class="block-alert"
      data-testid="users-load-error"
    >
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="user-create-form">
      <h3 class="panel-title">创建用户</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="用户名" required>
          <t-input
            v-model="createUsername"
            placeholder="例如 alice"
            data-testid="user-create-username"
          />
        </t-form-item>
        <t-form-item label="显示名">
          <t-input
            v-model="createDisplayName"
            placeholder="例如 Alice"
            data-testid="user-create-display"
          />
        </t-form-item>
        <t-form-item label="角色">
          <t-select v-model="createRole" data-testid="user-create-role">
            <t-option label="USER" value="USER" />
            <t-option label="SYSTEM_ADMIN" value="SYSTEM_ADMIN" />
          </t-select>
        </t-form-item>
        <p v-if="formError" class="form-error mk-num">
          {{ formError
          }}<span v-if="formRequestId" class="mk-mono"> requestId: {{ formRequestId }}</span>
        </p>
        <div class="form-actions">
          <t-button
            theme="primary"
            :loading="submitting"
            data-testid="user-create-submit"
            @click="createUser"
          >
            创建用户
          </t-button>
          <t-button @click="creating = false">取消</t-button>
        </div>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :data="users"
        :columns="columns"
        class="users-table"
        data-testid="users-table"
      >
        <template #username="{ row }">
          <div class="user-name">{{ row.username }}</div>
          <div class="user-display">{{ row.displayName }}</div>
        </template>
        <template #status="{ row }">
          <span class="mk-status" :class="statusClass(row.status)">{{
            statusLabel[row.status] ?? row.status
          }}</span>
        </template>
        <template #lastLoginAt="{ row }">
          {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : '—' }}
        </template>
        <template #actions="{ row }">
          <t-dropdown trigger="click">
            <t-button variant="text" data-testid="user-actions">操作</t-button>
            <template #dropdown>
              <t-dropdown-menu>
                <t-dropdown-item
                  data-testid="user-toggle-status"
                  @click="handleCommand('status', row)"
                >
                  {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
                </t-dropdown-item>
                <t-dropdown-item
                  data-testid="user-reset-password"
                  @click="handleCommand('reset', row)"
                >
                  重置密码
                </t-dropdown-item>
                <t-dropdown-item
                  data-testid="user-revoke-sessions"
                  @click="handleCommand('revoke', row)"
                >
                  撤销会话
                </t-dropdown-item>
              </t-dropdown-menu>
            </template>
          </t-dropdown>
        </template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="reveal"
      header="一次性临时密码"
      width="480px"
      :close-on-overlay-click="false"
      data-testid="temp-password-dialog"
    >
      <p>
        用户
        <strong>{{ revealUser }}</strong>
        的临时密码如下，<strong>仅显示这一次</strong>，请立即交付本人并提醒其首次登录后修改。
      </p>
      <div class="temp-password-box mk-mono" data-testid="temp-password">{{ revealPassword }}</div>
      <template #footer>
        <t-button theme="primary" @click="reveal = false">我已保存</t-button>
      </template>
    </t-dialog>
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
