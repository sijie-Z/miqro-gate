<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { confirmDialog } from '@/utils/confirm';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { MemberView, Project } from '@/types/api';

const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const createCode = ref('');
const createName = ref('');
const createTag = ref('');
const formError = ref('');
const submitting = ref(false);

const memberDrawer = ref(false);
const memberProject = ref<Project | null>(null);
const memberUsers = ref<MemberView[]>([]);
const memberLoading = ref(false);

const columns = [
  { colKey: 'code', title: '代码', width: 120 },
  { colKey: 'name', title: '名称', minWidth: 180 },
  { colKey: 'projectTag', title: '路由标签', width: 140 },
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
    projects.value = await api.listProjects();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createProject() {
  if (!createCode.value.trim() || !createName.value.trim()) {
    formError.value = '项目代码与名称必填。';
    return;
  }
  submitting.value = true;
  try {
    await api.createProject({
      code: createCode.value.trim(),
      name: createName.value.trim(),
      projectTag: createTag.value.trim() || undefined,
    });
    creating.value = false;
    createCode.value = '';
    createName.value = '';
    createTag.value = '';
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function openMembers(project: Project) {
  memberProject.value = project;
  memberDrawer.value = true;
  memberLoading.value = true;
  try {
    memberUsers.value = await api.listProjectMembers(project.id);
  } finally {
    memberLoading.value = false;
  }
}

async function removeMember(user: MemberView) {
  if (!memberProject.value) return;
  try {
    await confirmDialog({
      header: '移除成员',
      body: `将「${user.username}」移出项目「${memberProject.value.name}」。`,
      confirmBtn: '移除',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  await api.removeProjectMember(memberProject.value.id, user.userId);
  memberUsers.value = await api.listProjectMembers(memberProject.value.id);
}

onMounted(load);
</script>

<template>
  <div class="projects-page">
    <PageHeader title="Projects" description="用量归属与路由标签的载体。">
      <template #actions>
        <t-button theme="primary" data-testid="project-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建项目' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="project-create-form">
      <h3 class="panel-title">创建项目</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="项目代码" required>
          <t-input v-model="createCode" placeholder="例如 CORE" data-testid="project-create-code" />
        </t-form-item>
        <t-form-item label="名称" required>
          <t-input v-model="createName" data-testid="project-create-name" />
        </t-form-item>
        <t-form-item label="路由标签">
          <t-input v-model="createTag" placeholder="例如 core-ai（Virtual Key 点号后缀）" />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :loading="submitting"
          data-testid="project-create-submit"
          @click="createProject"
        >
          创建项目
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :data="projects"
        :columns="columns"
        data-testid="projects-table"
      >
        <template #projectTag="{ row }">{{ row.projectTag ?? '—' }}</template>
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
            data-testid="project-members-open"
            @click="openMembers(row)"
            >成员</t-button
          >
        </template>
      </t-table>
    </t-loading>

    <t-drawer
      v-model:visible="memberDrawer"
      :header="`项目成员：${memberProject?.name ?? ''}`"
      :footer="false"
      size="420px"
    >
      <t-loading :loading="memberLoading" size="small" show-overlay>
        <t-table
          row-key="id"
          size="small"
          :data="memberUsers"
          :columns="memberColumns"
          data-testid="project-members-table"
        >
          <template #actions="{ row }">
            <t-button
              variant="text"
              theme="danger"
              data-testid="project-member-remove"
              @click="removeMember(row)"
            >
              移除
            </t-button>
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
