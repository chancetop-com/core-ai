import React, { useState, useCallback } from 'react';
import { Box, Text, useInput } from 'ink';
import TextInput from 'ink-text-input';
import { theme, icons } from '../utils/theme.js';
import { Spinner } from './Spinner.js';
import { Footer } from './Footer.js';
import { ToolApproval } from './ToolApproval.js';
import { useUIState } from '../contexts/UIStateContext.js';
import { useUIActions } from '../contexts/UIActionsContext.js';

export const Composer: React.FC = () => {
  const state = useUIState();
  const actions = useUIActions();
  const [input, setInput] = useState('');

  const handleSubmit = useCallback((text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    setInput('');
    if (trimmed === '/exit' || trimmed === '/quit') {
      actions.quit();
      return;
    }
    actions.sendMessage(trimmed);
  }, [actions]);

  useInput((ch, key) => {
    if (state.streamingState === 'waiting_approval') {
      if (ch === 'y' || ch === 'Y') actions.approveToolCall('approve');
      else if (ch === 'n' || ch === 'N') actions.approveToolCall('deny');
      return;
    }
    if (state.streamingState === 'responding') {
      if (key.escape) actions.cancelTurn();
      return;
    }
  }, { isActive: state.streamingState !== 'idle' });

  if (state.streamingState === 'waiting_approval' && state.pendingApproval) {
    return (
      <Box marginLeft={2}>
        <ToolApproval request={state.pendingApproval} onDecision={actions.approveToolCall} />
      </Box>
    );
  }

  if (state.streamingState === 'responding') {
    return <Spinner elapsedMs={state.elapsedMs} thought={state.thought} />;
  }

  return (
    <Box flexDirection="column" marginTop={1} marginLeft={2}>
      <Box>
        <Text>{theme.prompt(icons.prompt)} </Text>
        <TextInput
          value={input}
          onChange={setInput}
          onSubmit={handleSubmit}
          placeholder="Type a message..."
        />
      </Box>
      <Footer />
    </Box>
  );
};
