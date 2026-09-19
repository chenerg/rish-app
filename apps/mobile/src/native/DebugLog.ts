import { NativeModules, TurboModuleRegistry } from 'react-native';

/**
 * The in-app debug log, exported without a cable.
 *
 * Android keeps a bounded native step log (`AndroidDebugLog`) describing what
 * the native layers did — picker steps, grant outcomes, tool refusals — never
 * message text or file contents. This wrapper reads it for the share sheet.
 * On platforms without the module, `isAvailable()` is false and nothing is
 * shown.
 */

type DebugLogNative = {
  export(): Promise<unknown>;
  clear(): Promise<unknown>;
};

function currentNative(): DebugLogNative | null {
  let candidate: unknown = null;
  try {
    candidate = TurboModuleRegistry.get('DebugLog');
  } catch {
    candidate = null;
  }
  if (candidate === null || typeof candidate !== 'object') {
    try {
      candidate = Reflect.get(NativeModules, 'DebugLog') as unknown;
    } catch {
      return null;
    }
  }
  if (candidate === null || typeof candidate !== 'object') return null;
  const native = candidate as Partial<DebugLogNative>;
  return typeof native.export === 'function' && typeof native.clear === 'function'
    ? (native as DebugLogNative)
    : null;
}

export const DebugLog = {
  isAvailable: (): boolean => currentNative() !== null,

  /** The retained log text, oldest line first; empty when there is none. */
  export: async (): Promise<string> => {
    const native = currentNative();
    if (native === null) return '';
    const result = (await native.export()) as { text?: unknown } | null;
    return result !== null && typeof result === 'object' && typeof result.text === 'string'
      ? result.text
      : '';
  },

  clear: async (): Promise<void> => {
    const native = currentNative();
    if (native === null) return;
    await native.clear();
  },
};
