// The remote server speaks the older SSE field names (`content`, `tool_args`, `call_id`, ...).
// The desktop's local app-server events already use the canonical names (`chunk`, `arguments`,
// `callId`, ...). Normalize at this boundary so the remote view consumes one semantic model; a
// future server wire migration only has to change this file.

export interface RemoteEvent {
  type: string;
  sessionId?: string;
  chunk?: string;
  callId?: string;
  toolName?: string;
  arguments?: string;
  diff?: string;
  toolType?: string | null;
  parentCallId?: string | null;
  status?: string;
  result?: string;
  reasoning?: string;
  durationMs?: number;
  /** Transport time to the first token of one LLM call (TTFT). */
  ttftMs?: number;
  /** The server marks the last reasoning chunk instead of sending a typed complete event. */
  final?: boolean;
  todos?: unknown[];
  card?: string;
  data?: string;
  [key: string]: unknown;
}

export interface RemoteTool {
  callId?: string;
  name?: string;
  arguments?: string;
  result?: string;
  status?: string;
  toolType?: string | null;
  parentCallId?: string | null;
  durationMs?: number;
}

export interface RemoteThinkingBlock {
  text: string;
  durationMs?: number;
}

export interface RemoteEventRecord {
  name?: string;
  data?: string;
  card?: string;
  callId?: string;
}

export interface RemoteMessage {
  role: 'user' | 'assistant';
  content: string;
  thinking?: string | null;
  thinkingBlocks?: RemoteThinkingBlock[];
  tools?: RemoteTool[];
  events?: RemoteEventRecord[];
  seq?: number | null;
  timestamp?: string | null;
}

function text(value: unknown): string | undefined {
  return value == null ? undefined : String(value);
}

function milliseconds(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function argumentText(value: unknown): string | undefined {
  if (value == null) return undefined;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function normalizeRemoteEvent(raw: unknown): RemoteEvent | null {
  if (raw === null || typeof raw !== 'object') return null;
  const event = raw as Record<string, unknown>;
  const type = text(event.type);
  if (!type) return null;
  const base: RemoteEvent = { ...event, type };
  base.sessionId = text(event.sessionId);
  switch (type) {
    case 'text_chunk':
      base.chunk = text(event.chunk ?? event.content) ?? '';
      break;
    case 'reasoning_chunk':
      base.chunk = text(event.chunk ?? event.content) ?? '';
      base.reasoning = text(event.reasoning);
      base.final = event.is_final_chunk === true || event.isFinalChunk === true;
      base.durationMs = milliseconds(event.duration_ms ?? event.durationMs);
      break;
    case 'reasoning_complete':
      base.reasoning = text(event.reasoning);
      base.final = true;
      base.durationMs = milliseconds(event.duration_ms ?? event.durationMs);
      break;
    case 'llm_first_token':
      base.ttftMs = milliseconds(event.ttft_ms ?? event.ttftMs);
      break;
    case 'tool_start':
      base.callId = text(event.callId ?? event.call_id);
      base.toolName = text(event.toolName ?? event.tool_name);
      base.arguments = argumentText(event.arguments ?? event.tool_args);
      base.diff = text(event.diff ?? event.tool_notes);
      base.toolType = text(event.toolType ?? event.tool_type) ?? null;
      base.parentCallId = text(event.parentCallId ?? event.parent_call_id) ?? null;
      break;
    case 'tool_result':
      base.callId = text(event.callId ?? event.call_id);
      base.toolName = text(event.toolName ?? event.tool_name);
      base.result = text(event.result);
      base.status = text(event.status);
      base.toolType = text(event.toolType ?? event.tool_type) ?? null;
      base.parentCallId = text(event.parentCallId ?? event.parent_call_id) ?? null;
      base.durationMs = milliseconds(event.duration_ms ?? event.durationMs);
      break;
    case 'plan_update':
      base.todos = Array.isArray(event.todos) ? event.todos : [];
      break;
    case 'custom':
      base.card = text(event.card);
      base.data = text(event.data);
      break;
    default:
      break;
  }
  return base;
}

export function normalizeRemoteMessages(raw: unknown): RemoteMessage[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => {
    const message = (value ?? {}) as Record<string, unknown>;
    const role = message.role === 'user' ? 'user' : 'assistant';
    return {
      role,
      content: text(message.content) ?? '',
      thinking: typeof message.thinking === 'string' ? message.thinking : null,
      thinkingBlocks: normalizeThinkingBlocks(message.thinking_blocks ?? message.thinkingBlocks),
      tools: normalizeTools(message.tools),
      events: normalizeEvents(message.events),
      seq: typeof message.seq === 'number' ? message.seq : null,
      timestamp: text(message.timestamp) ?? null,
    };
  });
}

function normalizeThinkingBlocks(raw: unknown): RemoteThinkingBlock[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  const blocks = raw
    .map((value) => value !== null && typeof value === 'object' ? value as Record<string, unknown> : {})
    .map((block) => ({
      text: typeof block.text === 'string' ? block.text : '',
      durationMs: milliseconds(block.duration_ms ?? block.durationMs),
    }))
    .filter((block) => block.text.trim().length > 0);
  return blocks.length ? blocks : undefined;
}

function normalizeTools(raw: unknown): RemoteTool[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => {
    const tool = (value ?? {}) as Record<string, unknown>;
    return {
      callId: text(tool.callId ?? tool.call_id),
      name: text(tool.name),
      arguments: text(tool.arguments),
      result: text(tool.result),
      status: text(tool.status),
      toolType: text(tool.toolType ?? tool.tool_type) ?? null,
      parentCallId: text(tool.parentCallId ?? tool.parent_call_id) ?? null,
      durationMs: milliseconds(tool.duration_ms ?? tool.durationMs),
    };
  });
}

function normalizeEvents(raw: unknown): RemoteEventRecord[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => {
    const event = (value ?? {}) as Record<string, unknown>;
    return {
      name: text(event.name),
      data: text(event.data),
      card: text(event.card),
      callId: text(event.callId ?? event.call_id),
    };
  });
}
