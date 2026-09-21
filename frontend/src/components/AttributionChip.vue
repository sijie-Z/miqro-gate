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
 * See {@link informative} for when the chip speaks: the no-decision routes stay
 * silent (with one exception, #1139 below), while a header-resolved request, a
 * recorded claim, or a "we could not place this" ruling is not. Rows written before
 * V54 (all columns null) render a dash.
 */
import { computed } from 'vue';
import { UiStatusBadge, UiTooltip } from '@/ui';

const props = defineProps<{
  resolutionStatus?: string | null;
  /**
   * #1139 (CAA V72): the number of ACTIVE bindings the key held when the ruling
   * ran. 1 = there was nothing to choose from; >1 = the ruling picked among
   * candidates; null/undefined = written before V72 — unknown, never guessed.
   */
  resolutionCandidates?: number | null;
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

/**
 * The routes that record no explicit intent — `SOLE_BINDING` always, and
 * `RESOLVED_SUFFIX` in the single-candidate case (see {@link informative}).
 */
const QUIET_RULINGS = new Set(['RESOLVED_SUFFIX', 'SOLE_BINDING']);

/**
 * #1139: `RESOLVED_SUFFIX` mixes two different facts, and the candidate count is
 * what separates them.
 *
 * - candidates > 1 — **the suffix really chose**: the key held several ACTIVE
 *   bindings and the suffix in the key picked one. That is an auditable decision, so
 *   the chip speaks.
 * - candidates == 1 — **the suffix merely matched**: a key is minted with its
 *   project's tag as its suffix, so the suffix step hits before the sole-binding
 *   fallback and there was nothing to choose from. Same "no decision" fact as
 *   `SOLE_BINDING`; chipping it would print 「按密钥后缀」 down the whole table.
 * - candidates == null — a row written before V72: the column cannot tell the two
 *   apart, and guessing would repaint the whole historical table — so it stays quiet
 *   too, and the bubble (when a claim opens it anyway) says 未知 rather than picking
 *   a story.
 *
 * Everything else speaks: a recorded claim (someone is using the context mechanism), a
 * header-resolved request (a client *asked* for a project), a policy route (the
 * unattributed bucket), and any value nobody has words for yet — a new ruling
 * reaching the console should be visible on day one, not silently dashed.
 */
const informative = computed(() => {
  const status = props.resolutionStatus ?? '';
  if (!status) {
    return false;
  }
  if (props.claimSource) {
    return true;
  }
  if (status === 'RESOLVED_SUFFIX') {
    return (props.resolutionCandidates ?? 0) > 1;
  }
  return !QUIET_RULINGS.has(status);
});

/** #1139: say which of the two `RESOLVED_SUFFIX` facts this row is — or that it is unknown. */
const candidateNote = computed(() => {
  const n = props.resolutionCandidates;
  if (n == null) {
    return '候选绑定数未记录（V72 之前的行）——无法区分「后缀在多个候选中选定」与「单绑定恰好命中」';
  }
  if (n > 1) {
    return `该密钥有 ${n} 个候选绑定，后缀从中选定`;
  }
  return '该密钥仅 1 个候选绑定，后缀只是恰好命中——并无候选可挑（与「唯一绑定」描述同一事实）';
});

/** The bubble: the ruling, then what the client claimed — and which of the two is verified. */
const note = computed(() => {
  const parts = [`归属由服务端裁定：${label.value}`];
  if (props.resolutionStatus === 'RESOLVED_SUFFIX') {
    parts.push(candidateNote.value);
  }
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
