<script setup lang="ts">
/**
 * NextTeamsView — /app/teams v2 admin page (U2 org batch, PostHog language).
 * Behaviour parity with the legacy teams page: create team, member drawer
 * with confirmed removal. Rendering on the v2 system; APIs untouched.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiDrawer, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { MemberView, Team } from '@/types/api';

const teams = ref<Team[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const createName = ref('');
const createDescription = ref('');
const formError = ref('');
const submitting = ref(false);

// Member drawer
const memberOpen = ref(false);
const memberTeam = ref<Team | null>(null);
const memberUsers = ref<MemberView[]>([]);
const memberLoading = ref(false);

// Confirm gate for member removal
const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const columns = [
  { key: 'name', title: '名称', minWidth: '180px' },
  { key: 'description', title: '描述', minWidth: '260px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'actions', title: '操作', width: '100px', align: 'center' as const },
];

const memberColumns = [
  { key: 'username', title: '成员', minWidth: '200px' },
  { key: 'joinedAt', title: '加入时间', width: '170px' },
  { key: 'actions', title: '', width: '80px', align: 'center' as const },
];

async function load() {
  loading.value = true;
  try {
    teams.value = await api.listTeams();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createTeam() {
  if (!createName.value.trim()) {
    formError.value = '请输入团队名称。';
    return;
  }
  submitting.value = true;
  try {
    await api.createTeam({
      name: createName.value.trim(),
      description: createDescription.value.trim() || undefined,
    });
    creating.value = false;
    createName.value = '';
    createDescription.value = '';
    toast.success('团队已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function openMembers(team: Team) {
  memberTeam.value = team;
  memberOpen.value = true;
  memberLoading.value = true;
  try {
    memberUsers.value = await api.listTeamMembers(team.id);
  } catch {
    memberUsers.value = [];
  } finally {
    memberLoading.value = false;
  }
}

function requestRemove(user: MemberView) {
  if (!memberTeam.value) return;
  const team = memberTeam.value;
  confirmState.value = {
    title: '移除成员',
    body: `将「${user.username}」移出团队「${team.name}」。`,
    confirmLabel: '移除',
    tone: 'danger',
    run: async () => {
      try {
        await api.removeTeamMember(team.id, user.userId);
        toast.success('成员已移除');
        memberUsers.value = await api.listTeamMembers(team.id);
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

function formatDate(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-teams">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">团队</h1>
        <p class="ui-page-desc">组织团队与成员归属。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="team-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建团队' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section v-if="creating" class="ui-panel next-teams__create" data-testid="team-create-form">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建团队</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-teams__form">
          <UiInput
            v-model="createName"
            label="名称"
            required
            placeholder="例如 platform-sre"
            data-testid="team-create-name"
          />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="createDescription"
              class="ui-textarea"
              rows="3"
              placeholder="团队职责（可选）"
              data-testid="team-create-description"
            />
          </div>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-teams__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="team-create-submit"
              @click="createTeam"
            >
              创建团队
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">团队列表</h2>
      </div>
      <UiTable
        :columns="columns"
        :data="teams"
        :loading="loading"
        row-key="id"
        empty-title="还没有团队"
        data-testid="teams-table"
      >
        <template #description="{ row }">{{ (row as Team).description ?? '—' }}</template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as Team).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as Team).status === 'ACTIVE' ? '正常' : '停用'"
          />
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="team-members-open"
            @click="openMembers(row as Team)"
          >
            成员
          </UiButton>
        </template>
      </UiTable>
    </section>

    <!-- Member drawer -->
    <UiDrawer
      :open="memberOpen"
      :title="`团队成员：${memberTeam?.name ?? ''}`"
      data-testid="team-members-drawer"
      @close="memberOpen = false"
    >
      <UiTable
        :columns="memberColumns"
        :data="memberUsers"
        :loading="memberLoading"
        row-key="userId"
        empty-title="还没有成员"
        data-testid="team-members-table"
      >
        <template #username="{ row }">
          <div class="next-teams__member-name">{{ (row as MemberView).username }}</div>
          <div v-if="(row as MemberView).displayName" class="next-teams__member-sub">
            {{ (row as MemberView).displayName }}
          </div>
        </template>
        <template #joinedAt="{ row }">{{ formatDate((row as MemberView).createdAt) }}</template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="team-member-remove"
            @click="requestRemove(row as MemberView)"
          >
            移除
          </UiButton>
        </template>
      </UiTable>
    </UiDrawer>

    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="440px"
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
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-teams__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-teams__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-textarea {
  width: 100%;
  min-height: 80px;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-teams__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-teams__member-name {
  font-weight: var(--ui-weight-medium);
}

.next-teams__member-sub {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}
</style>
