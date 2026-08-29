<script setup lang="ts">
import { onMounted, ref } from 'vue';

const version = '0.1.0';
const catalogVersion = 'v1';
const startedAt = ref<string | null>(null);

const infoRows = [
  { label: '产品', value: 'MiQroGate' },
  { label: '版本', value: version },
  { label: '签名目录', value: catalogVersion },
  { label: '部署方式', value: 'Docker Compose（单节点私有化）' },
  { label: '控制面端口', value: '8080（管理 API）' },
  { label: '网关端口', value: '8081（推理流量）' },
  { label: '数据库', value: 'PostgreSQL 17（AES-256-GCM 加密凭证存储）' },
  { label: '响应缓存', value: '默认关闭（MIQROKEY_CACHE_ENABLED=false；按 Key 显式开启）' },
  { label: '日志', value: 'JSON 结构化日志；不记录 prompt 与模型回答' },
];

onMounted(() => {
  startedAt.value = new Date().toLocaleString();
});
</script>

<template>
  <div class="deploy-page">
    <header class="mk-page-header">
      <h1 class="mk-page-title" data-testid="page-title">部署信息</h1>
      <p class="mk-page-description">网关实例的基本信息与运行配置。私有化部署由客户侧运维。</p>
    </header>

    <section class="deploy-panel" data-testid="deploy-info">
      <table class="info-table">
        <tbody>
          <tr v-for="row in infoRows" :key="row.label">
            <th class="info-label">{{ row.label }}</th>
            <td class="info-value">{{ row.value }}</td>
          </tr>
          <tr>
            <th class="info-label">门户启动时间</th>
            <td class="info-value mk-num">{{ startedAt ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="deploy-panel">
      <h3 class="panel-title">健康检查</h3>
      <p class="hint">
        网关与控制面分别暴露 <span class="mk-mono">/actuator/health</span>；监控指标仅
        <span class="mk-mono">monitoring</span> profile 下可用（Prometheus + Grafana）。
      </p>
    </section>
  </div>
</template>

<style scoped>
.deploy-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px;
  margin-bottom: 16px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  line-height: 20px;
}

.info-table {
  width: 100%;
  border-collapse: collapse;
}

.info-table tr {
  border-bottom: 1px solid var(--miqrokey-border-muted);
}

.info-table tr:last-child {
  border-bottom: none;
}

.info-label {
  text-align: left;
  font-weight: 500;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  padding: 10px 16px 10px 0;
  white-space: nowrap;
  width: 140px;
  vertical-align: top;
}

.info-value {
  font-size: 13px;
  color: var(--miqrokey-text-primary);
  padding: 10px 0;
}
</style>
