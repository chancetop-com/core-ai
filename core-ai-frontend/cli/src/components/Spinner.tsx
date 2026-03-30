import React, { useState, useEffect } from 'react';
import { Text } from 'ink';
import { theme, icons } from '../utils/theme.js';

interface SpinnerProps {
  elapsedMs: number;
  thought?: string;
}

const MESSAGES = [
  'Thinking...', 'Charging the laser...', 'Assembling pixels...',
  'Consulting the oracle...', 'Brewing fresh tokens...', 'Polishing the output...',
  'Crunching numbers...', 'Weaving magic...', 'Summoning results...',
];

function formatTime(ms: number): string {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m ${s % 60}s`;
}

export const Spinner: React.FC<SpinnerProps> = ({ elapsedMs, thought }) => {
  const [frame, setFrame] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => setFrame(f => (f + 1) % icons.spinner.length), 80);
    return () => clearInterval(timer);
  }, []);

  const msgIdx = Math.floor(elapsedMs / 3000) % MESSAGES.length;
  const msg = thought ? theme.reasoning(thought.split('\n').pop()?.slice(0, 60) || '') : MESSAGES[msgIdx];

  return (
    <Text>
      {'  '}{theme.prompt(icons.spinner[frame]!)} {msg}{' '}
      {theme.muted(`(esc to cancel, ${formatTime(elapsedMs)})`)}
    </Text>
  );
};
