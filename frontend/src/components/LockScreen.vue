<script setup lang="ts">
/**
 * LockScreen — full-viewport lock overlay (Vben 自动锁屏). Rendered by the
 * shell when the user locks manually (user menu) or after the idle timeout
 * chosen in the settings drawer. Unlocking re-verifies the current password
 * through the regular auth store, so no dedicated endpoint is needed.
 *
 * Visual language mirrors the login hero (deep navy scene + grid overlay),
 * which is the sanctioned gradient surface for auth-adjacent screens.
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { LockOnIcon, LogoutIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

const props = defineProps<{ username?: string }>();

const emit = defineEmits<{ unlock: []; logout: [] }>();

const auth = useAuthStore();

const password = ref('');
const errorMessage = ref('');
const submitting = ref(false);
const inputRef = ref<HTMLInputElement | null>(null);

const initial = computed(() => (props.username ?? '?').slice(0, 1).toUpperCase());

// ---- clock (ticks once a second) ----
const now = ref(new Date());
let clockTimer: number | undefined;

const clock = computed(() => {
  const pad = (n: number) => String(n).padStart(2, '0');
  const d = now.value;
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
});

const clockDate = computed(() =>
  now.value.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }),
);

onMounted(async () => {
  clockTimer = window.setInterval(() => {
    now.value = new Date();
  }, 1000);
  // The card is teleported; wait a tick so the input exists before focusing.
  await nextTick();
  inputRef.value?.focus();
});

onUnmounted(() => {
  window.clearInterval(clockTimer);
});

async function unlock() {
  if (submitting.value) return;
  if (!password.value) {
    errorMessage.value = '请输入密码。';
    return;
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    await auth.login(props.username ?? auth.user?.username ?? '', password.value);
    emit('unlock');
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '解锁失败，请重试。';
    password.value = '';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="lock-screen" data-testid="lock-screen">
      <div class="lock-screen__grid" aria-hidden="true" />

      <div class="lock-screen__clock">
        <p class="lock-screen__time ui-num">{{ clock }}</p>
        <p class="lock-screen__date">{{ clockDate }}</p>
      </div>

      <div class="lock-screen__card">
        <span class="lock-screen__avatar" aria-hidden="true">{{ initial }}</span>
        <p class="lock-screen__user">{{ username }}</p>

        <form class="lock-screen__form" @submit.prevent="unlock">
          <span class="lock-screen__input-wrap">
            <LockOnIcon class="lock-screen__input-icon" />
            <input
              ref="inputRef"
              v-model="password"
              class="lock-screen__input"
              type="password"
              placeholder="输入密码解锁"
              aria-label="输入密码解锁"
              autocomplete="current-password"
              data-testid="lock-password"
            />
          </span>
          <button
            type="submit"
            class="lock-screen__submit"
            :disabled="submitting"
            data-testid="lock-unlock"
          >
            {{ submitting ? '解锁中…' : '解锁' }}
          </button>
        </form>

        <p v-if="errorMessage" class="lock-screen__error" data-testid="lock-error">
          {{ errorMessage }}
        </p>

        <button
          type="button"
          class="lock-screen__logout"
          data-testid="lock-logout"
          @click="emit('logout')"
        >
          <LogoutIcon size="13px" />
          退出登录
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lock-screen {
  position: fixed;
  inset: 0;
  z-index: 4000;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 48px;
  color: #f5f7ff;
  background:
    radial-gradient(circle at 63% 61%, rgba(96, 105, 255, 0.26), transparent 19%),
    radial-gradient(circle at 43% 28%, rgba(57, 108, 255, 0.16), transparent 26%),
    linear-gradient(156deg, #071224 0%, #0a1428 42%, #07101f 100%);
  isolation: isolate;
}

.lock-screen__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.16;
  background-image:
    linear-gradient(rgba(157, 175, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(157, 175, 255, 0.06) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: linear-gradient(to right, #000, transparent 95%);
  z-index: -1;
}

.lock-screen__clock {
  text-align: center;
}

.lock-screen__time {
  margin: 0;
  font-size: 64px;
  font-weight: 300;
  letter-spacing: 0.02em;
  line-height: 1.05;
  color: #eef2ff;
}

.lock-screen__date {
  margin: 12px 0 0;
  font-size: 14px;
  color: #8c99b7;
}

.lock-screen__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 320px;
}

.lock-screen__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--ui-primary);
  color: #ffffff;
  font-size: 22px;
  font-weight: 600;
}

.lock-screen__user {
  margin: 0;
  font-size: 15px;
  color: #dbe2f4;
}

.lock-screen__form {
  display: flex;
  gap: 8px;
  width: 100%;
  margin-top: 6px;
}

.lock-screen__input-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
}

.lock-screen__input-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 15px;
  color: #72809c;
  pointer-events: none;
}

.lock-screen__input {
  width: 100%;
  height: 36px;
  padding: 0 10px 0 32px;
  border: 1px solid rgba(151, 165, 255, 0.19);
  border-radius: 8px;
  background: rgba(16, 25, 45, 0.68);
  color: #eef2ff;
  font-family: inherit;
  font-size: 13px;
  outline: none;
  transition:
    border-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.lock-screen__input::placeholder {
  color: #72809c;
}

.lock-screen__input:focus {
  border-color: var(--ui-primary);
  box-shadow: 0 0 0 2px rgba(94, 105, 255, 0.25);
}

.lock-screen__submit {
  height: 36px;
  padding: 0 20px;
  border: 0;
  border-radius: 8px;
  background: var(--ui-primary);
  color: #ffffff;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color var(--ui-ease);
}

.lock-screen__submit:hover:not(:disabled) {
  background: var(--ui-primary-hover);
}

.lock-screen__submit:disabled {
  opacity: 0.7;
  cursor: default;
}

.lock-screen__error {
  margin: 2px 0 0;
  font-size: 12px;
  color: #f19999;
}

.lock-screen__logout {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: none;
  color: #72809c;
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.lock-screen__logout:hover {
  color: #b6c1d5;
}
</style>
