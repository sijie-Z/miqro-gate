<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { SkillView } from '@/types/api';

const skills = ref<SkillView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    skills.value = await api.listSkills();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载技能目录失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function download(skill: SkillView) {
  try {
    await api.downloadSkill(skill.id, skill.name);
    MessagePlugin.success(`已下载 ${skill.name} v${skill.version}`);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 'SKILL_DOWNLOAD_FORBIDDEN') {
        MessagePlugin.error('该技能未授权给你的团队/项目，无法下载。');
      } else {
        MessagePlugin.error(error.message);
      }
    } else {
      MessagePlugin.error('下载失败，请稍后重试。');
    }
  }
}

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

onMounted(load);
</script>

<template>
  <div class="skillhub-page">
    <PageHeader
      title="SkillHub"
      description="公司内部技能目录：全部技能对登录用户可见；下载按团队/项目授权。"
    />

    <t-alert
      v-if="loadError"
      theme="error"
      :close-btn="false"
      class="block-alert"
      data-testid="skills-load-error"
    >
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <t-loading :loading="loading" size="small" show-overlay>
      <div v-if="skills.length" class="skill-grid" data-testid="skill-grid">
        <div v-for="skill in skills" :key="skill.id" class="skill-card" data-testid="skill-card">
          <div class="skill-card-head">
            <span class="skill-name">{{ skill.name }}</span>
            <span class="mk-mono skill-version">v{{ skill.version }}</span>
          </div>
          <p class="skill-desc">{{ skill.description }}</p>
          <div class="skill-tags">
            <span v-for="tag in skill.tags" :key="tag" class="skill-tag">{{ tag }}</span>
          </div>
          <div class="skill-meta">
            <span v-if="skill.author">{{ skill.author }}</span>
            <span v-if="skill.license" class="mk-mono">{{ skill.license }}</span>
            <span class="mk-num">{{ formatBytes(skill.contentBytes) }}</span>
          </div>
          <div class="skill-actions">
            <t-button
              theme="primary"
              size="small"
              data-testid="skill-download"
              @click="download(skill)"
            >
              下载
            </t-button>
          </div>
        </div>
      </div>
      <div v-else-if="!loading && !loadError" class="skill-empty">
        <p>技能目录还是空的。</p>
        <p class="hint">管理员上传技能包后，这里会展示全部可用技能。</p>
      </div>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.skill-card {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.skill-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.skill-name {
  font-weight: 600;
  font-size: 15px;
  overflow-wrap: anywhere;
}

.skill-version {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
  white-space: nowrap;
}

.skill-desc {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--miqrokey-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.skill-tag {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--miqrokey-bg-subtle);
  border: 1px solid var(--miqrokey-border-muted);
  color: var(--miqrokey-text-secondary);
}

.skill-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
}

.skill-actions {
  margin-top: auto;
  text-align: right;
}

.skill-empty {
  padding: 48px 0;
  text-align: center;
  color: var(--miqrokey-text-secondary);
}

.skill-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
