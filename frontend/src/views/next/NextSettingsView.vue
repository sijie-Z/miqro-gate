<script setup lang="ts">
/**
 * NextSettingsView — /app/settings v2 admin page (U3 wrap-up).
 * Behaviour parity with the legacy deploy-info page: static deployment and
 * runtime facts in a definition table plus a health-check note. No API calls.
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { UiButton, UiInput, UiSelect, type UiSelectOption } from '@/ui';
import type { UnattributedPolicyView } from '@/types/api';

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

// ---- #647: unattributed-request policy ----
// Unconfigured tenants keep the fail-closed baseline: a multi-bound key whose
// request cannot be attributed is rejected with 400 CONTEXT_REQUIRED. With a
// policy configured, such requests route via the dedicated credential and are
// accounted to the UNATTRIBUTED bucket project.
const policy = ref<UnattributedPolicyView | null>(null);
const policyCredentials = ref<UiSelectOption[]>([]);
const policyCredentialId = ref('');
const policyModelsText = ref('');
const policySaving = ref(false);
const policyError = ref('');

async function loadPolicy() {
  policyError.value = '';
  try {
    const [current, credentials] = await Promise.all([
      api.getUnattributedPolicy(),
      api.listCredentials(),
    ]);
    policy.value = current;
    policyCredentials.value = credentials
      .filter((c) => c.status === 'ACTIVE')
      .map((c) => ({ value: c.id ?? '', label: c.name ?? c.id ?? '' }));
    policyCredentialId.value = current.credentialId ?? '';
    policyModelsText.value = (current.models ?? []).join(', ');
  } catch (error) {
    policyError.value = error instanceof Error ? error.message : '加载未归属策略失败。';
  }
}

async function savePolicy() {
  if (!policyCredentialId.value) {
    policyError.value = '请先选择凭证。';
    return;
  }
  policySaving.value = true;
  policyError.value = '';
  try {
    policy.value = await api.putUnattributedPolicy({
      credentialId: policyCredentialId.value,
      models: policyModelsText.value
        .split(',')
        .map((m) => m.trim())
        .filter(Boolean),
    });
  } catch (error) {
    policyError.value = error instanceof Error ? error.message : '保存失败。';
  } finally {
    policySaving.value = false;
  }
}

async function clearPolicy() {
  policySaving.value = true;
  policyError.value = '';
  try {
    await api.deleteUnattributedPolicy();
    policy.value = { configured: false };
    policyCredentialId.value = '';
    policyModelsText.value = '';
  } catch (error) {
    policyError.value = error instanceof Error ? error.message : '清除失败。';
  } finally {
    policySaving.value = false;
  }
}

onMounted(() => {
  startedAt.value = new Date().toLocaleString();
  void loadPolicy();
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

    <section class="ui-panel next-settings__panel" data-testid="unattributed-policy">
      <h2 class="next-settings__sub">未归属请求策略</h2>
      <p class="next-settings__hint">
        多项目 Key 的请求在无法判断项目时默认拒绝（400
        CONTEXT_REQUIRED）。配置本策略后，这类请求改走所选凭证，并记账到「未归属」系统项目；建议使用专用凭证（与项目授权资源分离）。
      </p>
      <div v-if="policy" class="next-settings__policy" data-testid="unattributed-policy-state">
        <span class="next-settings__policy-state">
          {{ policy.configured ? '已启用' : '未配置（失败关闭）' }}
          <template v-if="policy.configured && policy.credentialName">
            · 凭证：{{ policy.credentialName }}
            <template v-if="policy.providerProductName"
              >· 产品：{{ policy.providerProductName }}</template
            >
          </template>
        </span>
        <p
          v-if="policy.warning"
          class="next-settings__policy-warning"
          data-testid="unattributed-policy-warning"
        >
          {{ policy.warning }}
        </p>
        <div class="next-settings__policy-form">
          <UiSelect
            v-model="policyCredentialId"
            :options="policyCredentials"
            placeholder="选择凭证（ACTIVE）"
            data-testid="unattributed-policy-credential"
          />
          <UiInput
            v-model="policyModelsText"
            placeholder="模型范围（逗号分隔；留空 = 目录全部）"
            width="280px"
            data-testid="unattributed-policy-models"
          />
          <UiButton
            variant="primary"
            :disabled="policySaving"
            data-testid="unattributed-policy-save"
            @click="savePolicy"
          >
            保存
          </UiButton>
          <UiButton
            v-if="policy.configured"
            variant="secondary"
            :disabled="policySaving"
            data-testid="unattributed-policy-clear"
            @click="clearPolicy"
          >
            清除
          </UiButton>
        </div>
        <p v-if="policyError" class="ui-alert ui-alert--error">{{ policyError }}</p>
      </div>
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
.next-settings__policy {
  margin-top: var(--ui-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-settings__policy-state {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
}

.next-settings__policy-warning {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-warning-fg, #a16207);
}

.next-settings__policy-form {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

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
