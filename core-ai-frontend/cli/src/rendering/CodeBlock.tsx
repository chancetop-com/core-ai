import React from 'react';
import { Text, Box } from 'ink';
import { theme } from '../utils/theme.js';
import { highlightCode } from './CodeHighlighter.js';

interface CodeBlockProps {
  code: string;
  language: string;
  isPending?: boolean;
}

export const CodeBlock: React.FC<CodeBlockProps> = ({ code, language }) => {
  const lines = code.split('\n');
  const label = language || 'code';

  return (
    <Box flexDirection="column">
      <Text>{theme.muted('  ┌─ ')}{language ? theme.mdInlineCode(label) : theme.muted(label)}</Text>
      {lines.map((line, i) => (
        <Text key={i}>{theme.muted('  │ ')}{highlightCode(line, language)}</Text>
      ))}
      <Text>{theme.muted('  └─')}</Text>
    </Box>
  );
};
