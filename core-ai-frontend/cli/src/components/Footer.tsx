import React from 'react';
import { Box, Text } from 'ink';
import { theme } from '../utils/theme.js';
import { useUIState } from '../contexts/UIStateContext.js';

export const Footer: React.FC = () => {
  const { modelName, streamingState } = useUIState();

  return (
    <Box justifyContent="space-between">
      <Text>{theme.muted('? for shortcuts')}</Text>
      <Text>{theme.muted(modelName)}</Text>
    </Box>
  );
};
