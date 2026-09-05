<script setup lang="ts">
/**
 * NextUsersView — /app-new/users pilot page (UI U0, PostHog language).
 * Behaviour parity with legacy AdminUsersView (admin table, create form,
 * one-time temporary password reveal, disable/reset/revoke gates).
 */
import { computed, onMounted, ref } from 'vue';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuItemIndicator,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from 'radix-vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type {AdminUser, UserProjectMembership, UserRole} from '@/types/api';
import type { Project } from '@/types/generated-api';

const users = ref<AdminUser[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const createUsername = ref('');
const createDisplayName = ref('');
const createRole = ref<string>('USER');
const formError = ref('');
const formRequestId = ref('');
const submitting = ref(false);

// One-time temporary password reveal (create or reset).
const revealOpen = ref(false);
const revealUser = ref('');
const revealPassword = ref('');
const revealAcked = ref(false);
const revealCopied = ref(false);

// Confirm gate (replaces TDesign confirmDialog on this page).
const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const roleLabel: Record<string, string> = {
  SYSTEM_ADMIN: '系统管理员',
  USER: '用户',
};

const roleOptions = [
  { value: 'USER', label: '用户（USER）' },
  { value: 'SYSTEM_ADMIN', label: '系统管理员（SYSTEM_ADMIN）' },
];

const columns = [
  { key: 'username', title: '用户', minWidth: '200px' },
  { key: 'role', title: '角色', width: '140px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'lastLoginAt', title: '最近登录', width: '150px' },
  { key: 'createdAt', title: '创建时间', width: '180px' },
  { key: 'actions', title: '操作', width: '80px', align: 'center' as const },
];

const activeCount = computed(() => users.value.filter((u) => u.status === 'ACTIVE').length);

onMounted(load);

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

// Project membership drawer (admin quick-join, F-REG loop).
const membershipUser = ref<AdminUser | null>(null);
const membershipDrawer = ref(false);
const memberships = ref<UserProjectMembership[]>([]);
const projects = ref<Project[]>([]);
const projectsLoaded = ref(false);
const membershipLoading = ref(false);
const membershipError = ref('');
const pickProjectId = ref('');
const membershipSaving = ref(false);

const joinableProjects = computed(() => {
  const memberIds = new Set(memberships.value.map((m) => m.projectId));
  return projects.value.filter((p) => p.status === 'ACTIVE' && !memberIds.has(p.id));
});

function openProjectMembership(user: AdminUser) {
  membershipUser.value = user;
  memberships.value = [];
  membershipError.value = '';
  pickProjectId.value = '';
  membershipDrawer.value = true;
  void refreshMemberships();
  if (!projectsLoaded.value) {
    api
      .listProjects()
      .then((list) => {
        projects.value = list;
        projectsLoaded.value = true;
      })
      .catch(() => {
        projects.value = [];
      });
  }
}

async function refreshMemberships() {
  if (!membershipUser.value) return;
  membershipLoading.value = true;
  membershipError.value = '';
  try {
    memberships.value = await api.adminUserProjectMemberships(membershipUser.value.id);
  } catch (error) {
    membershipError.value = error instanceof ApiError ? error.message : '加载项目成员关系失败。';
  } finally {
    membershipLoading.value = false;
  }
}

async function addMembership() {
  if (!membershipUser.value || !pickProjectId.value) return;
  membershipSaving.value = true;
  membershipError.value = '';
  try {
    await api.addProjectMember(pickProjectId.value, membershipUser.value.id);
    pickProjectId.value = '';
    toast.success('已加入项目');
    await refreshMemberships();
  } catch (error) {
    membershipError.value = error instanceof ApiError ? error.message : '加入项目失败。';
  } finally {
    membershipSaving.value = false;
  }
}

async function removeMembership(membership: UserProjectMembership) {
  if (!membershipUser.value) return;
  membershipError.value = '';
  try {
    await api.removeProjectMember(membership.projectId, membershipUser.value.id);
    toast.success('已从「' + membership.projectName + '」移除');
    await refreshMemberships();
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
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
      role: createRole.value as UserRole,
    });
    creating.value = false;
    createUsername.value = '';
    createDisplayName.value = '';
    await load();
    openReveal(response.user.username, response.temporaryPassword);
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

function openReveal(username: string, password: string) {
  revealUser.value = username;
  revealPassword.value = password;
  revealAcked.value = false;
  revealCopied.value = false;
  revealOpen.value = true;
}

async function copyPassword() {
  try {
    await navigator.clipboard.writeText(revealPassword.value);
    revealCopied.value = true;
  } catch {
    toast.error('复制失败，请手动选择复制');
  }
}

function toggleStatus(user: AdminUser) {
  const disabling = user.status === 'ACTIVE';
  confirmState.value = {
    title: disabling ? '禁用用户' : '启用用户',
    body: disabling
      ? `禁用后「${user.username}」立即无法登录，现有会话全部失效。`
      : `重新启用「${user.username}」的登录。`,
    confirmLabel: disabling ? '禁用' : '启用',
    tone: disabling ? 'danger' : 'primary',
    run: async () => {
      try {
        await api.updateUserStatus(user.id, disabling ? 'DISABLED' : 'ACTIVE');
        toast.success(disabling ? '用户已禁用' : '用户已启用');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

function resetPassword(user: AdminUser) {
  confirmState.value = {
    title: '重置密码',
    body: `将重置「${user.username}」的密码并撤销其全部会话，新密码仅显示一次。`,
    confirmLabel: '重置',
    tone: 'danger',
    run: async () => {
      try {
        const response = await api.resetUserPassword(user.id);
        await load();
        openReveal(response.user.username, response.temporaryPassword);
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

function revokeSessions(user: AdminUser) {
  confirmState.value = {
    title: '撤销会话',
    body: `撤销「${user.username}」的全部登录会话。`,
    confirmLabel: '撤销',
    tone: 'primary',
    run: async () => {
      try {
        await api.revokeUserSessions(user.id);
        toast.success('会话已撤销');
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

async function confirmAndRun() {
  const state = confirmState.value;
  if (!state) return;
  confirmState.value = null;
  await state.run();
}

function statusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '正常';
    case 'DISABLED':
      return '停用';
    case 'LOCKED':
      return '锁定';
    default:
      return status;
  }
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'LOCKED':
      return 'warning';
    default:
      return 'neutral';
  }
}

function formatDate(iso?: string): string {
  if (!iso) return '从未登录';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
</script>

<template>
  <div class="ui-page next-users">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">用户</h1>
        <p class="ui-page-desc">管理门户账号与登录权限。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="user-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建用户' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="users-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <!-- Create form -->
    <section v-if="creating" class="ui-panel next-users__create" data-testid="user-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建用户</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-users__grid">
          <UiInput
            v-model="createUsername"
            label="用户名"
            required
            placeholder="例如 alice"
            data-testid="user-create-username"
          />
          <UiInput
            v-model="createDisplayName"
            label="显示名"
            placeholder="例如 Alice"
            data-testid="user-create-display"
          />
          <UiSelect
            v-model="createRole"
            label="角色"
            :options="roleOptions"
            data-testid="user-create-role"
          />
          <p v-if="formError" class="ui-form-error" data-testid="user-create-error">
            {{ formError
            }}<span v-if="formRequestId" class="ui-request-id">
              requestId: {{ formRequestId }}</span
            >
          </p>
          <div class="next-users__form-actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="user-create-submit"
              @click="createUser"
            >
              创建用户
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <!-- List -->
    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub" data-testid="users-summary"
          >共 {{ users.length }} 个账号，{{ activeCount }} 个正常</span
        >
      </div>
      <UiTable
        :columns="columns"
        :data="users"
        :loading="loading"
        row-key="id"
        empty-title="还没有用户"
        data-testid="users-table"
      >
        <template #username="{ row }">
          <div class="next-users__name">{{ (row as AdminUser).username }}</div>
          <div
            v-if="
              (row as AdminUser).displayName &&
              (row as AdminUser).displayName !== (row as AdminUser).username
            "
            class="next-users__display"
          >
            {{ (row as AdminUser).displayName }}
          </div>
        </template>
        <template #role="{ row }">{{
          roleLabel[(row as AdminUser).role] ?? (row as AdminUser).role
        }}</template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="statusTone((row as AdminUser).status)"
            :label="statusLabel((row as AdminUser).status)"
          />
        </template>
        <template #lastLoginAt="{ row }">{{ formatDate((row as AdminUser).lastLoginAt) }}</template>
        <template #createdAt="{ row }">{{ formatDate((row as AdminUser).createdAt) }}</template>
        <template #actions="{ row }">
          <DropdownMenuRoot>
            <DropdownMenuTrigger
              class="next-users__kebab"
              aria-label="操作"
              :data-testid="`user-actions-${(row as AdminUser).id}`"
            >
              <svg
                width="16"
                height="16"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <circle cx="3" cy="8" r="1.4" />
                <circle cx="8" cy="8" r="1.4" />
                <circle cx="13" cy="8" r="1.4" />
              </svg>
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent class="next-users__menu" :side-offset="4" :align="'end'">
                <DropdownMenuItem
                  class="next-users__menu-item"
                  @select="openProjectMembership(row as AdminUser)"
                >
                  <DropdownMenuItemIndicator class="next-users__menu-ind" />
                  <span data-testid="user-project-members">项目成员</span>
                </DropdownMenuItem>
                <DropdownMenuSeparator class="next-users__menu-sep" />
                <DropdownMenuItem
                  class="next-users__menu-item"
                  @select="toggleStatus(row as AdminUser)"
                >
                  <DropdownMenuItemIndicator class="next-users__menu-ind" />
                  <span data-testid="user-toggle-status">{{
                    (row as AdminUser).status === 'ACTIVE' ? '禁用' : '启用'
                  }}</span>
                </DropdownMenuItem>
                <DropdownMenuSeparator class="next-users__menu-sep" />
                <DropdownMenuItem
                  class="next-users__menu-item"
                  @select="resetPassword(row as AdminUser)"
                >
                  <DropdownMenuItemIndicator class="next-users__menu-ind" />
                  <span data-testid="user-reset-password">重置密码</span>
                </DropdownMenuItem>
                <DropdownMenuItem
                  class="next-users__menu-item"
                  @select="revokeSessions(row as AdminUser)"
                >
                  <DropdownMenuItemIndicator class="next-users__menu-ind" />
                  <span data-testid="user-revoke-sessions">撤销会话</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </template>
      </UiTable>
    </section>

    <!-- Project membership quick-join (admin) -->
    <UiDrawer
      :open="membershipDrawer"
      :title="membershipUser ? `项目成员 · ${membershipUser.username}` : '项目成员'"
      width="560px"
      data-testid="user-membership-drawer"
      @update:open="membershipDrawer = false"
    >
      <div v-if="membershipError" class="ui-alert ui-alert--error">{{ membershipError }}</div>
      <h3 class="next-users__drawer-title">当前项目</h3>
      <div v-if="membershipLoading" class="next-users__skeleton">
        <div v-for="n in 2" :key="n" class="ui-skeleton next-users__skeleton-row">&nbsp;</div>
      </div>
      <div
        v-else-if="memberships.length"
        class="next-users__member-list"
        data-testid="user-memberships"
      >
        <div v-for="m in memberships" :key="m.projectId" class="next-users__member-row">
          <div class="next-users__member-info">
            <span class="ui-mono next-users__member-code">{{ m.projectCode }}</span>
            <span class="next-users__member-name">{{ m.projectName }}</span>
            <span class="next-users__member-since">{{ formatDate(m.joinedAt) }} 加入</span>
          </div>
          <UiButton
            variant="ghost"
            size="sm"
            class="next-users__danger"
            data-testid="user-project-remove"
            @click="removeMembership(m)"
            >移除</UiButton
          >
        </div>
      </div>
      <p v-else class="next-users__member-empty" data-testid="user-memberships-empty">
        该用户还没有加入任何项目——创建 Virtual Key 前需要先加入项目。
      </p>

      <h3 class="next-users__drawer-title">加入项目</h3>
      <div class="next-users__join-row">
        <UiSelect
          v-model="pickProjectId"
          :options="joinableProjects.map((p) => ({ value: p.id, label: p.code + ' · ' + p.name }))"
          placeholder="选择项目"
          data-testid="user-project-pick"
        />
        <UiButton
          variant="primary"
          :disabled="!pickProjectId"
          :loading="membershipSaving"
          data-testid="user-project-add"
          @click="addMembership"
          >加入</UiButton
        >
      </div>
      <p v-if="!joinableProjects.length" class="next-users__member-hint">
        没有更多可加入的 ACTIVE 项目。
      </p>
    </UiDrawer>

    <!-- One-time temporary password -->
    <UiDialog
      :open="revealOpen"
      title="一次性临时密码"
      description="仅显示这一次，请立即交付本人并提醒其首次登录后修改。"
      width="520px"
      :dismissible="false"
      data-testid="temp-password-dialog"
      @update:open="revealAcked && (revealOpen = $event)"
    >
      <p class="next-users__reveal-for">
        用户 <strong>{{ revealUser }}</strong>
      </p>
      <div class="next-users__password-box" data-testid="temp-password">
        <code>{{ revealPassword }}</code>
      </div>
      <label class="next-users__ack">
        <input
          v-model="revealAcked"
          type="checkbox"
          class="next-users__ack-input"
          data-testid="temp-password-ack"
        />
        <span class="next-users__ack-box" aria-hidden="true">
          <svg width="11" height="11" viewBox="0 0 16 16" fill="none">
            <path
              d="M3.5 8.5 6.5 11.5 12.5 4.5"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <span>我已保存</span>
      </label>
      <template #footer>
        <UiButton variant="secondary" data-testid="temp-password-copy" @click="copyPassword">
          {{ revealCopied ? '已复制' : '复制' }}
        </UiButton>
        <UiButton
          variant="primary"
          :disabled="!revealAcked"
          data-testid="temp-password-close"
          @click="revealOpen = false"
        >
          完成
        </UiButton>
      </template>
    </UiDialog>

    <!-- Confirm gate -->
    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="460px"
      @update:open="confirmState = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="confirmState = null">取消</UiButton>
        <UiButton
          :variant="confirmState.tone === 'danger' ? 'danger' : 'primary'"
          @click="confirmAndRun"
        >
          {{ confirmState.confirmLabel }}
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-users__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-users__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 640px;
}

.next-users__form-actions {
  display: flex;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-2);
  grid-column: 1 / -1;
}

.next-users__head-inline {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-3);
}

.next-users__name {
  font-weight: var(--ui-weight-semibold);
  line-height: var(--ui-line-height-lg);
}

.next-users__display {
  font-size: 11px;
  line-height: var(--ui-line-height-sm);
  color: var(--ui-foreground-faint);
  margin-top: 2px;
}

.next-users__kebab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-users__kebab:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.next-users__kebab:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.next-users__menu {
  min-width: 160px;
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  box-shadow: var(--ui-shadow-popper);
  padding: var(--ui-space-1);
  z-index: 2000;
}

.next-users__menu-item {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-3);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
  outline: none;
}

.next-users__menu-item[data-highlighted] {
  background: var(--ui-fill-hover);
}

.next-users__menu-item[data-disabled] {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.next-users__menu-ind {
  display: none;
}

.next-users__menu-sep {
  height: 1px;
  margin: var(--ui-space-1) 0;
  background: var(--ui-border-muted);
}

.next-users__reveal-for {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-users__password-box {
  padding: var(--ui-space-3) var(--ui-space-4);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  font-family: var(--ui-font-mono);
  font-size: var(--ui-font-size-base);
  line-height: var(--ui-line-height-lg);
  word-break: break-all;
  user-select: all;
}

.next-users__ack {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-users__ack-input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

.next-users__ack-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--ui-input-border);
  border-radius: 4px;
  background: var(--ui-card);
  color: transparent;
  flex-shrink: 0;

  pointer-events: none;
}

.next-users__ack-input:checked + .next-users__ack-box {
  background: var(--ui-primary);
  border-color: var(--ui-primary);
  color: #fff;
}

.next-users__ack:has(.next-users__ack-input:focus-visible) .next-users__ack-box {
  box-shadow: var(--ui-shadow-focus);
}

.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-users__drawer-title {
  margin: 0 0 var(--ui-space-2);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-users__drawer-title + .next-users__drawer-title,
.next-users__member-empty + .next-users__drawer-title,
.next-users__member-hint + .next-users__drawer-title {
  margin-top: var(--ui-space-5);
}

.next-users__skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-4);
}

.next-users__skeleton-row {
  height: 48px;
}

.next-users__member-list {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-5);
}

.next-users__member-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-users__member-info {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
  flex: 1;
  min-width: 0;
}

.next-users__member-code {
  font-size: var(--ui-font-size-xs);
  padding: 0 var(--ui-space-2);
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: var(--ui-muted);
  color: var(--ui-foreground-secondary);
}

.next-users__member-name {
  font-weight: var(--ui-weight-medium);
}

.next-users__member-since {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-users__danger {
  color: var(--ui-danger-fg);
}

.next-users__member-empty {
  margin: 0 0 var(--ui-space-5);
  padding: var(--ui-space-6) 0;
  text-align: center;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-users__join-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-users__join-row :deep(.ui-select) {
  flex: 1;
}

.next-users__member-hint {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}
</style>
