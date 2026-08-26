<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
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
    await ElMessageBox.confirm(
      `将「${user.username}」移出项目「${memberProject.value.name}」。`,
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
  await api.removeProjectMember(memberProject.value.id, user.userId);
  memberUsers.value = await api.listProjectMembers(memberProject.value.id);
}

onMounted(load);
</script>

<template>
  <div class="projects-page">
    <PageHeader title="Projects" description="用量归属与路由标签的载体。">
      <template #actions>
        <el-button type="primary" data-testid="project-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建项目' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="project-create-form">
      <h3 class="panel-title">创建项目</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="项目代码" required>
          <el-input
            v-model="createCode"
            placeholder="例如 CORE"
            data-testid="project-create-code"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createName" data-testid="project-create-name" />
        </el-form-item>
        <el-form-item label="路由标签">
          <el-input v-model="createTag" placeholder="例如 core-ai（Virtual Key 点号后缀）" />
        </el-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="project-create-submit"
          @click="createProject"
        >
          创建项目
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="projects" data-testid="projects-table">
      <el-table-column prop="code" label="代码" width="120" />
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="projectTag" label="路由标签" width="140">
        <template #default="{ row }">{{ row.projectTag ?? '—' }}</template>
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
          <el-button
            link
            type="primary"
            data-testid="project-members-open"
            @click="openMembers(row)"
            >成员</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="memberDrawer"
      :title="`项目成员：${memberProject?.name ?? ''}`"
      size="420px"
    >
      <el-table v-loading="memberLoading" :data="memberUsers" data-testid="project-members-table">
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column label="" width="80">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              data-testid="project-member-remove"
              @click="removeMember(row)"
            >
              移除
            </el-button>
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
