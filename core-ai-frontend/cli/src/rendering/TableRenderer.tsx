import React from 'react';
import { Text, Box } from 'ink';
import { theme } from '../utils/theme.js';
import { renderInlineText } from './InlineMarkdown.js';
import { displayWidth } from '../utils/displayWidth.js';

interface TableRendererProps {
  rows: string[][];
  hasHeader: boolean;
}

export const TableRenderer: React.FC<TableRendererProps> = ({ rows, hasHeader }) => {
  if (rows.length === 0) return null;

  const colCount = Math.max(...rows.map(r => r.length));
  const colWidths = Array.from({ length: colCount }, (_, col) =>
    Math.max(...rows.map(r => displayWidth(r[col] || '')), 3)
  );

  const pad = (s: string, w: number) => s + ' '.repeat(Math.max(0, w - displayWidth(s)));
  const border = (left: string, mid: string, right: string) =>
    theme.mdTableBorder(`${left}${colWidths.map(w => '─'.repeat(w + 2)).join(mid)}${right}`);

  const lines: string[] = [];
  lines.push(border('┌', '┬', '┐'));

  rows.forEach((row, i) => {
    const cells = colWidths.map((w, col) => {
      const cell = row[col] || '';
      return ` ${pad(renderInlineText(cell.trim()), w)} `;
    });
    lines.push(`${theme.mdTableBorder('│')}${cells.join(theme.mdTableBorder('│'))}${theme.mdTableBorder('│')}`);

    if (i === 0 && hasHeader) {
      lines.push(border('├', '┼', '┤'));
    }
  });

  lines.push(border('└', '┴', '┘'));

  return (
    <Box flexDirection="column">
      {lines.map((line, i) => <Text key={i}>{line}</Text>)}
    </Box>
  );
};
