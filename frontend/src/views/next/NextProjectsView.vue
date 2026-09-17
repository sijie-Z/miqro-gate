<script setup lang="ts">
/**
 * NextProjectsView — /app/projects v2 admin page (U2 org batch).
 * Behaviour parity with the legacy projects page: create project (code,
 * name, routing tag), member drawer with confirmed removal; #556 adds the
 * member-add picker (mirrors the teams page and users-page quick-join).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiInput,
  UiPageGuide,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type { AdminUser, Grant, MemberView, Project } from '@/types/generated-api';
import { PROJECTS_GUIDE } from '@/content/pageGuides';

const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');
const grants = ref<Grant[]>([]);
// 依赖元数据（#657）：成员数按项目并行取，失败静默（列显示 —），不阻塞列表。
const memberCounts = ref<Record<string, number>>({});

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

// #556: add-member picker (mirrors the teams page and users-page quick-join)
const allUsers = ref<AdminUser[]>([]);
const usersLoaded = ref(false);
const pickUserId = ref('');
const addingMember = ref(false);

const joinableUsers = computed(() => {
  const memberIds = new Set(memberUsers.value.map((m) => m.userId));
  return allUsers.value.filter((u) => u.status === 'ACTIVE' && u.id && !memberIds.has(u.id));
});

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

// #617: project edit dialog — the backend PATCH existed but the UI had no
// entry, so a tagless project could never be fixed after a 409 on key create.
const editOpen = ref(false);
const editProject = ref<Project | null>(null);
const editName = ref('');
const editTag = ref('');
const editError = ref('');
const editRequestId = ref('');
const editSaving = ref(false);

const columns = [
  { key: 'code', title: '代码', width: '110px' },
  { key: 'name', title: '名称', minWidth: '180px' },
  { key: 'projectTag', title: '路由标签', width: '150px' },
  { key: 'grants', title: '授权', width: '80px', align: 'right' as const },
  { key: 'members', title: '成员', width: '80px', align: 'right' as const },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'actions', title: '操作', width: '100px', align: 'center' as const },
];

const memberColumns = [
  { key: 'username', title: '成员', minWidth: '200px' },
  { key: 'joinedAt', title: '加入时间', width: '170px' },
  { key: 'actions', title: '', width: '80px', align: 'center' as const },
];

// 依赖可见性（#657）：授权数来自一次全量 grants；成员数逐项目并行取。
const grantCountByProject = computed(() => {
  const counts = new Map<string, number>();
  for (const g of grants.value) {
    if (g.projectId) {
      counts.set(g.projectId, (counts.get(g.projectId) ?? 0) + 1);
    }
  }
  return counts;
});

function grantCountOf(projectId: string | undefined): number {
  return projectId ? (grantCountByProject.value.get(projectId) ?? 0) : 0;
}

async function loadMemberCounts(list: Project[]) {
  const results = await Promise.allSettled(list.map((p) => api.listProjectMembers(p.id!)));
  const counts: Record<string, number> = {};
  list.forEach((p, index) => {
    const result = results[index];
    if (result && result.status === 'fulfilled' && Array.isArray(result.value)) {
      counts[p.id!] = result.value.length;
    }
  });
  memberCounts.value = counts;
}

async function load() {
  loading.value = true;
  try {
    // 项目列表是主数据；授权计数为辅助聚合，失败降级为 0 不阻塞列表（#657）。
    const [projectList, grantList] = await Promise.all([
      api.listProjects(),
      api.listGrants().catch(() => [] as Grant[]),
    ]);
    projects.value = projectList;
    grants.value = grantList;
    await loadMemberCounts(projectList);
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

// #440: request-sequence guard — a slow member list for project A must not
// land after the drawer re-targets project B, and a failure must not
// impersonate an empty roster for the current project.
let membersRequestSeq = 0;

async function openMembers(project: Project) {
  const seq = ++membersRequestSeq;
  memberProject.value = project;
  memberOpen.value = true;
  memberLoading.value = true;
  pickUserId.value = '';
  if (!usersLoaded.value) {
    api
      .listUsers()
      .then((list) => {
        allUsers.value = list;
        usersLoaded.value = true;
      })
      .catch(() => {
        allUsers.value = [];
      });
  }
  try {
    const rows = await api.listProjectMembers(project.id!); // list rows always carry ids
    if (seq !== membersRequestSeq) {
      return; // a newer drawer target won — this response is stale
    }
    memberUsers.value = rows;
  } catch {
    if (seq === membersRequestSeq) {
      memberUsers.value = [];
      toast.error('加载成员失败');
    }
  } finally {
    if (seq === membersRequestSeq) {
      memberLoading.value = false;
    }
  }
}

async function addMember() {
  const project = memberProject.value;
  if (!project || !pickUserId.value) return;
  addingMember.value = true;
  try {
    await api.addProjectMember(project.id!, pickUserId.value);
    pickUserId.value = '';
    toast.success('成员已添加');
    const seq = ++membersRequestSeq;
    const rows = await api.listProjectMembers(project.id!); // list rows always carry ids
    if (seq === membersRequestSeq) {
      memberUsers.value = rows;
    }
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  } finally {
    addingMember.value = false;
  }
}

function requestRemove(user: MemberView) {
  if (!memberProject.value) return;
  const project = memberProject.value;
  confirmState.value = {
    title: '移除成员',
    body: `将「${user.username!}」移出项目「${project.name!}」。`,
    confirmLabel: '移除',
    tone: 'danger',
    run: async () => {
      try {
        await api.removeProjectMember(project.id!, user.userId!);
        toast.success('成员已移除');
        const seq = ++membersRequestSeq;
        const rows = await api.listProjectMembers(project.id!); // list rows always carry ids
        if (seq === membersRequestSeq) {
          memberUsers.value = rows;
        }
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

// #617: edit name / routing tag (PATCH /admin/projects/{id}).
function openEdit(project: Project) {
  editProject.value = project;
  editName.value = project.name ?? '';
  editTag.value = project.projectTag ?? '';
  editError.value = '';
  editRequestId.value = '';
  editOpen.value = true;
}

async function saveEdit() {
  const project = editProject.value;
  if (!project) return;
  const name = editName.value.trim();
  const tag = editTag.value.trim();
  if (!name) {
    editError.value = '请输入项目名称。';
    return;
  }
  if (tag && !/^[A-Za-z0-9_-]{1,64}$/.test(tag)) {
    editError.value = '路由标签只允许字母、数字、下划线与连字符（1–64 位）。';
    return;
  }
  if (!tag && project.projectTag) {
    editError.value = '路由标签不能清空——现有 Virtual Key 的路由依赖它。';
    return;
  }
  if (name === (project.name ?? '') && tag === (project.projectTag ?? '')) {
    editOpen.value = false;
    return;
  }
  editSaving.value = true;
  editError.value = '';
  try {
    await api.updateProject(project.id!, { name, projectTag: tag || undefined });
    editOpen.value = false;
    toast.success('项目已更新');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      editError.value = error.message;
      editRequestId.value = error.requestId ?? '';
    } else {
      editError.value = '保存失败，请稍后重试。';
    }
  } finally {
    editSaving.value = false;
  }
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

    <UiPageGuide :guide="PROJECTS_GUIDE" storage-key="projects" />

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
          <!-- #657: rules live next to the field instead of only in a 409 body
               (AdminOrgService#createProject + projects.code/name width). -->
          <UiInput
            v-model="createCode"
            label="项目代码"
            required
            placeholder="例如 CORE"
            hint="必填，同一租户内唯一，最长 64 个字符。"
            data-testid="project-create-code"
          />
          <UiInput
            v-model="createName"
            label="名称"
            required
            hint="必填，最长 200 个字符。"
            data-testid="project-create-name"
          />
          <UiInput
            v-model="createTag"
            label="路由标签"
            placeholder="例如 core-ai（虚拟密钥点号后缀）"
            data-testid="project-create-tag"
          />
          <p class="next-projects__tag-hint" data-testid="project-create-tag-hint">
            留空将自动从项目代码派生；已被密钥绑定引用的标签不可修改（1–64 位字母、数字、- 或 _）。
          </p>
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
        empty-description="创建项目并添加成员后，成员即可在「我的密钥」创建虚拟密钥。"
        data-testid="projects-table"
      >
        <template #projectTag="{ row }">
          <span v-if="(row as Project).projectTag" class="ui-mono">{{
            (row as Project).projectTag
          }}</span>
          <span v-else>—</span>
        </template>
        <template #grants="{ row }">
          <span class="ui-num" data-testid="project-grant-count">{{
            grantCountOf((row as Project).id)
          }}</span>
        </template>
        <template #members="{ row }">
          <span class="ui-num" data-testid="project-member-count">{{
            memberCounts[(row as Project).id!] ?? '—'
          }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as Project).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as Project).status === 'ACTIVE' ? '正常' : '停用'"
          />
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="link"
            size="sm"
            data-testid="project-members-open"
            @click="openMembers(row as Project)"
          >
            成员
          </UiButton>
          <UiButton
            variant="link"
            size="sm"
            data-testid="project-edit-open"
            @click="openEdit(row as Project)"
          >
            编辑
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
      <h3 class="next-projects__drawer-title">添加成员</h3>
      <div class="next-projects__join-row">
        <UiSelect
          v-model="pickUserId"
          :options="
            joinableUsers.map((u) => ({
              value: u.id ?? '',
              label: u.username + ((u.displayName ?? '') ? ' · ' + u.displayName : ''),
            }))
          "
          placeholder="选择用户"
          data-testid="project-member-pick"
        />
        <UiButton
          variant="primary"
          :disabled="!pickUserId"
          :loading="addingMember"
          data-testid="project-member-add"
          @click="addMember"
          >加入</UiButton
        >
      </div>
      <p v-if="usersLoaded && !joinableUsers.length" class="next-projects__join-hint">
        没有可加入的 ACTIVE 用户。
      </p>

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
            variant="link-danger"
            size="sm"
            data-testid="project-member-remove"
            @click="requestRemove(row as MemberView)"
          >
            移除
          </UiButton>
        </template>
      </UiTable>
    </UiDrawer>

    <!-- #617: edit name / routing tag -->
    <UiDialog
      :open="editOpen"
      title="编辑项目"
      :description="editProject ? `修改「${editProject.name}」的名称与路由标签。` : ''"
      width="460px"
      data-testid="project-edit-dialog"
      @update:open="editOpen = false"
    >
      <div class="next-projects__form">
        <UiInput v-model="editName" label="名称" required data-testid="project-edit-name" />
        <UiInput
          v-model="editTag"
          label="路由标签"
          placeholder="例如 core-ai（虚拟密钥点号后缀）"
          data-testid="project-edit-tag"
        />
      </div>
      <p v-if="editError" class="ui-form-error" data-testid="project-edit-error">
        {{ editError
        }}<span v-if="editRequestId" class="ui-request-id"> requestId: {{ editRequestId }}</span>
      </p>
      <template #footer>
        <UiButton variant="ghost" data-testid="project-edit-cancel" @click="editOpen = false"
          >取消</UiButton
        >
        <UiButton
          variant="primary"
          :loading="editSaving"
          data-testid="project-edit-save"
          @click="saveEdit"
          >保存</UiButton
        >
      </template>
    </UiDialog>

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

.next-projects__tag-hint {
  margin: calc(-1 * var(--ui-space-2)) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-faint);
}

.next-projects__drawer-title {
  margin: var(--ui-space-1) 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  font-weight: 600;
  color: var(--ui-foreground-muted, #5b6472);
}

.next-projects__join-row {
  display: flex;
  gap: var(--ui-space-3);
  align-items: center;
  max-width: 440px;
  margin-bottom: var(--ui-space-3);
}

.next-projects__join-hint {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-faint);
}

.next-projects__member-name {
  font-weight: var(--ui-weight-medium);
}

.next-projects__member-sub {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}
</style>
