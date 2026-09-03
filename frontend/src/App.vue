<script setup lang="ts">
import { RouterView } from 'vue-router';
import { UiToastHost } from '@/ui';
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
