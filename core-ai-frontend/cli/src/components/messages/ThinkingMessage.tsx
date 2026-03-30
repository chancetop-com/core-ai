import React from 'react';
import { Box, Text } from 'ink';
import { theme } from '../../utils/theme.js';

interface ThinkingMessageProps {
  content: string;
  isStreaming?: boolean;
}

export const ThinkingMessage: React.FC<ThinkingMessageProps> = ({ content, isStreaming }) => {
  const lines = content.split('\n');
  const preview = lines.slice(0, 3).join('\n');
  const hasMore = lines.length > 3;

  return (
    <Box flexDirection="column">
      <Text>{theme.muted('◆ Thinking')}{isStreaming ? theme.muted('...') : ''}</Text>
      <Box marginLeft={2}>
        <Text>{theme.reasoning(preview)}</Text>
      </Box>
      {hasMore && !isStreaming && (
        <Text>{'  '}{theme.muted(`… ${lines.length - 3} more lines`)}</Text>
      )}
    </Box>
  );
};
