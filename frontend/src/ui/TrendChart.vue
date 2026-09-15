<script setup lang="ts">
/**
 * UiTrendChart — v2 design-system time-series chart (SVG area + line).
 * Flat low-opacity area fill (no gradients, per the aesthetic gate) with a
 * non-scaling stroke line and per-point dots. X labels are rendered as an
 * HTML row beneath the plot so they never distort with viewBox scaling.
 */
import { computed, useAttrs } from 'vue';

export interface UiTrendPoint {
  label: string;
  value: number;
}

const props = withDefaults(
  defineProps<{
    points: UiTrendPoint[];
    /** Line/area color; defaults to the console primary. */
    color?: string;
    height?: number;
    /** Formats axis/dot values (e.g. counts with k/M). */
    valueFormatter?: (value: number) => string;
    /** How many x labels to show below the plot. */
    xLabelCount?: number;
  }>(),
  {
    color: 'var(--ui-primary)',
    height: 180,
    valueFormatter: (value: number) => String(Math.round(value * 100) / 100),
    xLabelCount: 4,
  },
);

defineOptions({ inheritAttrs: false });
const attrs = useAttrs();

const VIEW_W = 600;
const PAD_X = 6;
const PAD_TOP = 12;
const PAD_BOTTOM = 14;

const plot = computed(() => {
  const points = props.points;
  const n = points.length;
  if (n === 0) return null;
  const max = Math.max(...points.map((p) => p.value), 0) || 1;
  const innerH = props.height - PAD_TOP - PAD_BOTTOM;
  const step = n > 1 ? (VIEW_W - PAD_X * 2) / (n - 1) : 0;
  const xy = points.map((p, i) => {
    const x = PAD_X + i * step;
    const y = PAD_TOP + (1 - p.value / max) * innerH;
    return { x, y, label: p.label, value: p.value };
  });
  const line = xy.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
  const area =
    xy.length > 0
      ? `${line} L${xy[xy.length - 1]!.x.toFixed(1)} ${(props.height - PAD_BOTTOM).toFixed(1)} L${xy[0]!.x.toFixed(1)} ${(props.height - PAD_BOTTOM).toFixed(1)} Z`
      : '';
  const grid = [0.25, 0.5, 0.75].map((t) => PAD_TOP + t * innerH);
  const labelIdx =
    n <= props.xLabelCount
      ? xy.map((_, i) => i)
      : Array.from({ length: props.xLabelCount }, (_, k) =>
          Math.round((k * (n - 1)) / (props.xLabelCount - 1)),
        ).filter((v, i, a) => a.indexOf(v) === i);
  const labels = labelIdx.map((i) => xy[i]!);
  const maxPoint = xy.reduce((a, b) => (b.value > a.value ? b : a), xy[0]!);
  return { xy, line, area, grid, labels, maxPoint, max };
});
</script>

<template>
  <div class="ui-trend" v-bind="attrs">
    <template v-if="plot">
      <svg
        class="ui-trend__svg"
        :viewBox="`0 0 ${VIEW_W} ${height}`"
        preserveAspectRatio="none"
        :style="{ height: `${height}px` }"
        role="img"
        :aria-label="`趋势图：峰值 ${valueFormatter(plot.max)}`"
      >
        <line
          v-for="(gy, i) in plot.grid"
          :key="i"
          :x1="0"
          :x2="VIEW_W"
          :y1="gy"
          :y2="gy"
          class="ui-trend__grid"
        />
        <path :d="plot.area" :fill="color" fill-opacity="0.1" stroke="none" />
        <path
          :d="plot.line"
          fill="none"
          :stroke="color"
          stroke-width="2"
          stroke-linejoin="round"
          stroke-linecap="round"
          vector-effect="non-scaling-stroke"
        />
        <template v-if="plot.xy.length <= 40">
          <circle
            v-for="(p, i) in plot.xy"
            :key="i"
            :cx="p.x"
            :cy="p.y"
            r="2.5"
            :fill="color"
          >
            <title>{{ p.label }} · {{ valueFormatter(p.value) }}</title>
          </circle>
        </template>
      </svg>
      <div class="ui-trend__x">
        <span v-for="(p, i) in plot.labels" :key="i" class="ui-trend__x-label">{{
          p.label
        }}</span>
      </div>
    </template>
    <div v-else class="ui-trend__empty" :style="{ height: `${height}px` }">暂无趋势数据</div>
  </div>
</template>

<style scoped>
.ui-trend {
  width: 100%;
}

.ui-trend__svg {
  display: block;
  width: 100%;
  overflow: visible;
}

.ui-trend__grid {
  stroke: var(--ui-border-muted);
  stroke-width: 1;
  vector-effect: non-scaling-stroke;
}

.ui-trend__x {
  display: flex;
  justify-content: space-between;
  margin-top: var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.ui-trend__empty {
  display: grid;
  place-items: center;
  border: 1px dashed var(--ui-border-strong);
  border-radius: var(--ui-radius-control);
  color: var(--ui-foreground-faint);
  font-size: var(--ui-font-size-sm);
}
</style>
