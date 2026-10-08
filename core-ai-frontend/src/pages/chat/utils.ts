import type { ChatMessage, CompressionSegment, MessageSegment, QuickRepliesSegment, QuickReplyOption, RichCard, TextSegment } from './types';
import type { HistoryMessage } from '../../api/session';

export function normalizeArgs(argsJson: string | undefined): Record<string, unknown> | null {
  if (!argsJson || argsJson === '{}') return null;
  try {
    const args = JSON.parse(argsJson);
    // backend wraps tool_args as {raw: "..."}, unwrap it
    if (args && typeof args === 'object' && !Array.isArray(args) && Object.keys(args).length === 1 && 'raw' in args && typeof args.raw === 'string') {
      try { return JSON.parse(args.raw); } catch { return args; }
    }
    return args;
  } catch {
    return null;
  }
}

export function getArgsPreview(argsJson: string | undefined): string | null {
  const args = normalizeArgs(argsJson);
  if (!args) return null;
  // Prefer description if present
  if (typeof args.description === 'string' && args.description) return args.description;
  // Otherwise show first few key-value pairs as a compact preview
  const entries = Object.entries(args).filter(([k]) => k !== 'raw');
  if (entries.length === 0) return null;
  const preview = entries.slice(0, 2).map(([k, v]) => {
    const val = typeof v === 'string' ? v : JSON.stringify(v);
    // Truncate long values
    const shortVal = val.length > 60 ? val.slice(0, 60) + '...' : val;
    return `${k}: ${shortVal}`;
  }).join(', ');
  if (entries.length > 2) return preview + ', ...';
  return preview;
}

function buildSegments(m: HistoryMessage): MessageSegment[] {
  const segments: MessageSegment[] = [];
  if (m.compression) {
    segments.push(compressionSegment({
      before: m.compression.before_count ?? 0,
      after: m.compression.after_count ?? 0,
      contextTokens: m.compression.context_tokens,
      maxContextTokens: m.compression.max_context_tokens,
      triggerThreshold: m.compression.trigger_threshold,
    }));
  }
  if (m.sandbox) {
    segments.push({
      type: 'sandbox',
      historical: true,
      sandboxType: m.sandbox.sandbox_type || '',
      sandboxId: m.sandbox.sandbox_id || '',
      message: m.sandbox.message || '',
      hostname: m.sandbox.hostname,
      ip: m.sandbox.ip,
      image: m.sandbox.image,
      durationMs: m.sandbox.duration_ms,
    });
  }
  if (m.thinking) {
    segments.push({ type: 'thinking', content: m.thinking });
  }
  if (m.tools && m.tools.length > 0) {
    segments.push({
      type: 'tools',
      tools: m.tools.map(t => ({
        type: 'result',
        tool: t.name,
        callId: t.call_id,
        arguments: t.arguments,
        result: t.result,
        resultStatus: t.status,
      })),
    });
  }
  if (m.events && m.events.length > 0) {
    const custom = appendCustomSegments([], m.events.flatMap(event => customEventSegments(event)));
    segments.push(...custom);
  }
  if (m.content) {
    segments.push({ type: 'text', content: m.content });
  }
  return segments;
}

export function historyToChatMessages(messages: HistoryMessage[]): ChatMessage[] {
  return messages.map(m => ({
    role: m.role === 'user' ? 'user' : 'agent',
    segments: buildSegments(m),
    timestamp: m.timestamp,
  }));
}

export function restoreCachedChatMessages(serialized: string | null): ChatMessage[] {
  if (!serialized) return [];
  try {
    const parsed: unknown = JSON.parse(serialized);
    if (!Array.isArray(parsed)) return [];
    return (parsed as ChatMessage[]).map(message => ({
      ...message,
      segments: Array.isArray(message.segments)
        ? message.segments.map(segment => segment.type === 'sandbox'
          ? { ...segment, historical: true }
          : segment)
        : [],
    }));
  } catch {
    return [];
  }
}

/** Extract concatenated text from all text segments (for copy) */
export function getMessageText(msg: ChatMessage): string {
  return (msg.segments || [])
    .filter((s): s is TextSegment => s.type === 'text')
    .map(s => s.content)
    .join('\n\n');
}

