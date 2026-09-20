<script setup lang="ts">
/**
 * CostSplitBar — what a money figure was spent on (#1097).
 *
 * The API has always valued each token dimension separately; the group used to add
 * those four amounts into one number and drop the split, so the console could only
 * say *how much* a bill was, never *what for*. This is the mark that gives the
 * figure back its composition: a thin stacked bar whose segments are the same four
 * amounts the total was summed from, in fixed colour order — so the bar lands
 * exactly on the number it sits under rather than beside it.
 *
 * Identity is never carried by colour alone: the bubble spells the four amounts out,
 * and every caller prints the figure itself as text next to the bar. The two middle
 * colours measure under 3:1 against the card surface, which makes those labels
 * required rather than decorative.
 *
 * When there is no split to draw — an older payload, or usage nothing could price —
 * the component renders **nothing**. An empty rail would be a mark that claims
 * something it cannot say, and on a white card a grey rail is invisible anyway; the
 * caller's figure stands on its own.
 *
 * Widths are shares of the split's own sum, which is what keeps a partial split
 * filling the rail instead of trailing off at a share of a total it does not know.
 */
import { computed } from 'vue';
import { COST_SPLIT_COLORS } from '@/lib/chart-palette';
import { UiTooltip } from '@/ui';

const props = defineProps<{
  /** The figure the bar explains — named in the bubble and the accessible label. */
  label: string;
  total: number;
  input: number;
  output: number;
  cacheRead: number;
  cacheCreation: number;
}>();

const money = (value: number) => `¥${value.toFixed(4)}`;

/**
 * Money that is not a finite, non-negative number is treated as "not stated": the
 * amounts come from summed cents and cannot legitimately be negative, so drawing one
 * would put a segment on the bar that contradicts the bubble above it.
 */
const amount = (value: number) => (Number.isFinite(value) && value > 0 ? value : 0);

const parts = computed(() => [
  { key: 'input', name: '输入', value: amount(props.input), color: COST_SPLIT_COLORS.input },
  { key: 'output', name: '输出', value: amount(props.output), color: COST_SPLIT_COLORS.output },
  {
    key: 'cacheRead',
    name: '缓存读',
    value: amount(props.cacheRead),
    color: COST_SPLIT_COLORS.cacheRead,
  },
  {
    key: 'cacheCreation',
    name: '缓存写',
    value: amount(props.cacheCreation),
    color: COST_SPLIT_COLORS.cacheCreation,
  },
]);

const drawn = computed(() => {
  const sum = parts.value.reduce((acc, p) => acc + p.value, 0);
  if (sum <= 0) return [];
  return parts.value
    .filter((p) => p.value > 0)
    .map((p) => ({ ...p, width: (p.value / sum) * 100 }));
});

const note = computed(
  () =>
    `${props.label}构成：` +
    parts.value.map((p) => `${p.name} ${money(p.value)}`).join(' · ') +
    `（合计 ${money(amount(props.total))}）`,
);
</script>

<template>
  <UiTooltip v-if="drawn.length" :text="note">
    <span class="cost-split" role="img" :aria-label="note" data-testid="cost-split-bar">
      <span
        v-for="(part, index) in drawn"
        :key="part.key"
        class="cost-split__seg"
        :class="{
          'cost-split__seg--first': index === 0,
          'cost-split__seg--last': index === drawn.length - 1,
        }"
        :style="{ width: `${part.width}%`, background: part.color }"
        :data-testid="`cost-split-${part.key}`"
      />
    </span>
  </UiTooltip>
</template>

<style scoped>
.cost-split {
  display: flex;
  gap: 2px; /* the stacking gap: adjacent fills must not touch */
  align-items: stretch;
  width: 100%;
  min-width: 80px;
  height: 6px;
}

.cost-split__seg {
  display: block;
  height: 100%;
}

.cost-split__seg--first {
  border-top-left-radius: 4px;
  border-bottom-left-radius: 4px;
}

.cost-split__seg--last {
  border-top-right-radius: 4px;
  border-bottom-right-radius: 4px;
}
</style>
