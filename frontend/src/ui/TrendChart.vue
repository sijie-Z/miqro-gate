<script setup lang="ts">
/**
 * UiTrendChart — v2 design-system time-series chart (SVG area + line).
 * Flat low-opacity area fill (no gradients, per the aesthetic gate) with a
 * non-scaling stroke line and per-point dots. X labels are rendered as an
 * HTML row beneath the plot so they never distort with viewBox scaling.
 *
 * Two modes:
 * - legacy: pass {@code points} for a single series;
 * - multi: pass {@code series} for 1..n independently-scaled series (each
 *   normalized to its own peak — e.g. tokens on one scale and cost on
 *   another) with a compact legend.
 */
import { computed, useAttrs } from 'vue';

export interface UiTrendPoint {
  label: string;
  value: number;
}

export interface UiTrendSeries {
  name: string;
  color: string;
  points: UiTrendPoint[];
  /** Area fill (default) or line only. */
  kind?: 'area' | 'line';
}

const props = withDefaults(
  defineProps<{
    points?: UiTrendPoint[];
    /** Multi-series mode; wins over {@code points} when non-empty. */
    series?: UiTrendSeries[];
    /** Line/area color; defaults to the console primary. */
    color?: string;
    height?: number;
    /** Formats axis/dot values (e.g. counts with k/M). */
    valueFormatter?: (value: number) => string;
    /** How many x labels to show below the plot. */
    xLabelCount?: number;
    /**
     * #1065: text for the no-data slot. Pages whose read failed pass a
     * "failed" wording so the chart does not claim the window is empty.
     */
    emptyText?: string;
  }>(),
  {
    points: () => [],
    series: () => [],
    color: 'var(--ui-primary)',
    height: 180,
    valueFormatter: (value: number) => String(Math.round(value * 100) / 100),
    xLabelCount: 4,
    emptyText: '暂无趋势数据',
  },
);

defineOptions({ inheritAttrs: false });
const attrs = useAttrs();

const VIEW_W = 600;
const PAD_X = 6;
const PAD_TOP = 12;
const PAD_BOTTOM = 14;

interface BuiltPlot {
  name: string;
  color: string;
  kind: 'area' | 'line';
  line: string;
  area: string;
  labels: { label: string }[];
  dots: { x: number; y: number; label: string; value: number }[];
  max: number;
}

function buildPlot(
  points: UiTrendPoint[],
  color: string,
  name: string,
  kind: 'area' | 'line',
): BuiltPlot | null {
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
  const line = xy
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`)
    .join(' ');
  const area = `${line} L${xy[n - 1]!.x.toFixed(1)} ${(props.height - PAD_BOTTOM).toFixed(1)} L${xy[0]!.x.toFixed(1)} ${(props.height - PAD_BOTTOM).toFixed(1)} Z`;
  const labelIdx =
    n <= props.xLabelCount
      ? xy.map((_, i) => i)
      : Array.from({ length: props.xLabelCount }, (_, k) =>
          Math.round((k * (n - 1)) / (props.xLabelCount - 1)),
        ).filter((v, i, a) => a.indexOf(v) === i);
  const labels = labelIdx.map((i) => ({ label: xy[i]!.label }));
  return { name, color, kind, line, area, labels, dots: xy, max };
}

const plots = computed<BuiltPlot[]>(() => {
  if (props.series.length > 0) {
    return props.series
      .map((s) => buildPlot(s.points, s.color, s.name, s.kind ?? 'area'))
      .filter((p): p is BuiltPlot => p !== null);
  }
  const single = buildPlot(props.points, props.color, '', 'area');
  return single ? [single] : [];
});

const grid = computed(() => {
  const innerH = props.height - PAD_TOP - PAD_BOTTOM;
  return [0.25, 0.5, 0.75].map((t) => PAD_TOP + t * innerH);
});

const ariaLabel = computed(() => {
  if (plots.value.length === 0) return '趋势图';
  if (props.series.length > 0) {
    return `趋势图：${plots.value.map((p) => p.name).join('、')}`;
  }
  const max = plots.value[0]!.max;
  return `趋势图：峰值 ${props.valueFormatter(max)}`;
});
</script>

<template>
  <div class="ui-trend" v-bind="attrs">
    <template v-if="plots.length > 0">
      <div v-if="series.length > 1" class="ui-trend__legend">
        <span v-for="p in plots" :key="p.name" class="ui-trend__legend-item">
          <span class="ui-trend__legend-chip" :style="{ background: p.color }" />
          {{ p.name }}
        </span>
      </div>
      <svg
        class="ui-trend__svg"
        :viewBox="`0 0 ${VIEW_W} ${height}`"
        preserveAspectRatio="none"
        :style="{ height: `${height}px` }"
        role="img"
        :aria-label="ariaLabel"
      >
        <line
          v-for="(gy, i) in grid"
          :key="i"
          :x1="0"
          :x2="VIEW_W"
          :y1="gy"
          :y2="gy"
          class="ui-trend__grid"
        />
        <template v-for="p in plots" :key="p.name || 'series'">
          <path
            v-if="p.kind === 'area'"
            :d="p.area"
            :fill="p.color"
            fill-opacity="0.1"
            stroke="none"
          />
          <path
            :d="p.line"
            fill="none"
            :stroke="p.color"
            stroke-width="2"
            stroke-linejoin="round"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
          <template v-if="p.dots.length <= 40">
            <circle v-for="(d, i) in p.dots" :key="i" :cx="d.x" :cy="d.y" r="2.5" :fill="p.color">
              <title>
                {{ p.name ? `${p.name} · ` : '' }}{{ d.label }} · {{ valueFormatter(d.value) }}
              </title>
            </circle>
          </template>
        </template>
      </svg>
      <div class="ui-trend__x">
        <span v-for="(p, i) in plots[0]!.labels" :key="i" class="ui-trend__x-label">{{
          p.label
        }}</span>
      </div>
    </template>
    <div v-else class="ui-trend__empty" :style="{ height: `${height}px` }">{{ emptyText }}</div>
  </div>
</template>

<style scoped>
.ui-trend {
  width: 100%;
}

.ui-trend__legend {
  display: flex;
  justify-content: flex-end;
  gap: var(--ui-space-4, 16px);
  margin-bottom: var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-muted, var(--ui-foreground-faint));
}

.ui-trend__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.ui-trend__legend-chip {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
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
