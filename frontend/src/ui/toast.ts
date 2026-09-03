/**
 * UiToast — minimal self-drawn toast bus (no runtime UI dependency).
 * Module-level store so any page/composable can fire messages without
 * prop drilling; <UiToastHost /> renders the stack once (App.vue).
 * Host mounted once per app — never mount twice.
 */
import { reactive } from 'vue';

export type ToastTone = 'success' | 'error' | 'info';

export interface ToastItem {
  id: number;
  tone: ToastTone;
  message: string;
  closable: boolean;
}

const DURATION_SUCCESS = 3200;
const DURATION_INFO = 3200;
const DURATION_ERROR = 7000;

let nextId = 1;
const timers = new Map<number, ReturnType<typeof setTimeout>>();

export const toastState = reactive<{ items: ToastItem[] }>({ items: [] });

function push(tone: ToastTone, message: string, duration: number) {
  const id = nextId++;
  toastState.items.push({ id, tone, message, closable: tone === 'error' });
  timers.set(
    id,
    setTimeout(() => dismiss(id), duration),
  );
  return id;
}

export function dismissToast(id: number) {
  const timer = timers.get(id);
  if (timer) {
    clearTimeout(timer);
    timers.delete(id);
  }
  const index = toastState.items.findIndex((item) => item.id === id);
  if (index >= 0) {
    toastState.items.splice(index, 1);
  }
}

export const toast = {
  success(message: string) {
    return push('success', message, DURATION_SUCCESS);
  },
  info(message: string) {
    return push('info', message, DURATION_INFO);
  },
  error(message: string) {
    return push('error', message, DURATION_ERROR);
  },
};
