<script setup lang="ts">
/**
 * UiTooltip — hover/focus explanation bubble on radix-vue Tooltip primitives
 * (portal, collision-aware popper). Used to explain status badges and other
 * terse labels (#651). Bubble chrome lives in styles/design-base.css because
 * teleported popper roots drop the scoped data-v attribute (same rule as the
 * Select/Menu surfaces). The slot content becomes the trigger; the wrapper
 * span is focusable so keyboard users get the explanation too.
 */
import {
  TooltipContent,
  TooltipPortal,
  TooltipProvider,
  TooltipRoot,
  TooltipTrigger,
} from 'radix-vue';

withDefaults(
  defineProps<{
    text: string;
    side?: 'top' | 'right' | 'bottom' | 'left';
  }>(),
  {
    side: 'top',
  },
);
</script>

<template>
  <TooltipProvider :delay-duration="120" :skip-delay-duration="200">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <span class="ui-tooltip__anchor" tabindex="0"><slot /></span>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent class="ui-tooltip" :side="side" :side-offset="6">
          {{ text }}
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>

<style scoped>
.ui-tooltip__anchor {
  display: inline-flex;
  border-radius: var(--ui-radius-control);
}

.ui-tooltip__anchor:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}
</style>
