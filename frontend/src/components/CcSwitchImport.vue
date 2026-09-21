<script setup lang="ts">
/**
 * CcSwitchImport — 一键导入 CC Switch（目标应用 + 结果反馈闭环）。
 *
 * CC Switch 的深链是单向的：无论用户是否在其确认窗里点了「导入」，浏览器
 * 都拿不到回执。所以这里给三层反馈，而不是假装知道结果：
 *   1. 唤起检测（blur 启发式）：2.5 秒内窗口失焦 ⇒ 「已唤起 CC Switch」，
 *      超时未失焦 ⇒ 「未检测到唤起」并给出未安装/被拦截两种最可能的原因；
 *   2. 分步指引：确认窗 → 对应应用分组查看 → 列表未刷新时重启 CC Switch；
 *   3. 兜底：复制导入链接（浏览器地址栏手动触发）+ 冷却防连点（每确认一次
 *      CC Switch 会新增一条供应商记录，重复发送只会造成重复条目）。
 */
import { computed, onBeforeUnmount, ref } from 'vue';
import { UiButton, UiInput, toast } from '@/ui';
import {
  CCSWITCH_APP_LABEL,
  ccSwitchImportLink,
  launchImportLink,
  type CcSwitchApp,
} from '@/lib/ccswitch';

const props = withDefaults(
  defineProps<{
    secret: string;
    keyName: string;
    baseUrl: string;
    model?: string;
    defaultApp?: CcSwitchApp;
    /** The create-success flow already holds the plaintext; the row dialog pastes one. */
    showSecretInput?: boolean;
    /** Test-id base: the import button itself carries exactly this id. */
    importTestId: string;
  }>(),
  {
    model: '',
    defaultApp: 'claude',
    showSecretInput: true,
  },
);

const emit = defineEmits<{ 'update:secret': [value: string] }>();

const APPS: CcSwitchApp[] = ['claude', 'codex'];

const app = ref<CcSwitchApp>(props.defaultApp);
type Phase = 'idle' | 'sent' | 'opened' | 'silent';
const phase = ref<Phase>('idle');
/** 冷却期：发送后短暂禁用按钮，避免连点造成重复导入。 */
const cooling = ref(false);

/** 等待唤起检测的最长时间；超过即判定为「未检测到」。 */
const WAKE_UP_WINDOW_MS = 2500;
const COOLDOWN_MS = 4000;

const secretModel = computed({
  get: () => props.secret,
  set: (value: string) => emit('update:secret', value),
});

const canSend = computed(() => secretModel.value.trim().length > 0);

const appHint = computed(() =>
  app.value === 'codex'
    ? `将导入为 CC Switch 的「Codex」供应商（CC Switch 固定生成 wire_api = "responses" 形态）——适用于上游产品支持 OpenAI Responses 的场景；若你的产品仅支持 Chat Completions，请改用下方手动配置的 Codex 片段。`
    : `将导入为 CC Switch 的「Claude Code」供应商，网关地址与密钥自动填入，无需手抄。`,
);

const currentAppLabel = computed(() => CCSWITCH_APP_LABEL[app.value]);

/**
 * Guidance steps as whole strings (one text node each) so the zh→en phrase
 * table can translate them — interpolated template text would split nodes.
 */
const stepsOpened = computed(() => [
  '在 CC Switch 弹出的「导入确认」窗中点确认（密钥显示为掩码属正常）。',
  `打开 CC Switch 的「${currentAppLabel.value}」分组，即可看到新增的「MiQroKey · ${props.keyName}」。`,
  '若列表没出现，完全退出并重开 CC Switch 再看。',
]);

const stepsSilent = [
  '可能未安装 CC Switch，或浏览器拦截了 ccswitch:// 跳转。',
  '可点「复制导入链接」，粘贴到浏览器地址栏手动触发。',
  '或直接使用下方「手动配置」，把片段贴进对应客户端配置文件。',
];

const buttonLabel = computed(() => {
  if (phase.value === 'idle') return '导入到 CC Switch';
  if (cooling.value) return '已发送…';
  return '重新发送导入请求';
});

const importButtonTestId = computed(() => props.importTestId);

function buildLink(): string {
  return ccSwitchImportLink({
    secret: secretModel.value.trim(),
    keyName: props.keyName,
    baseUrl: props.baseUrl,
    model: props.model,
    app: app.value,
  });
}

let blurHandler: (() => void) | null = null;
let wakeTimer: ReturnType<typeof setTimeout> | null = null;
let cooldownTimer: ReturnType<typeof setTimeout> | null = null;

function detachWakeUpWatch() {
  if (blurHandler) {
    window.removeEventListener('blur', blurHandler);
    blurHandler = null;
  }
  if (wakeTimer) {
    clearTimeout(wakeTimer);
    wakeTimer = null;
  }
}

