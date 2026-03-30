import { useState, useCallback } from 'react';

export function useTextBuffer() {
  const [text, setText] = useState('');

  const clear = useCallback(() => setText(''), []);
  const set = useCallback((v: string) => setText(v), []);

  return { text, setText: set, clear };
}
