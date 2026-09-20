<script setup lang="ts">
/**
 * AttributionChip — how this request came to belong to the project it was billed to
 * (#1128, CAA V54).
 *
 * The gateway records two things and the console showed neither: the server's ruling
 * (`resolutionStatus`) and the client's claim that fed it (`claimSource` /
 * `claimConfidence`). They stay apart in the bubble on purpose — the claim is
 * unverified input, the ruling is the verdict.
 *
 * The chip appears when the attribution was **decided** rather than defaulted. Every
 * authenticated request walks the ladder — a key with a single binding resolves as
 * `SOLE_BINDING` — so chipping every row would print 「唯一绑定」 down the whole table.
 * What a reader scans for is the rows something *else* decided (a header claim, a
 * suffix, a policy route) or where a claim was made at all. Everything else renders a
 * dash, which is also what pre-V54 rows get when the columns are null.
 */
import { computed } from 'vue';
import { UiStatusBadge, UiTooltip } from '@/ui';

const props = defineProps<{
  resolutionStatus?: string | null;
  claimSource?: string | null;
  claimConfidence?: string | null;
}>();

/**
 * Four rulings are produced today (`RequestContextResolver`); `UNATTRIBUTED` and
 * `AMBIGUOUS` belong to the shared vocabulary — they are values a *client* may claim
 * as its own status — and are labelled here so a future writer does not surface as an
 * English token. Unknown values are shown raw for the same reason.
 */
const RULING_TEXT: Record<string, string> = {
  RESOLVED_HEADER: '按请求头声明',
  RESOLVED_SUFFIX: '按密钥后缀',
  SOLE_BINDING: '唯一绑定',
  POLICY_ROUTED: '未归属策略路由',
  UNATTRIBUTED: '未归属',
  AMBIGUOUS: '无从判定',
};

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
  git_remote: '仓库远端',
  suffix: '密钥后缀',
  none: '无',
};

/** See the header comment: the default route is not worth a chip on every row. */
const informative = computed(
  () =>
    Boolean(props.resolutionStatus) &&
    (props.resolutionStatus !== 'SOLE_BINDING' || Boolean(props.claimSource)),
);

/** The bubble: the ruling, then what the client claimed — and which of the two is verified. */
const note = computed(() => {
  const parts = [`归属由服务端裁定：${label.value}`];
  if (props.claimSource) {
    const source = CLAIM_SOURCE_TEXT[props.claimSource] ?? props.claimSource;
    const confidence = props.claimConfidence ? `，置信度 ${props.claimConfidence}` : '';
    parts.push(`客户端声明来源：${source}${confidence}（声明未经验证）`);
  } else {
    // The column is null both when the client sent nothing and when it sent something
    // that was dropped (>64 chars, blank after trimming, or outside the allowlist), and
    // the console cannot tell those apart — so it must not claim the first.
    parts.push('本次请求未记录客户端声明（客户端未发送，或发送后未通过校验）');
  }
  return parts.join(' · ');
});
</script>

<template>
  <UiTooltip v-if="informative" :text="note">
    <UiStatusBadge :tone="tone" :label="label" data-testid="usage-attribution-chip" />
  </UiTooltip>
  <span v-else>—</span>
</template>
