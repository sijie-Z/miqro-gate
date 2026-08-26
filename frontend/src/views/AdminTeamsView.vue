<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
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
    await ElMessageBox.confirm(
      `将「${user.username}」移出团队「${memberTeam.value.name}」。`,
      '移除成员',
      {
        confirmButtonText: '移除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
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
        <el-button type="primary" data-testid="team-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建团队' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="team-create-form">
      <h3 class="panel-title">创建团队</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="名称" required>
          <el-input v-model="createName" data-testid="team-create-name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createDescription" type="textarea" :rows="2" />
        </el-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="team-create-submit"
          @click="createTeam"
        >
          创建团队
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="teams" data-testid="teams-table">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="description" label="描述" min-width="240">
        <template #default="{ row }">{{ row.description ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--danger'"
          >
            {{ row.status }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-testid="team-members-open" @click="openMembers(row)"
            >成员</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="memberDrawer" :title="`团队成员：${memberTeam?.name ?? ''}`" size="420px">
      <el-table v-loading="memberLoading" :data="memberUsers" data-testid="team-members-table">
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column label="" width="80">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              data-testid="team-member-remove"
              @click="removeMember(row)"
              >移除</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
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
