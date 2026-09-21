<script setup lang="ts">
/**
 * ProviderBrandChip — 品牌标识层（frontend-design.md §4.1）：供应商渐变图标徽章。
 * 有公开品牌图形的供应商使用官方图形（Simple Icons，白色 currentColor）；
 * 无公开图形的使用品牌单字；未知供应商回退首字母 + 中性渐变。
 */
import { computed } from 'vue';
import aliyun from '@/assets/providers/aliyun.svg?raw';
import baidu from '@/assets/providers/baidu.svg?raw';
import deepseek from '@/assets/providers/deepseek.svg?raw';
import minimax from '@/assets/providers/minimax.svg?raw';
import moonshot from '@/assets/providers/moonshot.svg?raw';

const props = withDefaults(defineProps<{ slug?: string; name?: string; size?: 'sm' | 'md' }>(), {
  slug: '',
  name: '',
  size: 'md',
});

/** provider slug → 官方图形（内联 SVG 源）。 */
const GLYPHS: Record<string, string> = { aliyun, baidu, deepseek, minimax, moonshot };
/** 无公开图形供应商的品牌单字。 */
const MONOGRAMS: Record<string, string> = { tencent: '腾', zhipu: '智', volcengine: '火' };

const slug = computed(() => props.slug.toLowerCase());
const glyph = computed(() => GLYPHS[slug.value] ?? null);
const monogram = computed(() => MONOGRAMS[slug.value] ?? null);
const fallback = computed(() => (props.name || props.slug || '?').slice(0, 1).toUpperCase());
const known = computed(
  () => slug.value !== '' && (slug.value in GLYPHS || slug.value in MONOGRAMS),
);
</script>

<template>
  <span
    class="mk-brand-chip"
    :class="[
      known ? `mk-chip-${slug}` : 'mk-brand-chip--unknown',
      size === 'sm' && 'mk-brand-chip--sm',
    ]"
    :data-testid="`brand-chip-${slug || 'unknown'}`"
    aria-hidden="true"
  >
    <!-- eslint-disable-next-line vue/no-v-html -- glyphs are static, bundled SVG assets -->
    <span v-if="glyph" class="mk-brand-chip__glyph" v-html="glyph" />
    <template v-else-if="monogram">{{ monogram }}</template>
    <template v-else>{{ fallback }}</template>
  </span>
</template>
