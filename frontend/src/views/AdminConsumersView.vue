<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { ApiConsumerView } from '@/types/api';

const consumers = ref<ApiConsumerView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 200 },
  { colKey: 'keyPrefix', title: 'Key 前缀', width: 160 },
  { colKey: 'status', title: '状态', width: 120 },
  { colKey: 'createdAt', title: '创建时间', width: 180 },
  { colKey: 'actions', title: '操作', width: 100, fixed: 'right' },
];

// Create
const creating = ref(false);
const createName = ref('');
const submitting = ref(false);
const formError = ref('');

// One-time key reveal
const reveal = ref(false);
const revealName = ref('');
const revealKey = ref('');

const canCreate = computed(() => createName.value.trim().length > 0);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    consumers.value = await api.listApiConsumers();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载 API 消费者失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function createConsumer() {
  if (!canCreate.value) {
    formError.value = '请输入消费者名称。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    const result = await api.createApiConsumer(createName.value.trim());
    revealName.value = result.consumer.name;
    revealKey.value = result.apiKey;
    reveal.value = true;
    creating.value = false;
    createName.value = '';
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      formError.value = error.message;
    } else {
      formError.value = '创建失败，请稍后重试。';
    }
  } finally {
    submitting.value = false;
  }
}

async function disableConsumer(consumer: ApiConsumerView) {
  try {
    await confirmDialog({
      header: `吊销消费者「${consumer.name}」`,
      body: `吊销后「${consumer.name}」的 API Key 立即失效，平台计费查询将无法访问。此操作不可撤销。`,
      confirmBtn: '吊销',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.disableApiConsumer(consumer.id);
    MessagePlugin.success('消费者已吊销');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

onMounted(load);
</script>

<template>
  <div class="consumers-page">
    <PageHeader
      title="API 消费者"
      description="外部系统（平台）访问计费数据的身份通道；API Key 仅创建时显示一次。"
    >
      <template #actions>
        <t-button theme="primary" data-testid="consumer-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建消费者' }}
        </t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" class="block-alert" data-testid="consumers-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <section v-if="creating" class="create-panel" data-testid="consumer-create-form">
      <h3 class="panel-title">创建 API 消费者</h3>
      <p class="hint">
        创建后立即生成 API Key（仅显示一次，请立即保存）；平台通过
        <span class="mk-mono">X-API-Key</span> 或
        <span class="mk-mono">Authorization: Bearer mqk_api_…</span> 访问计费接口。
      </p>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required-mark>
          <t-input
            v-model="createName"
            placeholder="例如 platform"
            data-testid="consumer-create-name"
          />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <div class="form-actions">
          <t-button
            theme="primary"
            :disabled="!canCreate"
            :loading="submitting"
            data-testid="consumer-create-submit"
            @click="createConsumer"
          >
            创建
          </t-button>
          <t-button @click="creating = false">取消</t-button>
        </div>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        row-key="id"
        size="small"
        :columns="columns"
        :data="consumers"
        class="consumers-table"
        data-testid="consumers-table"
      >
        <template #name="{ row }">
          <span class="consumer-name">{{ row.name }}</span>
        </template>
        <template #keyPrefix="{ row }">
          <span class="mk-mono">{{ row.keyPrefix }}…</span>
        </template>
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.status === 'ACTIVE' ? 'Active' : 'Disabled' }}
          </span>
        </template>
        <template #createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
        <template #actions="{ row }">
          <t-button
            v-if="row.status === 'ACTIVE'"
            variant="text"
            theme="danger"
            data-testid="consumer-disable"
            @click="disableConsumer(row)"
          >
            吊销
          </t-button>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>还没有 API 消费者。</p>
            <p class="hint">创建消费者后，平台即可用 API Key 查询计费数据。</p>
          </div>
        </template>
      </t-table>
    </t-loading>

    <!-- One-time key reveal -->
    <t-dialog
      v-model:visible="reveal"
      header="API Key 已创建"
      width="560px"
      :close-on-overlay-click="false"
      :show-close="false"
    >
      <p class="dialog-hint">
        该 Key
        仅在此显示一次，关闭后无法找回。请立即复制并保存到平台配置中；遗失只能重新创建消费者。
      </p>
      <div class="key-block">
        <div class="key-label">{{ revealName }}</div>
        <div class="key-row">
          <code class="mk-mono key-value" data-testid="consumer-key-value">{{ revealKey }}</code>
          <t-button size="small" @click="navigator.clipboard.writeText(revealKey)">复制</t-button>
        </div>
      </div>
      <template #footer>
        <t-button theme="primary" data-testid="consumer-key-close" @click="reveal = false"
          >关闭</t-button
        >
      </template>
    </t-dialog>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: 760px;
}

.panel-title {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.create-form {
  max-width: 480px;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.form-actions {
  display: flex;
  gap: 8px;
}

.consumers-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.consumer-name {
  font-weight: 500;
}

.dialog-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  line-height: 20px;
}

.key-block {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  padding: 12px;
  background: var(--miqrokey-bg-surface);
}

.key-label {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
  margin-bottom: 4px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.key-value {
  flex: 1;
  padding: 6px 8px;
  background: var(--miqrokey-bg-subtle);
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: 4px;
  overflow-wrap: anywhere;
  user-select: all;
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
