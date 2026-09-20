<script setup lang="ts">
/**
 * UiDonut — pure-CSS share chart (Vben analysis “成交占比” pattern).
 * Renders a conic-gradient ring with a centred total; consumers render their
 * own legend. Colours come from the shared categorical palette
 * (`@/lib/chart-palette`): named slots in fixed order, and anything past the
 * last slot is the neutral 「其他」 colour rather than a cycled hue — the donut
 * is one of the two sanctioned gradient surfaces.
 */
import { computed } from 'vue';
import { CHART_OTHER_COLOR, CHART_PALETTE } from '@/lib/chart-palette';

export interface UiDonutSegment {
  label: string;
  value: number;
  color?: string;
}

const props = withDefaults(
  defineProps<{
    segments: UiDonutSegment[];
    size?: number;
    centerText?: string;
  }>(),
  {
    size: 96,
    centerText: '',
  },
);

const resolved = computed(() => {
  const total = props.segments.reduce((sum, s) => sum + s.value, 0);
  if (total <= 0) return [];
  return props.segments.map((s, i) => ({
    ...s,
    pct: (s.value / total) * 100,
    color: s.color ?? CHART_PALETTE[i] ?? CHART_OTHER_COLOR,
  }));
});

const background = computed(() => {
  if (!resolved.value.length) return 'var(--ui-muted)';
  let acc = 0;
  const stops = resolved.value.map((seg) => {
    const from = acc;
    acc += seg.pct;
    return `${seg.color} ${from.toFixed(2)}% ${acc.toFixed(2)}%`;
  });
  if (acc < 100) stops.push(`#f0f0f0 ${acc.toFixed(2)}% 100%`);
  return `conic-gradient(${stops.join(', ')})`;
});
</script>

<template>
  <div
    class="ui-donut"
    :style="{ width: `${size}px`, height: `${size}px`, background }"
    role="img"
    :aria-label="centerText"
  >
    <span class="ui-donut__hole" />
    <span v-if="centerText" class="ui-donut__center ui-num">{{ centerText }}</span>
  </div>
</template>

<style scoped>
.ui-donut {
  position: relative;
  border-radius: 50%;
  flex-shrink: 0;
}

/* Punch the hole with a centred disc rather than mask-image (crisper edges). */
.ui-donut__hole {
  position: absolute;
  inset: 19%;
  border-radius: 50%;
  background: var(--ui-card);
}

.ui-donut__center {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  z-index: 1;
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}
</style>
