import type { ChatMessage, MessageSegment, TextSegment } from './types';
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
  if (m.content) {
    segments.push({ type: 'text', content: m.content });
  }
  return segments;
}

export function historyToChatMessages(messages: HistoryMessage[]): ChatMessage[] {
  return messages.map(m => ({
    role: m.role === 'user' ? 'user' : 'agent',
    segments: buildSegments(m),
  }));
}

/** Extract concatenated text from all text segments (for copy) */
export function getMessageText(msg: ChatMessage): string {
  return (msg.segments || [])
    .filter((s): s is TextSegment => s.type === 'text')
    .map(s => s.content)
    .join('\n\n');
}
