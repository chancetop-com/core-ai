import { createContext, useContext } from 'react';
import type { UIState } from '../types.js';

const defaultState: UIState = {
  history: [],
  pendingItems: [],
  streamingState: 'idle',
  currentTaskId: null,
  isInputActive: true,
  pendingApproval: null,
  modelName: 'default',
  agentName: 'core-ai',
  turnCount: 0,
  totalTokens: 0,
  inputTokens: 0,
  outputTokens: 0,
  activeDialog: null,
  terminalWidth: 80,
  lastAssistantText: '',
  thought: '',
  elapsedMs: 0,
};

export const UIStateContext = createContext<UIState>(defaultState);
export const useUIState = () => useContext(UIStateContext);
