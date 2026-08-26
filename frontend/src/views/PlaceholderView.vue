<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '@/components/PageHeader.vue';

const route = useRoute();
const title = computed(() => String(route.meta.title ?? ''));
</script>

<template>
  <PageHeader :title="title" description="该模块的页面在后续 Goal 落地；导航与安全边界已就绪。" />
</template>
