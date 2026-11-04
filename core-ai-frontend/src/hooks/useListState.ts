import { useEffect, useState, type Dispatch, type SetStateAction } from 'react';

const PREFIX = 'core-ai:list-state:';

/**
 * Keeps list controls (search, filters, pagination) for the lifetime of the browser tab.
 * Detail pages unmount the list route, so restoring this state after a back navigation
 * is what makes the list land on the same page/query instead of its defaults.
 */
export function useListState<T>(key: string, initialValue: T | (() => T)): [T, Dispatch<SetStateAction<T>>] {
  const storageKey = PREFIX + key;
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = window.sessionStorage.getItem(storageKey);
      if (stored !== null) return JSON.parse(stored) as T;
    } catch {
      // Private mode / corrupted value: fall back to the page default
    }
    return typeof initialValue === 'function' ? (initialValue as () => T)() : initialValue;
  });

  useEffect(() => {
    try {
      window.sessionStorage.setItem(storageKey, JSON.stringify(value));
    } catch {
      // Quota or storage disabled: the list still works, it just won't survive navigation
    }
  }, [storageKey, value]);

  return [value, setValue];
}

export function clearListState(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < window.sessionStorage.length; i += 1) {
      const key = window.sessionStorage.key(i);
      if (key?.startsWith(PREFIX)) keys.push(key);
    }
    keys.forEach(key => window.sessionStorage.removeItem(key));
  } catch {
    // ignore unavailable storage
  }
}