/** Format an ISO timestamp for display next to a chat message. */
export function formatMessageTime(timestamp?: string): string {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hh = pad(date.getHours());
  const mm = pad(date.getMinutes());
  const sameDay = date.toDateString() === now.toDateString();
  if (sameDay) return `${hh}:${mm}`;
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday ${hh}:${mm}`;
  const M = pad(date.getMonth() + 1);
  const D = pad(date.getDate());
  if (date.getFullYear() === now.getFullYear()) return `${M}-${D} ${hh}:${mm}`;
  return `${date.getFullYear()}-${M}-${D} ${hh}:${mm}`;
}

/** Full localized timestamp for tooltips. */
export function formatMessageTimeFull(timestamp?: string): string {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString();
}

export interface CompressionUsage {
  before: number;
  after: number;
  contextTokens?: number;
  maxContextTokens?: number;
  triggerThreshold?: number;
}

/** Context occupancy suffix of the compression notice, empty when the server sent no usage. */
export function compressionUsageText(info: CompressionUsage): string {
  const parts: string[] = [];
  if (info.contextTokens !== undefined && info.maxContextTokens) {
    const percent = Math.round((info.contextTokens / info.maxContextTokens) * 100);
    parts.push(`${info.contextTokens.toLocaleString()} / ${info.maxContextTokens.toLocaleString()} tokens (${percent}%)`);
  }
  if (info.triggerThreshold !== undefined) parts.push(`threshold ${Math.round(info.triggerThreshold * 100)}%`);
  return parts.length > 0 ? ` · ${parts.join(' · ')}` : '';
}

/** The same compression record reaches the UI live (SSE) and on reload (history), so both build the same segment. */
export function compressionSegment(usage: CompressionUsage): CompressionSegment {
  return {
    type: 'compression',
    before: usage.before,
    after: usage.after,
    contextTokens: usage.contextTokens,
    maxContextTokens: usage.maxContextTokens,
    triggerThreshold: usage.triggerThreshold,
  };
}

function parseJsonObject(text?: string): Record<string, unknown> | null {
  if (!text) return null;
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function quickReplyOptions(payload: Record<string, unknown> | null): QuickReplyOption[] {
  if (!payload || !Array.isArray(payload.options)) return [];
  const options: QuickReplyOption[] = [];
  for (const raw of payload.options) {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) continue;
    const option = raw as Record<string, unknown>;
    if (typeof option.label !== 'string' || !option.label) continue;
    options.push({
      label: option.label,
      value: typeof option.value === 'string' && option.value ? option.value : option.label,
      description: typeof option.description === 'string' && option.description ? option.description : undefined,
    });
  }
  return options;
}

/**
 * Turns a custom event into renderable chat segments: the standard quick_replies event becomes buttons,
 * any other event with the platform card companion field becomes a generic card. Unknown events render nothing.
 */
export function customEventSegments(event: { name: string; data?: string; card?: string }): MessageSegment[] {
  if (event.name === 'quick_replies') {
    const payload = parseJsonObject(event.data);
    const options = quickReplyOptions(payload);
    if (options.length === 0) return [];
    const question = payload && typeof payload.question === 'string' && payload.question ? payload.question : undefined;
    return [{ type: 'quick_replies', question, options }];
  }
  if (event.card) {
    const card = parseJsonObject(event.card);
    if (card && Array.isArray(card.blocks) && card.blocks.length > 0) {
      return [{ type: 'card', name: event.name, card: card as unknown as RichCard }];
    }
  }
  return [];
}

/** Appends incoming custom-event segments, merging consecutive identical quick_replies groups. */
export function appendCustomSegments(segments: MessageSegment[], incoming: MessageSegment[]): MessageSegment[] {
  const next = [...segments];
  for (const segment of incoming) {
    const last = next[next.length - 1];
    if (segment.type === 'quick_replies' && last?.type === 'quick_replies' && sameQuickReplies(last, segment)) continue;
    next.push(segment);
  }
  return next;
}

function sameQuickReplies(a: QuickRepliesSegment, b: QuickRepliesSegment): boolean {
  if (a.options.length !== b.options.length) return false;
  return a.options.every((option, idx) => option.label === b.options[idx].label && option.value === b.options[idx].value);
}
