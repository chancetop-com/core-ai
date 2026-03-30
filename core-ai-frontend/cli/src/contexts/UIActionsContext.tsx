import { createContext, useContext } from 'react';
import type { UIActions } from '../types.js';

const noop = () => {};
const defaultActions: UIActions = {
  sendMessage: noop,
  cancelTurn: noop,
  approveToolCall: noop,
  addHistoryItem: noop,
  clearHistory: noop,
  setActiveDialog: noop,
  quit: noop,
};

export const UIActionsContext = createContext<UIActions>(defaultActions);
export const useUIActions = () => useContext(UIActionsContext);
