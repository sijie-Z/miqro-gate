<script setup lang="ts">
/**
 * UiTooltip — hover/focus explanation bubble on radix-vue Tooltip primitives
 * (portal, collision-aware popper). Used to explain status badges and the
 * collapsed rail's icon-only nav items (#651/#655). Bubble chrome lives in
 * styles/design-base.css because teleported popper roots drop the scoped
 * data-v attribute (same rule as the Select/Menu surfaces).
 *
 * Trigger modes:
 * - default: the slot is wrapped in a focusable span, so non-interactive
 *   content (badges) still gets keyboard access to the bubble.
 * - asChild: the trigger merges onto the slot element itself — use it when
 *   the slotted element is already focusable (router-link, button), so no
 *   duplicate tab stop is introduced.
 * `disabled` keeps the slot rendered but makes the tooltip fully inert (used
 * by the rail when the sidebar is expanded and labels are visible).
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
    disabled?: boolean;
    asChild?: boolean;
  }>(),
  {
    side: 'top',
    disabled: false,
    asChild: false,
  },
);
</script>

<template>
  <TooltipProvider :delay-duration="120" :skip-delay-duration="200">
    <TooltipRoot :disabled="disabled">
      <TooltipTrigger as-child>
        <span v-if="!asChild" class="ui-tooltip__anchor" tabindex="0"><slot /></span>
        <slot v-else />
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
