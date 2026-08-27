<script setup lang="ts">
/**
 * Page header region: title + description + primary action in one row
 * (frontend-design.md §5). Dangerous actions must not sit next to the
 * primary action — use the danger zone instead.
 */
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <div class="mk-page-header">
    <div>
      <h1 class="mk-page-title" data-testid="page-title">{{ title }}</h1>
      <p v-if="description" class="mk-page-description">{{ description }}</p>
    </div>
    <div class="mk-page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>
