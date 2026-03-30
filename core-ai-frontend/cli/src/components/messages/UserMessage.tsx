import React from 'react';
import { Text } from 'ink';
import { theme, icons } from '../../utils/theme.js';

export const UserMessage: React.FC<{ content: string }> = ({ content }) => (
  <Text>{theme.prompt(`${icons.prompt} `)} {content}</Text>
);
