import { useState, useCallback } from 'react';
import type { HistoryItem } from '../types.js';

export function useHistory() {
  const [history, setHistory] = useState<HistoryItem[]>([]);

  const addItems = useCallback((items: HistoryItem[]) => {
    setHistory(prev => [...prev, ...items]);
  }, []);

  const addItem = useCallback((item: HistoryItem) => {
    setHistory(prev => [...prev, item]);
  }, []);

  const clearHistory = useCallback(() => {
    setHistory([]);
  }, []);

  return { history, addItems, addItem, clearHistory };
}
