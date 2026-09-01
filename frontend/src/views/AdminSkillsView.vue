<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { Project, SkillView, Team } from '@/types/api';

const skills = ref<SkillView[]>([]);
const projects = ref<Project[]>([]);
const teams = ref<Team[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 180 },
  { colKey: 'version', title: '版本', width: 90 },
  { colKey: 'tags', title: '标签', minWidth: 140 },
  { colKey: 'bytes', title: '大小', width: 90, align: 'right' },
  { colKey: 'status', title: '状态', width: 90 },
  { colKey: 'actions', title: '操作', width: 180, fixed: 'right' },
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
    MessagePlugin.success('技能已上传');
    await load();
  } catch (error) {
    uploadError.value = error instanceof ApiError ? error.message : '上传失败，请稍后重试。';
  } finally {
    uploading.value = false;
  }
}

async function openAccess(skill: SkillView) {
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
    MessagePlugin.success(scopes.length ? '下载授权已更新' : '技能已设为公开（全员可下载）');
  } catch (error) {
    accessError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    accessSaving.value = false;
  }
}

async function archive(skill: SkillView) {
  try {
    await confirmDialog({
      header: `归档技能「${skill.name}」`,
      body: `归档后技能从目录隐藏（数据与授权保留）；重新上传同名技能即可恢复。`,
      confirmBtn: '归档',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.adminArchiveSkill(skill.id);
    MessagePlugin.success('技能已归档');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
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
  <div class="admin-skills-page">
    <PageHeader
      title="SkillHub 管理"
      description="上传技能包（Anthropic Agent Skills 格式）并管理下载授权；上传后全员可见，下载按授权。"
    >
      <template #actions>
        <t-button
          theme="primary"
          data-testid="skill-upload-open"
          @click="uploadVisible = !uploadVisible"
        >
          {{ uploadVisible ? '收起表单' : '上传技能' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="uploadVisible" class="upload-panel" data-testid="skill-upload-form">
      <h3 class="panel-title">上传技能包</h3>
      <p class="hint">
        zip 内只包含一个技能目录（如 <span class="mk-mono">web-scraper/</span>），目录内含
        <span class="mk-mono">SKILL.md</span>（YAML frontmatter：name 与目录名一致、description
        必填）。 包上限 5MB。
      </p>
      <t-form label-align="top" class="upload-form">
        <t-form-item label="技能包（zip）" required-mark>
          <input
            type="file"
            accept=".zip"
            class="file-input"
            data-testid="skill-upload-file"
            @change="onFileChange"
          />
          <span v-if="uploadFile" class="mk-mono file-name">{{ uploadFile.name }}</span>
        </t-form-item>
        <t-form-item label="版本（语义化）" required-mark>
          <t-input
            v-model="uploadVersion"
            placeholder="例如 1.0.0"
            data-testid="skill-upload-version"
          />
        </t-form-item>
        <p v-if="uploadError" class="form-error">{{ uploadError }}</p>
        <t-button
          theme="primary"
          :disabled="!canUpload"
          :loading="uploading"
          data-testid="skill-upload-submit"
          @click="upload"
        >
          上传
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="skills"
        class="skills-table"
        data-testid="admin-skills-table"
      >
        <template #name="{ row }">
          <span class="skill-name">{{ row.name }}</span>
        </template>
        <template #version="{ row }">
          <span class="mk-mono">v{{ row.version }}</span>
        </template>
        <template #tags="{ row }">
          <span v-if="row.tags?.length" class="skill-tags">
            <span v-for="tag in row.tags" :key="tag" class="skill-tag">{{ tag }}</span>
          </span>
          <span v-else class="hint">—</span>
        </template>
        <template #bytes="{ row }">
          <span class="mk-num">{{ formatBytes(row.contentBytes) }}</span>
        </template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.status === 'ACTIVE' ? 'Active' : 'Archived' }}
          </span>
        </template>
        <template #actions="{ row }">
          <t-button variant="text" data-testid="skill-access" @click="openAccess(row)"
            >授权</t-button
          >
          <t-button
            v-if="row.status === 'ACTIVE'"
            variant="text"
            theme="danger"
            data-testid="skill-archive"
            @click="archive(row)"
          >
            归档
          </t-button>
        </template>
        <template #empty>
          <div class="table-empty">还没有技能。点击「上传技能」发布第一个技能包。</div>
        </template>
      </t-table>
    </t-loading>

    <t-dialog
      v-model:visible="accessVisible"
      :header="accessSkill ? `下载授权 · ${accessSkill.name}` : '下载授权'"
      width="480px"
      :close-on-overlay-click="false"
    >
      <p class="dialog-hint">
        不选任何范围 = 公开（全员可下载）。授权后仅所选团队/项目成员可下载。
      </p>
      <t-form label-align="top">
        <t-form-item label="授权项目">
          <t-select v-model="accessProjectIds" multiple clearable placeholder="不选 = 不按项目授权">
            <t-option
              v-for="p in projects"
              :key="p.id"
              :value="p.id"
              :label="`${p.name}（${p.code}）`"
            />
          </t-select>
        </t-form-item>
        <t-form-item label="授权团队">
          <t-select v-model="accessTeamIds" multiple clearable placeholder="不选 = 不按团队授权">
            <t-option v-for="t in teams" :key="t.id" :value="t.id" :label="t.name" />
          </t-select>
        </t-form-item>
        <p v-if="accessError" class="form-error">{{ accessError }}</p>
      </t-form>
      <template #footer>
        <t-button
          theme="primary"
          :loading="accessSaving"
          data-testid="skill-access-save"
          @click="saveAccess"
        >
          保存
        </t-button>
        <t-button @click="accessVisible = false">取消</t-button>
      </template>
    </t-dialog>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.upload-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.upload-form {
  max-width: 480px;
}

.file-input {
  width: 100%;
}

.file-name {
  font-size: 12px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.skills-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.skill-name {
  font-weight: 500;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.skill-tag {
  font-size: 12px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--miqrokey-bg-subtle);
  border: 1px solid var(--miqrokey-border-muted);
  color: var(--miqrokey-text-secondary);
}

.dialog-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  line-height: 20px;
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}
</style>
