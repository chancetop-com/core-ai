// Derived values for the transcript renderer.

import type { InputItem, Item, ReasoningItem, TextItem, Turn, TurnSummary } from './reducer';

export function textItems(turn: Turn): string[] {
  return turn.items
    .filter((item): item is TextItem => item.kind === 'text' && !!item.text)
    .map((item) => item.text as string);
}

export function reasoningItems(turn: Turn): string[] {
  return turn.items
    .filter((item): item is ReasoningItem => item.kind === 'reasoning' && !!item.text)
    .map((item) => item.text as string);
}

export function tools(turn: Turn): Item[] {
  return turn.items.filter((item) => item.kind === 'tool');
}

export function inputs(turn: Turn): InputItem[] {
  return turn.items.filter((item): item is InputItem => item.kind === 'input');
}

export function hasOutput(turn: Turn): boolean {
  return turn.items.some((item) => item.kind === 'text' || item.kind === 'reasoning' || item.kind === 'tool'
    || item.kind === 'batch' || item.kind === 'custom' || item.kind === 'memory' || item.kind === 'notice'
    || item.kind === 'compression' || item.kind === 'task') || turn.summary !== null;
}

export function summaryText(summary: TurnSummary | null): string[] {
  if (!summary) return [];
  const parts: string[] = [];
  if (summary.cancelled) parts.push('turn cancelled');
  if (summary.maxTurnsReached) parts.push('max turns reached');
  const bits: string[] = [];
  if (summary.durationMs !== undefined) bits.push(formatElapsed(summary.durationMs));
  if (summary.inputTokens || summary.outputTokens) {
    const input = summary.inputTokens ?? 0;
    const output = summary.outputTokens ?? 0;
    const cached = summary.cachedTokens ?? 0;
    bits.push(`${(input + output).toLocaleString()} tokens (↑ ${input.toLocaleString()} ↓ ${output.toLocaleString()}${cached > 0 ? ` ~${cached.toLocaleString()}` : ''})`);
  }
  if (summary.costUsd) bits.push(formatCost(summary.costUsd));
  if (bits.length) parts.push(`✦ ${bits.join(' | ')}`);
  return parts;
}

function formatElapsed(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  return seconds >= 10 ? `${Math.round(seconds)}s` : `${Math.round(seconds * 10) / 10}s`;
}

function formatCost(value: number): string {
  if (value <= 0) return '$0';
  if (value < 0.01) return `$${value.toFixed(4)}`;
  return `$${value.toFixed(2)}`;
}
