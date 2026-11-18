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
  status?: string;
  result?: string;
  reasoning?: string;
  durationMs?: number;
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
  tools?: RemoteTool[];
  events?: RemoteEventRecord[];
  seq?: number | null;
  timestamp?: string | null;
}

function text(value: unknown): string | undefined {
  return value == null ? undefined : String(value);
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
      break;
    case 'reasoning_complete':
      base.reasoning = text(event.reasoning);
      base.durationMs = typeof event.durationMs === 'number' ? event.durationMs : undefined;
      break;
    case 'tool_start':
      base.callId = text(event.callId ?? event.call_id);
      base.toolName = text(event.toolName ?? event.tool_name);
      base.arguments = argumentText(event.arguments ?? event.tool_args);
      base.diff = text(event.diff ?? event.tool_notes);
      base.toolType = text(event.toolType ?? event.tool_type) ?? null;
      break;
    case 'tool_result':
      base.callId = text(event.callId ?? event.call_id);
      base.toolName = text(event.toolName ?? event.tool_name);
      base.result = text(event.result);
      base.status = text(event.status);
      base.toolType = text(event.toolType ?? event.tool_type) ?? null;
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
      tools: normalizeTools(message.tools),
      events: normalizeEvents(message.events),
      seq: typeof message.seq === 'number' ? message.seq : null,
      timestamp: text(message.timestamp) ?? null,
    };
  });
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
