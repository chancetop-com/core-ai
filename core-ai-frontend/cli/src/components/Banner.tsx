import React from 'react';
import { Box, Text } from 'ink';
import { theme } from '../utils/theme.js';

interface BannerProps {
  agentName: string;
  modelName: string;
  version: string;
}

// Chrome T-Rex in braille (same pixel style as logo)
const DINO = [
  '⠀⠀⢀⣯⡿⠗',
  '⠼⣶⣿⠟⠀⠀',
  '⠀⣸⠑⣄⠀⠀',
];

const LOGO = [
  '⣠⣶⣶⣄ ⣠⣶⣶⣄ ⣶⣶⣶⣄ ⣶⣶⣶⡀  ⣠⣶⣶⣄ ⣶⡆',
  '⣿⡟⠀⠀ ⣿⡟⢻⣿ ⣿⡟⠻⣷ ⣿⡟⠛⠁  ⣿⣶⣶⣿ ⣿⡇',
  '⠻⣶⣶⠃ ⠻⣶⣶⠃ ⠿⠃ ⠻⠇⠿⠿⠿⠃  ⠿⠃ ⠿ ⠿⠇',
];

export const Banner: React.FC<BannerProps> = ({ agentName, modelName, version }) => {
  const cwd = process.cwd();
  const dirName = cwd.replace(process.env.HOME || '', '~');

  return (
    <Box flexDirection="column" marginTop={1} marginBottom={1}>
      {DINO.map((dino, i) => (
        <Text key={i}>  {theme.muted(dino)} {theme.prompt(LOGO[i]!)}</Text>
      ))}
      <Text>  {theme.muted(`      v${version} · ${modelName} · ${dirName}`)}</Text>
    </Box>
  );
};
