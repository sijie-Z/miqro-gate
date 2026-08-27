<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { confirmDialog } from '@/utils/confirm';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
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

const memberDrawer = ref(false);
const memberTeam = ref<Team | null>(null);
const memberUsers = ref<MemberView[]>([]);
const memberLoading = ref(false);

const columns = [
  { colKey: 'name', title: '名称', minWidth: 180 },
  { colKey: 'description', title: '描述', minWidth: 240 },
  { colKey: 'status', title: '状态', width: 110 },
  { colKey: 'actions', title: '操作', width: 100, fixed: 'right' as const },
];

const memberColumns = [
  { colKey: 'username', title: '用户名', minWidth: 120 },
  { colKey: 'actions', title: '', width: 80 },
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
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function openMembers(team: Team) {
  memberTeam.value = team;
  memberDrawer.value = true;
  await refreshMembers();
}

async function refreshMembers() {
  if (!memberTeam.value) return;
  memberLoading.value = true;
  try {
    memberUsers.value = await api.listTeamMembers(memberTeam.value.id);
  } finally {
    memberLoading.value = false;
  }
}

async function removeMember(user: MemberView) {
  if (!memberTeam.value) return;
  try {
    await confirmDialog({
      header: '移除成员',
      body: `将「${user.username}」移出团队「${memberTeam.value.name}」。`,
      confirmBtn: '移除',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  await api.removeTeamMember(memberTeam.value.id, user.userId);
  await refreshMembers();
}

onMounted(load);
</script>

<template>
  <div class="teams-page">
    <PageHeader title="Teams" description="组织团队与成员归属。">
      <template #actions>
        <t-button theme="primary" data-testid="team-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建团队' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="team-create-form">
      <h3 class="panel-title">创建团队</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required>
          <t-input v-model="createName" data-testid="team-create-name" />
        </t-form-item>
        <t-form-item label="描述">
          <t-textarea v-model="createDescription" :autosize="{ minRows: 2 }" />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :loading="submitting"
          data-testid="team-create-submit"
          @click="createTeam"
        >
          创建团队
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table row-key="id" size="small" :data="teams" :columns="columns" data-testid="teams-table">
        <template #description="{ row }">{{ row.description ?? '—' }}</template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--danger'"
          >
            {{ row.status }}
          </span>
        </template>
        <template #actions="{ row }">
          <t-button
            variant="text"
            theme="primary"
            data-testid="team-members-open"
            @click="openMembers(row)"
            >成员</t-button
          >
        </template>
      </t-table>
    </t-loading>

    <t-drawer
      v-model:visible="memberDrawer"
      :header="`团队成员：${memberTeam?.name ?? ''}`"
      :footer="false"
      size="420px"
    >
      <t-loading :loading="memberLoading" size="small" show-overlay>
        <t-table
          row-key="id"
          size="small"
          :data="memberUsers"
          :columns="memberColumns"
          data-testid="team-members-table"
        >
          <template #actions="{ row }">
            <t-button
              variant="text"
              theme="danger"
              data-testid="team-member-remove"
              @click="removeMember(row)"
              >移除</t-button
            >
          </template>
        </t-table>
      </t-loading>
    </t-drawer>
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
</style>
