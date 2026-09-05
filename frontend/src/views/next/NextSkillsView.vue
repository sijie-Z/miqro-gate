<script setup lang="ts">
/**
 * NextSkillsView — /app-new/skills pilot page (UI U1, PostHog language).
 * Behaviour parity with legacy SkillHubView: browsable internal skill cards
 * with scoped download (403 → friendly guidance).
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiEmptyState, toast } from '@/ui';
import type { SkillView } from '@/types/generated-api';

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
    toast.success(`已下载 ${skill.name} v${skill.version}`);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 'SKILL_DOWNLOAD_FORBIDDEN') {
        toast.error('该技能未授权给你的团队/项目，无法下载。');
      } else {
        toast.error(error.message);
      }
    } else {
      toast.error('下载失败，请稍后重试。');
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
  <div class="ui-page next-skills">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">技能库</h1>
        <p class="ui-page-desc">
          公司内部技能目录，全部技能对登录用户可见。下载权限按团队/项目授权。
        </p>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="skills-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <div v-if="loading" class="next-skills__grid">
      <div v-for="n in 6" :key="n" class="ui-panel next-skills__card next-skills__card--skeleton">
        <span class="ui-skeleton" style="height: 16px; width: 60%" />
        <span class="ui-skeleton" style="height: 12px; width: 100%" />
        <span class="ui-skeleton" style="height: 12px; width: 80%" />
      </div>
    </div>

    <div v-else-if="skills.length" class="next-skills__grid" data-testid="skill-grid">
      <article
        v-for="skill in skills"
        :key="skill.id"
        class="ui-panel next-skills__card"
        data-testid="skill-card"
      >
        <header class="next-skills__card-head">
          <h2 class="next-skills__name">{{ skill.name }}</h2>
          <span class="ui-mono next-skills__version">v{{ skill.version }}</span>
        </header>
        <p class="next-skills__desc">{{ skill.description }}</p>
        <div v-if="skill.tags?.length" class="next-skills__tags">
          <span v-for="tag in skill.tags" :key="tag" class="next-skills__tag">{{ tag }}</span>
        </div>
        <footer class="next-skills__foot">
          <div class="next-skills__meta">
            <span v-if="skill.author" class="next-skills__meta-item">{{ skill.author }}</span>
            <span v-if="skill.license" class="next-skills__license">{{ skill.license }}</span>
            <span class="next-skills__meta-item ui-num">{{ formatBytes(skill.contentBytes) }}</span>
          </div>
          <UiButton
            variant="secondary"
            size="sm"
            data-testid="skill-download"
            @click="download(skill)"
          >
            下载
          </UiButton>
        </footer>
      </article>
    </div>

    <UiEmptyState
      v-else
      title="技能目录还是空的"
      description="管理员上传技能包后，这里会展示全部可用技能。"
    />
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-skills__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--ui-space-4);
}

.next-skills__card {
  padding: var(--ui-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-skills__card--skeleton {
  gap: var(--ui-space-3);
  padding: var(--ui-space-5);
}

.next-skills__card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.next-skills__name {
  margin: 0;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  overflow-wrap: anywhere;
}

.next-skills__version {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-skills__desc {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.next-skills__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-2);
}

.next-skills__tag {
  font-size: var(--ui-font-size-xs);
  padding: 2px 10px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  color: var(--ui-foreground-secondary);
}

.next-skills__foot {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding-top: var(--ui-space-3);
  border-top: 1px solid var(--ui-border-muted);
}

.next-skills__meta {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-skills__license {
  padding: 1px 8px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  color: var(--ui-foreground-secondary);
  font-family: var(--ui-font-mono);
  font-size: 11px;
}
</style>
