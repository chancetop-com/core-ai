import React, { useMemo, useCallback } from 'react';
import { Box } from 'ink';
import { A2AClient } from '../client.js';
import { useHistory } from '../hooks/useHistory.js';
import { useStream } from '../hooks/useStream.js';
import { useTerminalSize } from '../hooks/useTerminalSize.js';
import { UIStateContext } from '../contexts/UIStateContext.js';
import { UIActionsContext } from '../contexts/UIActionsContext.js';
import { ConfigContext } from '../contexts/ConfigContext.js';
import { StreamingContext } from '../contexts/StreamingContext.js';
import { findCommand } from '../commands/registry.js';
import { MainContent } from '../components/MainContent.js';
import { Composer } from '../components/Composer.js';
import type { UIState, UIActions, HistoryItem } from '../types.js';

interface AppContainerProps {
  baseUrl: string;
  modelName: string;
  agentName: string;
  version: string;
  skipPermissions: boolean;
  onExit: () => void;
}

export const AppContainer: React.FC<AppContainerProps> = ({
  baseUrl, modelName, agentName, version, skipPermissions, onExit,
}) => {
  const client = useMemo(() => new A2AClient(baseUrl), [baseUrl]);
  const { history, addItems, addItem, clearHistory } = useHistory();
  const { columns: terminalWidth } = useTerminalSize();

  const stream = useStream(client, addItems, skipPermissions);

  const sendMessage = useCallback((text: string) => {
    // check for slash command
    const cmd = findCommand(text);
    if (cmd) {
      const ctx = {
        state: uiStateRef.current,
        actions: uiActionsRef.current,
        client,
        baseUrl,
      };
      cmd.command.action(ctx, cmd.args);
      return;
    }
    // add user message to history
    addItem({ id: `user-${Date.now()}`, type: 'user', content: text });
    stream.submitMessage(text);
  }, [client, baseUrl, addItem, stream]);

  const uiState: UIState = useMemo(() => ({
    history,
    pendingItems: stream.pendingItems,
    streamingState: stream.streamingState,
    currentTaskId: stream.currentTaskId,
    isInputActive: stream.streamingState === 'idle',
    pendingApproval: stream.pendingApproval,
    modelName,
    agentName,
    turnCount: stream.turnCount,
    totalTokens: stream.totalTokens,
    inputTokens: stream.inputTokens,
    outputTokens: stream.outputTokens,
    activeDialog: null,
    terminalWidth,
    lastAssistantText: stream.lastAssistantText,
    thought: stream.thought,
    elapsedMs: stream.elapsedMs,
  }), [history, stream, modelName, agentName, terminalWidth]);

  const uiActions: UIActions = useMemo(() => ({
    sendMessage,
    cancelTurn: stream.cancelTurn,
    approveToolCall: stream.approveToolCall,
    addHistoryItem: addItem,
    clearHistory,
    setActiveDialog: () => {},
    quit: onExit,
  }), [sendMessage, stream, addItem, clearHistory, onExit]);

  // refs for command context access
  const uiStateRef = React.useRef(uiState);
  uiStateRef.current = uiState;
  const uiActionsRef = React.useRef(uiActions);
  uiActionsRef.current = uiActions;

  const config = useMemo(() => ({
    baseUrl, client, modelName, agentName, skipPermissions,
  }), [baseUrl, client, modelName, agentName, skipPermissions]);

  return (
    <ConfigContext.Provider value={config}>
      <UIStateContext.Provider value={uiState}>
        <UIActionsContext.Provider value={uiActions}>
          <StreamingContext.Provider value={stream.streamingState}>
            <Box flexDirection="column">
              <MainContent version={version} />
              <Composer />
            </Box>
          </StreamingContext.Provider>
        </UIActionsContext.Provider>
      </UIStateContext.Provider>
    </ConfigContext.Provider>
  );
};
