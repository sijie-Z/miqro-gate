<script setup lang="ts">
/**
 * UiDonut — pure-CSS share chart (Vben analysis “成交占比” pattern).
 * Renders a conic-gradient ring with a centred total; consumers render their
 * own legend. Palette follows frontend-design.md §4 (blue/cyan/orange/gray —
 * no rainbow), and the donut is one of the two sanctioned gradient surfaces.
 */
import { computed } from 'vue';

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

const PALETTE = ['#0960bd', '#69c0ff', '#13c2c2', '#fa8c16', '#8c8c8c', '#d9d9d9'];

const resolved = computed(() => {
  const total = props.segments.reduce((sum, s) => sum + s.value, 0);
  if (total <= 0) return [];
  return props.segments.map((s, i) => ({
    ...s,
    pct: (s.value / total) * 100,
    color: s.color ?? PALETTE[i % PALETTE.length],
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
