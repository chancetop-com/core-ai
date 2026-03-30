import React from 'react';
import { Text } from 'ink';
import { theme, icons } from '../../utils/theme.js';

export const InfoMessage: React.FC<{ content: string }> = ({ content }) => (
  <Text>{theme.muted(content)}</Text>
);

export const WarningMessage: React.FC<{ content: string }> = ({ content }) => (
  <Text>{theme.warning(icons.warning)} {content}</Text>
);

export const ErrorMessage: React.FC<{ content: string }> = ({ content }) => (
  <Text>{theme.error(icons.error)} {content}</Text>
);

export const SystemMessage: React.FC<{ content: string }> = ({ content }) => (
  <Text>{theme.muted(content)}</Text>
);
