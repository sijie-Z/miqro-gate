<script setup lang="ts">
/**
 * AttributionChip — how this request came to belong to the project it was billed to
 * (#1128, CAA V54).
 *
 * The gateway has recorded both halves since V54 and the console showed neither: the
 * server's ruling (`RESOLVED_HEADER` / `RESOLVED_SUFFIX` / `SOLE_BINDING` /
 * `POLICY_ROUTED` / `UNATTRIBUTED` / `AMBIGUOUS`) and the client's claim that fed it
 * (`claimSource` + `claimConfidence`). They are kept apart in the bubble on purpose —
 * the claim is unverified input, the ruling is the verdict, and a row where the two
 * disagree is exactly the row someone is looking for.
 *
 * Renders nothing when the row never entered the ladder (a single-binding key, or a
 * row older than the feature): an "unknown" chip on every ordinary row would be noise,
 * and the absence is the honest statement.
 */
import { computed } from 'vue';
import { UiStatusBadge, UiTooltip } from '@/ui';

const props = defineProps<{
  resolutionStatus?: string | null;
  claimSource?: string | null;
  claimConfidence?: string | null;
}>();

const RULING_TEXT: Record<string, string> = {
  RESOLVED_HEADER: '按请求头声明',
  RESOLVED_SUFFIX: '按密钥后缀',
  SOLE_BINDING: '唯一绑定',
  POLICY_ROUTED: '未归属策略路由',
  UNATTRIBUTED: '未归属',
  AMBIGUOUS: '无从判定',
};

/** Unrecognised values are shown raw rather than swallowed: a new ruling should be visible. */
const label = computed(
  () => RULING_TEXT[props.resolutionStatus ?? ''] ?? props.resolutionStatus ?? '',
);

const tone = computed(() => {
  switch (props.resolutionStatus) {
    case 'UNATTRIBUTED':
    case 'AMBIGUOUS':
      return 'warning' as const;
    case 'POLICY_ROUTED':
      return 'info' as const;
    default:
      return 'neutral' as const;
  }
});

const CLAIM_SOURCE_TEXT: Record<string, string> = {
  prompt_url: '提示中的链接',
  tool_path: '工具读取的路径',
  bash_cwd: '命令的工作目录',
  system_cwd: '进程的工作目录',
  suffix: '密钥后缀',
  none: '无',
};

/** The bubble: the ruling, then what the client claimed — and which of the two is verified. */
const note = computed(() => {
  const parts = [`归属由服务端裁定：${label.value}`];
  if (props.claimSource) {
    const source = CLAIM_SOURCE_TEXT[props.claimSource] ?? props.claimSource;
    const confidence = props.claimConfidence ? `，置信度 ${props.claimConfidence}` : '';
    parts.push(`客户端声明来源：${source}${confidence}（声明未经验证）`);
  } else {
    parts.push('本次请求没有客户端声明');
  }
  return parts.join(' · ');
});
</script>

<template>
  <UiTooltip v-if="resolutionStatus" :text="note">
    <UiStatusBadge :tone="tone" :label="label" data-testid="usage-attribution-chip" />
  </UiTooltip>
  <span v-else>—</span>
</template>
