<script setup lang="ts">
/**
 * NextAdminSkillsView — /app/skillhub v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy skillhub page: upload an Agent Skills zip
 * with a semver, manage download authorisation per project/team (no scope =
 * public) and archive deprecated skills. Project/team scope lists render as
 * checkbox groups (the v2 select is single-value; console scale keeps the
 * list short and fully testable).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type { Project, SkillView, Team } from '@/types/generated-api';

const skills = ref<SkillView[]>([]);
const projects = ref<Project[]>([]);
const teams = ref<Team[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '200px' },
  { key: 'version', title: '版本', width: '90px' },
  { key: 'tags', title: '标签', minWidth: '160px' },
  { key: 'contentBytes', title: '大小', width: '90px', align: 'right' as const },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'createdAt', title: '发布时间', width: '170px' },
  { key: 'actions', title: '操作', width: '150px', align: 'center' as const },
];

// Upload form
const uploadVisible = ref(false);
const uploadVersion = ref('1.0.0');
const uploadFile = ref<File | null>(null);
const uploading = ref(false);
const uploadError = ref('');

// Access dialog
const accessSkill = ref<SkillView | null>(null);
const accessVisible = ref(false);
const accessProjectIds = ref<string[]>([]);
const accessTeamIds = ref<string[]>([]);
const accessSaving = ref(false);
const accessError = ref('');

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const canUpload = computed(
  () => uploadFile.value !== null && uploadVersion.value.trim().length > 0,
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    skills.value = await api.adminListSkills();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载技能列表失败。';
    }
  } finally {
    loading.value = false;
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  uploadFile.value = input.files?.[0] ?? null;
  if (uploadFile.value && !/\.zip$/i.test(uploadFile.value.name)) {
    uploadError.value = '技能包必须是 .zip 文件。';
    uploadFile.value = null;
    input.value = '';
  } else {
    uploadError.value = '';
  }
}

async function upload() {
  uploadError.value = '';
  if (!canUpload.value || !uploadFile.value) {
    uploadError.value = '请选择 zip 文件并填写版本号。';
    return;
  }
  uploading.value = true;
  try {
    await api.adminUploadSkill(uploadVersion.value.trim(), uploadFile.value);
    uploadVisible.value = false;
    uploadFile.value = null;
    uploadVersion.value = '1.0.0';
    toast.success('技能已上传');
    await load();
  } catch (error) {
    uploadError.value = error instanceof ApiError ? error.message : '上传失败，请稍后重试。';
  } finally {
    uploading.value = false;
  }
}

function openAccess(skill: SkillView) {
  accessSkill.value = skill;
  accessProjectIds.value = [];
  accessTeamIds.value = [];
  accessError.value = '';
  accessVisible.value = true;
}

async function saveAccess() {
  if (!accessSkill.value) {
    return;
  }
  accessSaving.value = true;
  accessError.value = '';
  const scopes = [
    ...accessProjectIds.value.map((id) => ({ scopeType: 'PROJECT', scopeId: id })),
    ...accessTeamIds.value.map((id) => ({ scopeType: 'TEAM', scopeId: id })),
  ];
  try {
    await api.adminSetSkillAccess(accessSkill.value.id, scopes);
    accessVisible.value = false;
    toast.success(scopes.length ? '下载授权已更新' : '技能已设为公开（全员可下载）');
  } catch (error) {
    accessError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    accessSaving.value = false;
  }
}

function requestArchive(skill: SkillView) {
  confirmState.value = {
    title: `归档技能「${skill.name}」`,
    body: '归档后技能从目录隐藏（数据与授权保留）；重新上传同名技能即可恢复。',
    confirmLabel: '归档',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminArchiveSkill(skill.id);
        toast.success('技能已归档');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
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

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(() => {
  void load();
  Promise.all([api.listProjects(), api.listTeams()])
    .then(([projectList, teamList]) => {
      projects.value = projectList;
      teams.value = teamList;
    })
    .catch(() => {
      projects.value = [];
      teams.value = [];
    });
});
</script>

<template>
  <div class="ui-page next-skills">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">技能库管理</h1>
        <p class="ui-page-desc">
          上传技能包（Anthropic Agent Skills 格式）并管理下载授权；上传后全员可见，下载按授权。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="skill-upload-open"
          @click="uploadVisible = !uploadVisible"
        >
          {{ uploadVisible ? '收起表单' : '上传技能' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="uploadVisible"
      class="ui-panel next-skills__upload"
      data-testid="skill-upload-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">上传技能包</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-skills__hint">
          zip 内只包含一个技能目录（如 <span class="ui-mono">web-scraper/</span>），目录内含
          <span class="ui-mono">SKILL.md</span>（YAML frontmatter：name 与目录名一致、description
          必填）。包上限 5MB。
        </p>
        <div class="next-skills__upload-grid">
          <div class="ui-field">
            <span class="ui-field__label"
              >技能包（zip） <span class="ui-field__required">*</span></span
            >
            <input
              type="file"
              accept=".zip"
              class="next-skills__file"
              data-testid="skill-upload-file"
              @change="onFileChange"
            />
            <p class="ui-field__hint">
              {{ uploadFile ? uploadFile.name : '选择 .zip 文件' }}
            </p>
          </div>
          <UiInput
            v-model="uploadVersion"
            label="版本（语义化）"
            required
            placeholder="例如 1.0.0"
            data-testid="skill-upload-version"
          />
          <p v-if="uploadError" class="ui-form-error">{{ uploadError }}</p>
          <div class="next-skills__actions">
            <UiButton
              variant="primary"
              :disabled="!canUpload"
              :loading="uploading"
              data-testid="skill-upload-submit"
              @click="upload"
            >
              上传
            </UiButton>
            <UiButton variant="ghost" @click="uploadVisible = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ skills.length }} 个技能</span>
      </div>
      <UiTable
        :columns="columns"
        :data="skills"
        :loading="loading"
        row-key="id"
        empty-title="还没有技能"
        empty-description="点击「上传技能」发布第一个技能包。"
        data-testid="admin-skills-table"
      >
        <template #name="{ row }">
          <span class="next-skills__name">{{ (row as SkillView).name }}</span>
        </template>
        <template #version="{ row }">
          <span class="ui-mono">v{{ (row as SkillView).version }}</span>
        </template>
        <template #tags="{ row }">
          <span v-if="(row as SkillView).tags?.length" class="next-skills__tags">
            <span v-for="tag in (row as SkillView).tags" :key="tag" class="next-skills__tag">{{
              tag
            }}</span>
          </span>
          <span v-else>—</span>
        </template>
        <template #contentBytes="{ row }">
          <span class="ui-num">{{ formatBytes((row as SkillView).contentBytes) }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as SkillView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as SkillView).status === 'ACTIVE' ? '已发布' : '已归档'"
          />
        </template>
        <template #createdAt="{ row }">{{ formatTime((row as SkillView).createdAt) }}</template>
        <template #actions="{ row }">
          <div class="next-skills__actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="skill-access"
              @click="openAccess(row as SkillView)"
              >授权</UiButton
            >
            <UiButton
              v-if="(row as SkillView).status === 'ACTIVE'"
              variant="ghost"
              size="sm"
              class="next-skills__danger"
              data-testid="skill-archive"
              @click="requestArchive(row as SkillView)"
              >归档</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <!-- Download authorisation: no scope selected = public -->
    <UiDialog
      :open="accessVisible"
      :title="accessSkill ? `下载授权 · ${accessSkill.name}` : '下载授权'"
      width="520px"
      data-testid="skill-access-dialog"
      @update:open="accessVisible = false"
    >
      <p class="next-skills__hint">
        不选任何范围 = 公开（全员可下载）。授权后仅所选团队/项目成员可下载。
      </p>
      <div class="next-skills__scope">
        <div class="ui-field">
          <span class="ui-field__label">授权项目</span>
          <div
            v-if="projects.length"
            class="next-skills__check-list"
            data-testid="skill-access-projects"
          >
            <label v-for="p in projects" :key="p.id" class="next-skills__check">
              <input
                v-model="accessProjectIds"
                type="checkbox"
                :value="p.id"
                data-testid="skill-access-project"
              />
              <span>{{ p.name }}（{{ p.code }}）</span>
            </label>
          </div>
          <p v-else class="ui-field__hint">暂无项目</p>
        </div>
        <div class="ui-field">
          <span class="ui-field__label">授权团队</span>
          <div v-if="teams.length" class="next-skills__check-list" data-testid="skill-access-teams">
            <label v-for="t in teams" :key="t.id" class="next-skills__check">
              <input
                v-model="accessTeamIds"
                type="checkbox"
                :value="t.id"
                data-testid="skill-access-team"
              />
              <span>{{ t.name }}</span>
            </label>
          </div>
          <p v-else class="ui-field__hint">暂无团队</p>
        </div>
        <p v-if="accessError" class="ui-form-error">{{ accessError }}</p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="accessVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="accessSaving"
          data-testid="skill-access-save"
          @click="saveAccess"
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

.ui-field__required {
  color: var(--ui-danger-fg);
}

.ui-field__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  line-height: var(--ui-line-height-sm);
}

.next-skills__upload {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-skills__hint {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-skills__upload-grid {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.next-skills__file {
  width: 100%;
  font-size: var(--ui-font-size-sm);
}

.next-skills__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-skills__name {
  font-weight: var(--ui-weight-medium);
}

.next-skills__tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-skills__tag {
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-sm);
  padding: 1px var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  color: var(--ui-foreground-secondary);
}

.next-skills__danger {
  color: var(--ui-danger-fg);
}

.next-skills__scope {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-skills__check-list {
  display: flex;
  flex-direction: column;
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  padding: var(--ui-space-1);
}

.next-skills__check {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-1) var(--ui-space-2);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
}

.next-skills__check:hover {
  background: var(--ui-fill-hover);
}

.next-skills__check input {
  accent-color: var(--ui-primary);
  margin: 0;
}
</style>
