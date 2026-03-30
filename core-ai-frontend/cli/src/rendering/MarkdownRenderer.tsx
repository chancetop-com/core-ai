import React from 'react';
import { Text, Box } from 'ink';
import { theme } from '../utils/theme.js';
import { CodeBlock } from './CodeBlock.js';
import { InlineMarkdown, renderInlineText } from './InlineMarkdown.js';
import { TableRenderer } from './TableRenderer.js';

interface MarkdownRendererProps {
  text: string;
  isPending?: boolean;
}

export const MarkdownRenderer: React.FC<MarkdownRendererProps> = ({ text, isPending }) => {
  const elements = parseMarkdown(text, isPending);
  return <Box flexDirection="column">{elements}</Box>;
};

function parseMarkdown(text: string, isPending?: boolean): React.ReactNode[] {
  const lines = text.split('\n');
  const elements: React.ReactNode[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i]!;

    // code fence
    const fenceMatch = line.match(/^(```|~~~)(\w*)$/);
    if (fenceMatch) {
      const lang = fenceMatch[2] || '';
      const codeLines: string[] = [];
      i++;
      while (i < lines.length) {
        if (lines[i]!.trimEnd() === '```' || lines[i]!.trimEnd() === '~~~') {
          i++;
          break;
        }
        codeLines.push(lines[i]!);
        i++;
      }
      elements.push(
        <CodeBlock key={elements.length} code={codeLines.join('\n')} language={lang} isPending={isPending} />
      );
      continue;
    }

    // table: collect consecutive | lines
    if (line.trim().startsWith('|') && line.trim().endsWith('|')) {
      const tableLines: string[] = [];
      while (i < lines.length && lines[i]!.trim().startsWith('|') && lines[i]!.trim().endsWith('|')) {
        tableLines.push(lines[i]!);
        i++;
      }
      const parsed = parseTable(tableLines);
      if (parsed) {
        elements.push(<TableRenderer key={elements.length} rows={parsed.rows} hasHeader={parsed.hasHeader} />);
        continue;
      }
      // fallback: render as normal lines
      for (const tl of tableLines) {
        elements.push(<Text key={elements.length}>{renderInlineText(tl)}</Text>);
      }
      continue;
    }

    // heading
    const hMatch = line.match(/^(#{1,6})\s+(.*)/);
    if (hMatch) {
      elements.push(<Text key={elements.length}>{theme.mdHeader(line)}</Text>);
      i++;
      continue;
    }

    // horizontal rule
    if (/^[-*_]{3,}$/.test(line.trim())) {
      elements.push(<Text key={elements.length}>{theme.muted('─'.repeat(40))}</Text>);
      i++;
      continue;
    }

    // unordered list
    const ulMatch = line.match(/^(\s*)[*\-+]\s+(.*)/);
    if (ulMatch) {
      elements.push(
        <Text key={elements.length}>{ulMatch[1]}{theme.mdBullet('•')} {renderInlineText(ulMatch[2]!)}</Text>
      );
      i++;
      continue;
    }

    // ordered list
    const olMatch = line.match(/^(\s*)(\d+)\.\s+(.*)/);
    if (olMatch) {
      elements.push(
        <Text key={elements.length}>{olMatch[1]}{theme.muted(`${olMatch[2]}.`)} {renderInlineText(olMatch[3]!)}</Text>
      );
      i++;
      continue;
    }

    // blockquote
    if (line.startsWith('> ')) {
      elements.push(
        <Text key={elements.length}>{theme.muted('│ ')}{theme.muted(renderInlineText(line.slice(2)))}</Text>
      );
      i++;
      continue;
    }

    // empty line
    if (!line.trim()) {
      elements.push(<Text key={elements.length}> </Text>);
      i++;
      continue;
    }

    // normal text
    elements.push(<Text key={elements.length}>{renderInlineText(line)}</Text>);
    i++;
  }

  return elements;
}

function parseTable(lines: string[]): { rows: string[][]; hasHeader: boolean } | null {
  if (lines.length < 2) return null;

  const parseRow = (line: string) =>
    line.split('|').slice(1, -1).map(c => c.trim());

  const rows: string[][] = [];
  let hasHeader = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!.trim();
    if (/^\|[\s\-:|]+\|$/.test(line)) {
      hasHeader = rows.length > 0;
      continue;
    }
    rows.push(parseRow(line));
  }

  return rows.length > 0 ? { rows, hasHeader } : null;
}
