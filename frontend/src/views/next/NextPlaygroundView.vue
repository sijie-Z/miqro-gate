<script setup lang="ts">
/**
 * NextPlaygroundView — 试调台 (#1201). Paste a Virtual Key (held in memory
 * only, never persisted), read the models the key may call through the
 * gateway's own {@code GET /v1/models}, then send one real chat call to
 * {@code /v1/chat/completions}. The call is ordinary traffic: it is metered,
 * audited and quota-counted exactly like any client's request — only the
 * prompt and reply stay in this page's memory.
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import * as api from '@/api';
import { UiButton, UiInput, UiPageGuide, UiSelect, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { PlazaModel } from '@/types/generated-api';
import { PLAYGROUND_GUIDE } from '@/content/pageGuides';

const route = useRoute();
const router = useRouter();

/** The pasted key: page-memory only — never stored, never logged. */
const virtualKey = ref('');
const showKey = ref(false);
const models = ref<string[]>([]);
const modelsError = ref('');
const loadingModels = ref(false);
const model = ref('');
const prompt = ref('');
const sending = ref(false);
const reply = ref('');
const callError = ref('');
const meta = ref<{
  latencyMs: number;
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  estimatedCost?: string;
} | null>(null);

/** Price lookup for the cost estimate; failure only drops the estimate. */
const plazaModels = ref<PlazaModel[]>([]);

const modelOptions = computed<UiSelectOption[]>(() =>
  models.value.map((id) => ({ value: id, label: id })),
);

const pendingModel = typeof route.query.model === 'string' ? route.query.model : '';

async function loadModels() {
  const key = virtualKey.value.trim();
  if (!key) {
    modelsError.value = '请先粘贴 Virtual Key';
    return;
  }
  loadingModels.value = true;
  modelsError.value = '';
  try {
    const res = await fetch('/v1/models', {
      headers: { Authorization: `Bearer ${key}` },
    });
    if (!res.ok) {
      modelsError.value = await errorText(res);
      models.value = [];
      return;
    }
    const body = (await res.json()) as { data?: Array<{ id?: string }> };
    models.value = (body.data ?? []).map((entry) => entry.id ?? '').filter(Boolean);
    if (models.value.length === 0) {
      modelsError.value = '这把密钥当前没有可调用的模型（授权范围为空或已停用）。';
    } else if (pendingModel && models.value.includes(pendingModel)) {
      model.value = pendingModel;
    } else if (!models.value.includes(model.value)) {
      model.value = models.value[0] ?? '';
    }
  } catch {
    modelsError.value = '无法连接网关（/v1/models 不可达）。';
    models.value = [];
  } finally {
    loadingModels.value = false;
  }
}

async function send() {
  const key = virtualKey.value.trim();
  callError.value = '';
  if (!key) {
    callError.value = '请先粘贴 Virtual Key 并读取可用模型。';
    return;
  }
  if (!model.value) {
    callError.value = '请选择模型。';
    return;
  }
  if (!prompt.value.trim()) {
    callError.value = '请输入要发送的内容。';
    return;
  }
  sending.value = true;
  reply.value = '';
  meta.value = null;
  const started = performance.now();
  try {
    const res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${key}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: model.value,
        messages: [{ role: 'user', content: prompt.value.trim() }],
        max_tokens: 512,
      }),
    });
    const latencyMs = Math.round(performance.now() - started);
    if (!res.ok) {
      callError.value = await errorText(res);
      return;
    }
    const body = (await res.json()) as {
      choices?: Array<{ message?: { content?: string | null; reasoning_content?: string | null } }>;
      usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
    };
    const message = body.choices?.[0]?.message;
    const content = message?.content ?? null;
    if (content === null) {
      reply.value = message?.reasoning_content
        ? '（模型仅返回思考内容，无可见回复）'
        : '（上游未返回可显示的文本内容）';
    } else {
      reply.value = content;
    }
    const usage = body.usage;
    meta.value = {
      latencyMs,
      inputTokens: usage?.prompt_tokens,
      outputTokens: usage?.completion_tokens,
      totalTokens: usage?.total_tokens,
      estimatedCost: estimateCost(usage?.prompt_tokens, usage?.completion_tokens),
    };
  } catch {
    callError.value = '请求未到达网关（网络错误）。';
  } finally {
    sending.value = false;
  }
}

/** Extracts a human message from the gateway's {"error":{...}} envelope (or HTTP fallback). */
async function errorText(res: Response): Promise<string> {
  let message = '';
  let type = '';
  try {
    const body = (await res.json()) as { error?: { message?: string; type?: string } };
    message = body.error?.message ?? '';
    type = body.error?.type ?? '';
  } catch {
    // Non-JSON error body — fall back to the status line.
  }
  const base = message || `HTTP ${res.status} ${res.statusText}`;
  const suffix = type ? `（${type}）` : '';
  const retryAfter = res.headers.get('Retry-After');
  if (res.status === 429 && retryAfter) {
    return `${base}${suffix}，请 ${retryAfter} 秒后重试`;
  }
  return `${base}${suffix}`;
}

