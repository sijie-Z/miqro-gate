<script setup lang="ts">
import { onMounted } from 'vue';
import { RouterView } from 'vue-router';
import { UiToastHost } from '@/ui';
import { installDomI18n } from '@/i18n';
import ErrorBoundary from '@/components/ErrorBoundary.vue';

// App-wide zh⇄en layer (translates the rendered DOM while English is chosen).
onMounted(installDomI18n);
</script>

<template>
  <!-- #833: last line of defence — a crash in any route (including the shell
       itself) renders a recoverable card instead of a blank page. -->
  <ErrorBoundary>
    <RouterView />
  </ErrorBoundary>
  <UiToastHost />
</template>

<style>
html {
  font-family: var(--ui-font);
}

body {
  margin: 0;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>
