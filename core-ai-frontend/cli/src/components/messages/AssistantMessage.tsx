import React from 'react';
import { Box, Text } from 'ink';
import { theme, icons } from '../../utils/theme.js';
import { MarkdownRenderer } from '../../rendering/MarkdownRenderer.js';

interface AssistantMessageProps {
  content: string;
  isStreaming?: boolean;
}

export const AssistantMessage: React.FC<AssistantMessageProps> = ({ content, isStreaming }) => (
  <Box flexDirection="column">
    <Text>{theme.separator(`${icons.bullet} `)}</Text>
    <Box marginLeft={2}>
      <MarkdownRenderer text={content} isPending={isStreaming} />
    </Box>
  </Box>
);