/** Estimated cost from this page's plaza prices; undefined when either side is missing. */
function estimateCost(inputTokens?: number, outputTokens?: number): string | undefined {
  const entry = plazaModels.value.find((m) => m.modelId === model.value);
  const price = entry?.price;
  if (!price || inputTokens === undefined || outputTokens === undefined) return undefined;
  const inputPrice = price.inputPerMillion;
  const outputPrice = price.outputPerMillion;
  if (
    inputPrice === null ||
    inputPrice === undefined ||
    outputPrice === null ||
    outputPrice === undefined
  ) {
    return undefined;
  }
  const cost = (inputTokens * Number(inputPrice) + outputTokens * Number(outputPrice)) / 1_000_000;
  const text = cost >= 0.0001 ? cost.toFixed(4) : cost.toExponential(2);
  return price.currency ? `${text} ${price.currency}` : text;
}

function clearKey() {
  virtualKey.value = '';
  models.value = [];
  model.value = '';
  modelsError.value = '';
}

onMounted(async () => {
  try {
    plazaModels.value = (await api.getPlazaModels()).models ?? [];
  } catch {
    // Estimate only — the page works without it.
  }
  if (typeof route.query.key === 'string') {
    toast.info?.('出于安全考虑，密钥不会通过链接带入——请在「我的密钥」重新复制后粘贴。');
  }
});
</script>

<template>
  <div class="ui-page next-playground">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">试调台</h1>
        <p class="ui-page-desc">
          粘贴你的 Virtual
          Key，选一个模型真实调用一次。密钥只保存在本页内存、不写日志；调用与正常流量同规计费、审计并计入配额。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="ghost"
          data-testid="playground-go-plaza"
          @click="router.push('/app/plaza')"
        >
          返回模型广场
        </UiButton>
      </div>
    </header>

    <UiPageGuide :guide="PLAYGROUND_GUIDE" storage-key="playground" />

    <section class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">1 · 接入</h2>
      </div>
      <div class="ui-panel-body next-pg__row">
        <UiInput
          v-model="virtualKey"
          :type="showKey ? 'text' : 'password'"
          label="Virtual Key"
          placeholder="mqk_live_…"
          width="320px"
          autocomplete="off"
          data-testid="playground-key"
        />
        <UiButton variant="ghost" @click="showKey = !showKey">
          {{ showKey ? '隐藏' : '显示' }}
        </UiButton>
        <UiButton
          variant="primary"
          :loading="loadingModels"
          data-testid="playground-load-models"
          @click="loadModels"
        >
          读取可用模型
        </UiButton>
        <UiButton v-if="virtualKey || models.length" variant="ghost" @click="clearKey"
          >清除</UiButton
        >
      </div>
      <div
        v-if="modelsError"
        class="ui-alert ui-alert--error next-pg__inline-alert"
        data-testid="playground-models-error"
      >
        {{ modelsError }}
      </div>
      <div v-else-if="models.length" class="next-pg__hint" data-testid="playground-models-count">
        已识别 {{ models.length }} 个可用模型。
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">2 · 发送</h2>
      </div>
      <div class="ui-panel-body next-pg__form">
        <UiSelect
          v-model="model"
          label="模型"
          placeholder="先读取可用模型"
          :options="modelOptions"
          width="320px"
          data-testid="playground-model"
        />
        <div class="ui-field">
          <label class="ui-field__label" for="playground-prompt">内容</label>
          <textarea
            id="playground-prompt"
            v-model="prompt"
            class="ui-textarea"
            rows="5"
            maxlength="8000"
            placeholder="写一句话试试，例如：用一句话解释什么是 API 网关。"
            data-testid="playground-prompt"
          />
        </div>
        <div class="next-pg__row">
          <UiButton
            variant="primary"
            :loading="sending"
            :disabled="!model || !prompt.trim()"
            data-testid="playground-send"
            @click="send"
          >
            发送
          </UiButton>
          <span class="next-pg__hint">试调用最多生成 512 tokens（stream=false）。</span>
        </div>
      </div>
    </section>

    <section v-if="callError || reply || meta" class="ui-panel">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">3 · 结果</h2>
      </div>
      <div class="ui-panel-body">
        <div v-if="callError" class="ui-alert ui-alert--error" data-testid="playground-error">
          {{ callError }}
        </div>
        <template v-else>
          <pre class="next-pg__reply" data-testid="playground-reply">{{ reply }}</pre>
          <div v-if="meta" class="next-pg__meta" data-testid="playground-meta">
            <span>延迟 {{ meta.latencyMs }} ms</span>
            <span v-if="meta.inputTokens !== undefined">输入 {{ meta.inputTokens }} tokens</span>
            <span v-if="meta.outputTokens !== undefined">输出 {{ meta.outputTokens }} tokens</span>
            <span v-if="meta.totalTokens !== undefined">合计 {{ meta.totalTokens }}</span>
            <span v-if="meta.estimatedCost">估算成本 ≈ {{ meta.estimatedCost }}</span>
            <span class="next-pg__meta-note">实际以用量报表为准</span>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.next-pg__row {
  display: flex;
  align-items: flex-end;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
}

.next-pg__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 760px;
}

.next-pg__hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-pg__inline-alert {
  margin-top: var(--ui-space-3);
}

.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.ui-textarea {
  width: 100%;
  min-height: 110px;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-pg__reply {
  margin: 0;
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  white-space: pre-wrap;
  word-break: break-word;
}

.next-pg__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-muted);
}

.next-pg__meta-note {
  color: var(--ui-foreground-faint);
}
</style>
