<script setup lang="ts">
/**
 * NextProjectsView — /app/projects v2 admin page (U2 org batch).
 * Behaviour parity with the legacy projects page: create project (code,
 * name, routing tag), member drawer with confirmed removal.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiDrawer, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type {MemberView} from '@/types/api';
import type { Project } from '@/types/generated-api';

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

const memberOpen = ref(false);
const memberProject = ref<Project | null>(null);
const memberUsers = ref<MemberView[]>([]);
const memberLoading = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const columns = [
  { key: 'code', title: '代码', width: '110px' },
  { key: 'name', title: '名称', minWidth: '180px' },
  { key: 'projectTag', title: '路由标签', width: '150px' },
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
    toast.success('项目已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function openMembers(project: Project) {
  memberProject.value = project;
  memberOpen.value = true;
  memberLoading.value = true;
  try {
    memberUsers.value = await api.listProjectMembers(project.id);
  } catch {
    memberUsers.value = [];
  } finally {
    memberLoading.value = false;
  }
}

function requestRemove(user: MemberView) {
  if (!memberProject.value) return;
  const project = memberProject.value;
  confirmState.value = {
    title: '移除成员',
    body: `将「${user.username}」移出项目「${project.name}」。`,
    confirmLabel: '移除',
    tone: 'danger',
    run: async () => {
      try {
        await api.removeProjectMember(project.id, user.userId);
        toast.success('成员已移除');
        memberUsers.value = await api.listProjectMembers(project.id);
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
  <div class="ui-page next-projects">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">项目</h1>
        <p class="ui-page-desc">用量归属与路由标签的载体。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="project-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建项目' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-projects__create"
      data-testid="project-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建项目</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-projects__form">
          <UiInput
            v-model="createCode"
            label="项目代码"
            required
            placeholder="例如 CORE"
            data-testid="project-create-code"
          />
          <UiInput v-model="createName" label="名称" required data-testid="project-create-name" />
          <UiInput
            v-model="createTag"
            label="路由标签"
            placeholder="例如 core-ai（Virtual Key 点号后缀）"
            data-testid="project-create-tag"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-projects__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="project-create-submit"
              @click="createProject"
            >
              创建项目
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ projects.length }} 个项目</span>
      </div>
      <UiTable
        :columns="columns"
        :data="projects"
        :loading="loading"
        row-key="id"
        empty-title="还没有项目"
        data-testid="projects-table"
      >
        <template #projectTag="{ row }">
          <span v-if="(row as Project).projectTag" class="ui-mono">{{
            (row as Project).projectTag
          }}</span>
          <span v-else>—</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as Project).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as Project).status === 'ACTIVE' ? '正常' : '停用'"
          />
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="project-members-open"
            @click="openMembers(row as Project)"
          >
            成员
          </UiButton>
        </template>
      </UiTable>
    </section>

    <UiDrawer
      :open="memberOpen"
      :title="`项目成员：${memberProject?.name ?? ''}`"
      data-testid="project-members-drawer"
      @close="memberOpen = false"
    >
      <UiTable
        :columns="memberColumns"
        :data="memberUsers"
        :loading="memberLoading"
        row-key="userId"
        empty-title="还没有成员"
        data-testid="project-members-table"
      >
        <template #username="{ row }">
          <div class="next-projects__member-name">{{ (row as MemberView).username }}</div>
          <div v-if="(row as MemberView).displayName" class="next-projects__member-sub">
            {{ (row as MemberView).displayName }}
          </div>
        </template>
        <template #joinedAt="{ row }">{{ formatDate((row as MemberView).createdAt) }}</template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
            size="sm"
            data-testid="project-member-remove"
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

.next-projects__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-projects__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.next-projects__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-projects__member-name {
  font-weight: var(--ui-weight-medium);
}

.next-projects__member-sub {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}
</style>
