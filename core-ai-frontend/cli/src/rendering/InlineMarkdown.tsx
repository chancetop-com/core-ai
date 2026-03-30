import React from 'react';
import { Text } from 'ink';
import { theme } from '../utils/theme.js';

interface InlineMarkdownProps {
  text: string;
}

export const InlineMarkdown: React.FC<InlineMarkdownProps> = ({ text }) => {
  const segments = parseInline(text);
  return (
    <Text>
      {segments.map((seg, i) => (
        <Text key={i}>{seg}</Text>
      ))}
    </Text>
  );
};

function parseInline(text: string): string[] {
  // split on inline code first
  const parts = text.split(/(`[^`]+`)/g);
  const result: string[] = [];

  for (const part of parts) {
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      result.push(theme.mdInlineCode(part.slice(1, -1)));
    } else {
      let processed = part;
      processed = processed.replace(/\*\*\*([^*]+)\*\*\*/g, (_, t) => theme.mdBold(theme.mdItalic(t)));
      processed = processed.replace(/\*\*([^*]+)\*\*/g, (_, t) => theme.mdBold(t));
      processed = processed.replace(/\*([^*\n]+)\*/g, (_, t) => theme.mdItalic(t));
      processed = processed.replace(/__([^_]+)__/g, (_, t) => theme.mdBold(t));
      processed = processed.replace(/_([^_\n]+)_/g, (_, t) => theme.mdItalic(t));
      processed = processed.replace(/\[([^\]]+)\]\([^)]+\)/g, (_, t) => theme.mdInlineCode(t));
      result.push(processed);
    }
  }

  return result;
}

export function renderInlineText(text: string): string {
  const parts = text.split(/(`[^`]+`)/g);
  return parts.map(part => {
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return theme.mdInlineCode(part.slice(1, -1));
    }
    return part
      .replace(/\*\*\*([^*]+)\*\*\*/g, (_, t) => theme.mdBold(theme.mdItalic(t)))
      .replace(/\*\*([^*]+)\*\*/g, (_, t) => theme.mdBold(t))
      .replace(/\*([^*\n]+)\*/g, (_, t) => theme.mdItalic(t))
      .replace(/__([^_]+)__/g, (_, t) => theme.mdBold(t))
      .replace(/_([^_\n]+)_/g, (_, t) => theme.mdItalic(t))
      .replace(/\[([^\]]+)\]\([^)]+\)/g, (_, t) => theme.mdInlineCode(t));
  }).join('');
}
