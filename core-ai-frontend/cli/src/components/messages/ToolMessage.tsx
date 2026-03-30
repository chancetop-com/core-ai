import React from 'react';
import { Text } from 'ink';
import { theme, icons } from '../../utils/theme.js';
import { formatToolSummary, formatResult } from '../../utils/toolSummary.js';

interface ToolStartProps {
  toolName: string;
  toolArgs?: string;
}

export const ToolStartMessage: React.FC<ToolStartProps> = ({ toolName, toolArgs }) => (
  <Text>
    {theme.separator(`${icons.toolStart} `)}{theme.separator(formatToolSummary(toolName, toolArgs))}
  </Text>
);

interface ToolResultProps {
  toolName: string;
  content: string;
  resultStatus?: string;
}

export const ToolResultMessage: React.FC<ToolResultProps> = ({ content, resultStatus }) => {
  const isError = resultStatus === 'error' || resultStatus === 'ERROR';
  const icon = isError ? theme.error(icons.error) : theme.success(icons.success);
  return (
    <Text>{'  '}{icon} {theme.muted(formatResult(content))}</Text>
  );
};
