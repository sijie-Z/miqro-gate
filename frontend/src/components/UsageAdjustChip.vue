<script setup lang="ts">
/**
 * UsageAdjustChip — the row-level marker for a usage adjustment (#773).
 *
 * The records table reports the *net* counts, so its columns add up to the
 * summary printed above them; this chip is what declares that, and its bubble
 * carries the observed counts those net ones were derived from — the observed
 * fact stays readable instead of being replaced by a silent recalculation.
 *
 * It also covers the case that would otherwise be invisible: a correction and
 * its reversal net out to zero, leaving a row whose numbers are unchanged even
 * though it carries an adjustment.
 */
import { computed } from 'vue';
import { adjustmentNote, type UsageNetFields } from '@/lib/usage-net';
import { UiStatusBadge, UiTooltip } from '@/ui';

const props = defineProps<{ record: UsageNetFields }>();

const note = computed(() => adjustmentNote(props.record));
</script>

<template>
  <UiTooltip v-if="record.adjusted" :text="note">
    <UiStatusBadge tone="info" label="已调整" data-testid="usage-adjust-chip" />
  </UiTooltip>
  <span v-else>—</span>
</template>
