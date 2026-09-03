import { DialogPlugin } from 'tdesign-vue-next';

export interface ConfirmDialogOptions {
  header: string;
  body: string;
  confirmBtn?: string;
  cancelBtn?: string;
  theme?: 'default' | 'warning' | 'danger' | 'success' | 'info';
}

/**
 * TDesign's DialogPlugin.confirm returns a dialog-node instance, not a
 * Promise — `await`-ing it resolves immediately and the guarded action
 * would run before the user confirms. This wrapper restores the
 * Element Plus semantics: resolves on confirm, rejects on cancel or
 * close. Callers keep the `try { await confirmDialog(...) } catch { return }`
 * pattern.
 *
 * The dialog instance is destroyed explicitly on every settle: TDesign
 * plugin dialogs do not auto-close on confirm and are attached to
 * document.body, so a page navigation after the action (e.g. logout ->
 * /login) used to leave the dialog floating over the next screen.
 */
export function confirmDialog(options: ConfirmDialogOptions): Promise<void> {
  return new Promise((resolve, reject) => {
    let settled = false;
    let instance: import('tdesign-vue-next').DialogInstance | null = null;
    const done = (fn: () => void) => {
      if (!settled) {
        settled = true;
        instance?.destroy();
        fn();
      }
    };
    instance = DialogPlugin.confirm({
      ...options,
      destroyOnClose: true,
      onConfirm: () => done(resolve),
      onCancel: () => done(() => reject(new Error('cancelled'))),
      onClose: () => done(() => reject(new Error('cancelled'))),
    });
  });
}
