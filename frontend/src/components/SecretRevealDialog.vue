<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';

interface Props {
  modelValue: boolean;
  /** Base URL for CC Switch configuration. */
  baseUrl: string;
  /** Plaintext Virtual Key — in memory only, never persisted. */
  secret: string;
  display: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>();

const acknowledged = ref(false);

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      acknowledged.value = false;
    }
  },
);

async function copy(text: string) {
  await navigator.clipboard.writeText(text);
  ElMessage.success('已复制');
}

function close() {
  emit('update:modelValue', false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    width="520px"
    title="Virtual Key 已创建"
    class="secret-dialog"
    @close="close"
  >
    <p class="warning">
      该 Key
      仅在此显示一次，关闭后无法再次查看。请立即复制并保存到密码管理器；遗失后只能轮换或重新创建。
    </p>

    <div class="secret-block">
      <div class="secret-label">Base URL</div>
      <div class="secret-row">
        <code class="mk-mono secret-value" data-testid="secret-base-url">{{ baseUrl }}</code>
        <el-button size="small" @click="copy(baseUrl)">复制</el-button>
      </div>
      <div class="secret-label">Virtual Key</div>
      <div class="secret-row">
        <code class="mk-mono secret-value" data-testid="secret-value">{{ secret }}</code>
        <el-button size="small" @click="copy(secret)">复制</el-button>
      </div>
    </div>

    <el-checkbox v-model="acknowledged" class="ack" data-testid="secret-ack">
      我已保存 Virtual Key
    </el-checkbox>

    <template #footer>
      <el-button type="primary" :disabled="!acknowledged" data-testid="secret-close" @click="close">
        关闭
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.warning {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--miqrokey-danger);
  line-height: 20px;
}

.secret-block {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  padding: 12px;
  background: var(--miqrokey-bg-surface);
}

.secret-label {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
  margin-bottom: 4px;
}

.secret-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.secret-row:last-child {
  margin-bottom: 0;
}

.secret-value {
  flex: 1;
  padding: 6px 8px;
  background: var(--miqrokey-bg-subtle);
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: 4px;
  color: var(--miqrokey-text-primary);
  overflow-wrap: anywhere;
  user-select: all;
}

.ack {
  margin-top: 16px;
}
</style>