function send() {
  if (!canSend.value || cooling.value) return;
  const link = buildLink();
  phase.value = 'sent';
  try {
    launchImportLink(link);
  } catch {
    // A blocked protocol jump is indistinguishable from "no handler installed";
    // fall through to the same not-detected guidance.
    phase.value = 'silent';
    return;
  }
  // Wake-up heuristic: handing off to a native app blurs the page. It is
  // advisory only — an alt-tab inside the window produces the same signal.
  blurHandler = () => {
    if (phase.value === 'sent') {
      phase.value = 'opened';
    }
    detachWakeUpWatch();
  };
  window.addEventListener('blur', blurHandler, { once: true });
  wakeTimer = setTimeout(() => {
    if (phase.value === 'sent') {
      phase.value = 'silent';
    }
    detachWakeUpWatch();
  }, WAKE_UP_WINDOW_MS);

  cooling.value = true;
  if (cooldownTimer) clearTimeout(cooldownTimer);
  cooldownTimer = setTimeout(() => {
    cooling.value = false;
  }, COOLDOWN_MS);
}

async function copyLink() {
  if (!canSend.value) {
    toast.error('请先粘贴明文密钥');
    return;
  }
  try {
    await navigator.clipboard.writeText(buildLink());
    toast.success('导入链接已复制');
  } catch {
    toast.error('复制失败，请手动选择复制');
  }
}

onBeforeUnmount(() => {
  detachWakeUpWatch();
  if (cooldownTimer) clearTimeout(cooldownTimer);
});
</script>

<template>
  <div class="ccswitch-import" data-testid="ccswitch-import">
    <div
      class="ccswitch-import__apps"
      role="radiogroup"
      aria-label="导入目标应用"
      :data-testid="`${importTestId}-app-select`"
    >
      <label
        v-for="option in APPS"
        :key="option"
        class="ccswitch-import__app"
        :class="{ 'ccswitch-import__app--on': app === option }"
      >
        <input
          v-model="app"
          type="radio"
          :name="`ccswitch-app-${importTestId}`"
          :value="option"
          class="ccswitch-import__app-input"
          :data-testid="`${importTestId}-app-${option}`"
        />
        <span>{{ CCSWITCH_APP_LABEL[option] }}</span>
      </label>
    </div>
    <p class="ccswitch-import__hint" data-testid="ccswitch-import-app-hint">{{ appHint }}</p>

    <UiInput
      v-if="showSecretInput"
      v-model="secretModel"
      label="明文密钥"
      placeholder="mqk_live_…（创建时只显示一次；遗失请先轮换）"
      :data-testid="`${importTestId}-secret-input`"
    />

    <div class="ccswitch-import__actions">
      <UiButton
        variant="primary"
        :disabled="!canSend || cooling"
        :data-testid="importButtonTestId"
        @click="send"
      >
        {{ buttonLabel }}
      </UiButton>
      <UiButton
        variant="secondary"
        :disabled="!canSend"
        :data-testid="`${importTestId}-copy-link`"
        @click="copyLink"
      >
        复制导入链接
      </UiButton>
    </div>

    <div
      v-if="phase !== 'idle'"
      class="ccswitch-import__feedback"
      :class="{
        'ccswitch-import__feedback--wait': phase === 'sent',
        'ccswitch-import__feedback--ok': phase === 'opened',
        'ccswitch-import__feedback--miss': phase === 'silent',
      }"
      :data-testid="`${importTestId}-feedback`"
    >
      <p v-if="phase === 'sent'" class="ccswitch-import__feedback-title">正在唤起 CC Switch…</p>
      <template v-else-if="phase === 'opened'">
        <p class="ccswitch-import__feedback-title">已唤起 CC Switch ✓</p>
        <ol class="ccswitch-import__feedback-steps">
          <li v-for="step in stepsOpened" :key="step">{{ step }}</li>
        </ol>
      </template>
      <template v-else>
        <p class="ccswitch-import__feedback-title">未检测到 CC Switch 被唤起</p>
        <ul class="ccswitch-import__feedback-steps">
          <li v-for="step in stepsSilent" :key="step">{{ step }}</li>
        </ul>
      </template>
      <p class="ccswitch-import__feedback-note">
        每确认一次导入会在 CC Switch 中新增一条供应商记录，请勿重复点击。
      </p>
    </div>
  </div>
</template>

<style scoped>
.ccswitch-import {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.ccswitch-import__apps {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
  width: fit-content;
}

.ccswitch-import__app {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 112px;
  height: 30px;
  padding: 0 var(--ui-space-3);
  border-radius: calc(var(--ui-radius-control) - 2px);
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  transition:
    color var(--ui-ease),
    background-color var(--ui-ease);
}

.ccswitch-import__app:hover {
  color: var(--ui-foreground);
}

.ccswitch-import__app--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

.ccswitch-import__app-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.ccswitch-import__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.ccswitch-import__actions {
  display: flex;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
}

.ccswitch-import__feedback {
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  border: 1px solid var(--ui-border-muted);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ccswitch-import__feedback--wait {
  background: var(--ui-muted);
  color: var(--ui-foreground-secondary);
}

.ccswitch-import__feedback--ok {
  background: var(--ui-success-bg);
  border-color: transparent;
  color: var(--ui-success-fg);
}

.ccswitch-import__feedback--miss {
  background: var(--ui-warning-bg, var(--ui-muted));
  border-color: transparent;
  color: var(--ui-warning-fg, var(--ui-foreground));
}

.ccswitch-import__feedback-title {
  margin: 0 0 var(--ui-space-2);
  font-weight: var(--ui-weight-semibold);
}

.ccswitch-import__feedback-steps {
  margin: 0;
  padding-left: 1.2em;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ccswitch-import__feedback-note {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-xs);
  opacity: 0.85;
}
</style>
