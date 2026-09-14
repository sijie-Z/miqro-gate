<script setup lang="ts">
import { onMounted } from 'vue';
import { RouterView } from 'vue-router';
import { UiToastHost } from '@/ui';
import { installDomI18n } from '@/i18n';

// App-wide zh⇄en layer (translates the rendered DOM while English is chosen).
onMounted(installDomI18n);
</script>

<template>
  <RouterView />
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
