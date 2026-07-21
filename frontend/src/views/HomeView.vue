<script setup lang="ts">
import { ElMessage } from 'element-plus';

function handleClick() {
  ElMessage.success('MiQroKey Gateway is running');
}
</script>

<template>
  <div class="home">
    <header class="home-header">
      <h1>MiQroKey Gateway</h1>
      <p class="subtitle">Quiet Operations Console</p>
    </header>
    <main class="home-main">
      <el-button type="primary" @click="handleClick">Hello</el-button>
    </main>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 2rem;
}

.home-header {
  text-align: center;
  margin-bottom: 2rem;
}

.home-header h1 {
  font-size: 2rem;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin: 0;
}

.subtitle {
  color: var(--el-text-color-secondary);
  margin-top: 0.5rem;
}

.home-main {
  display: flex;
  gap: 1rem;
}
</style>
