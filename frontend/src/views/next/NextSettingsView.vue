<script setup lang="ts">
/**
 * NextSettingsView — /app/settings v2 admin page (U3 wrap-up).
 * Behaviour parity with the legacy deploy-info page: static deployment and
 * runtime facts in a definition table plus a health-check note. No API calls.
 */
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
  <div class="ui-page next-settings">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title" data-testid="page-title">部署信息</h1>
        <p class="ui-page-desc">网关实例的基本信息与运行配置。私有化部署由客户侧运维。</p>
      </div>
    </header>

    <section class="ui-panel next-settings__panel" data-testid="deploy-info">
      <table class="next-settings__table">
        <tbody>
          <tr v-for="row in infoRows" :key="row.label" class="next-settings__row">
            <th class="next-settings__label" scope="row">{{ row.label }}</th>
            <td class="next-settings__value">{{ row.value }}</td>
          </tr>
          <tr class="next-settings__row">
            <th class="next-settings__label" scope="row">门户启动时间</th>
            <td class="next-settings__value ui-num">{{ startedAt ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="ui-panel next-settings__panel">
      <h2 class="next-settings__sub">健康检查</h2>
      <p class="next-settings__hint">
        网关与控制面分别暴露 <span class="ui-mono">/actuator/health</span>；监控指标仅
        <span class="ui-mono">monitoring</span> profile 下可用（Prometheus + Grafana）。
      </p>
    </section>
  </div>
</template>

<style scoped>
.next-settings__panel {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
  padding: var(--ui-space-4) var(--ui-space-5);
}

.next-settings__table {
  width: 100%;
  border-collapse: collapse;
}

.next-settings__row {
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-settings__row:last-child {
  border-bottom: none;
}

.next-settings__label {
  text-align: left;
  font-weight: var(--ui-weight-medium);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  padding: var(--ui-space-2) var(--ui-space-4) var(--ui-space-2) 0;
  white-space: nowrap;
  width: 150px;
  vertical-align: top;
}

.next-settings__value {
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
  padding: var(--ui-space-2) 0;
  overflow-wrap: anywhere;
}

.next-settings__sub {
  margin: 0 0 var(--ui-space-2);
  font-size: 15px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-settings__hint {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-lg);
}
</style>
