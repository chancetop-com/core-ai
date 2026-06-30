import type { Span, Trace } from '../../api/client';

export interface SpanNode extends Span {
  children: SpanNode[];
  depth: number;
  // Index of the agent turn this span belongs to. A new turn starts each time an LLM span
  // appears outside any other LLM ancestor; descendants (tools, sub-agents) inherit the index.
  // undefined for spans above the first LLM call (e.g. the wrapping agent.turn root).
  turnIndex?: number;
}

export interface ExtractedMessage {
  role: string;
  content: string;
  tool_calls?: { id: string; function: { name: string; arguments: string } }[];
  tool_call_id?: string;
  name?: string;
}

export interface ExtractedAssistantOutput {
  content: string;
  reasoning?: string;
  tool_calls?: { id: string; function: { name: string; arguments: string } }[];
}

export interface TimelineBounds {
  startMs: number;
  endMs: number;
  totalMs: number;
}

const WRAPPER_TRACE_NAMES = new Set(['agent.run', 'agent.turn', 'llm_call.run']);

export function traceDisplayName(trace: Trace): string {
  const name = trace.name?.trim();
  if (name && !WRAPPER_TRACE_NAMES.has(name)) return name;
  return trace.agentName || name || trace.traceId || trace.id;
}

export function buildSpanTree(spans: Span[]): SpanNode[] {
  const nodes = new Map<string, SpanNode>();
  const roots: SpanNode[] = [];

  spans.forEach(span => nodes.set(span.spanId, { ...span, children: [], depth: 0 }));

  nodes.forEach(node => {
    if (node.parentSpanId && nodes.has(node.parentSpanId)) {
      nodes.get(node.parentSpanId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  const sortByStart = (items: SpanNode[]) => {
    items.sort((a, b) => toMs(a.startedAt) - toMs(b.startedAt));
    items.forEach(child => sortByStart(child.children));
  };

  const assignDepth = (items: SpanNode[], depth: number) => {
    items.forEach(item => {
      item.depth = depth;
      assignDepth(item.children, depth + 1);
    });
  };

  sortByStart(roots);
  assignDepth(roots, 0);
  assignTurnIndex(roots);
  return roots;
}

function assignTurnIndex(roots: SpanNode[]) {
  let counter = -1;

  const walk = (items: SpanNode[], inherited: number | undefined) => {
    items.forEach(item => {
      let turn = inherited;
      let childInherit = inherited;
      // Crossing an agent boundary resets the turn scope so a sub-agent's iterations
      // get their own turn numbering instead of being absorbed into the parent's turn.
      if (item.type === 'AGENT' || item.type === 'FLOW') {
        childInherit = undefined;
      }
      // A top-level LLM (no LLM ancestor within the current agent scope) opens a new turn.
      if (item.type === 'LLM' && childInherit === undefined) {
        counter += 1;
        turn = counter;
        childInherit = counter;
      }
      item.turnIndex = turn;
      walk(item.children, childInherit);
    });
  };

  walk(roots, undefined);
}

export function flattenSpanTree(nodes: SpanNode[], collapsed: Set<string>): SpanNode[] {
  const result: SpanNode[] = [];

  const walk = (items: SpanNode[]) => {
    items.forEach(node => {
      result.push(node);
      if (!collapsed.has(node.spanId)) walk(node.children);
    });
  };

  walk(nodes);
  return result;
}

export function collectExpandableSpanIds(nodes: SpanNode[]): string[] {
  const ids: string[] = [];

  const walk = (items: SpanNode[]) => {
    items.forEach(node => {
      if (node.children.length > 0) ids.push(node.spanId);
      walk(node.children);
    });
  };

  walk(nodes);
  return ids;
}

export function findDefaultSpan(spans: Span[]): Span | null {
  return spans.find(span => span.status === 'ERROR')
    || spans.find(span => !span.parentSpanId)
    || spans[0]
    || null;
}

export function resolveTraceType(trace: Trace): string {
  if (trace.type) return trace.type;
  if (trace.agentName || trace.sessionId) return 'agent';
  if (trace.model) return 'llm_call';
  return 'external';
}

export function resolveTraceSource(trace: Trace): string {
  if (trace.source) return trace.source;
  if (trace.metadata?.source) return trace.metadata.source;
  if (trace.sessionId) return 'chat';
  return 'external';
}

export function getTimelineBounds(trace: Trace, spans: Span[]): TimelineBounds {
  const starts = spans.map(span => toMs(span.startedAt)).filter(Boolean);
  const ends = spans
    .map(span => toMs(span.completedAt) || toMs(span.startedAt))
    .filter(Boolean);

  const traceStart = toMs(trace.startedAt) || Math.min(...starts);
  const traceEnd = toMs(trace.completedAt) || Math.max(...ends);
  const startMs = Number.isFinite(traceStart) ? traceStart : Date.now();
  const endMs = Number.isFinite(traceEnd) && traceEnd > startMs
    ? traceEnd
    : startMs + Math.max(trace.durationMs || 0, 1);

  return { startMs, endMs, totalMs: Math.max(endMs - startMs, 1) };
}

export function getSpanTiming(span: Span, bounds: TimelineBounds) {
  const start = toMs(span.startedAt) || bounds.startMs;
  const end = toMs(span.completedAt) || start + (span.durationMs || 0);
  const left = clamp(((start - bounds.startMs) / bounds.totalMs) * 100, 0, 100);
  const width = clamp(((Math.max(end, start) - start) / bounds.totalMs) * 100, 0.6, 100 - left);
  return { left, width };
}

export function extractTracePreview(trace: Trace): string {
  const fromInput = extractMessages(trace.input)
    .findLast(message => message.role === 'user' && message.content.trim());
  if (fromInput) return compactText(fromInput.content, 120);

  if (trace.input) {
    const parsed = tryParseJson(trace.input);
    if (typeof parsed === 'string') return compactText(parsed, 120);
    if (parsed && typeof parsed === 'object') {
      const value = extractFirstString(parsed);
      if (value) return compactText(value, 120);
    }
    return compactText(trace.input, 120);
  }

  return '';
}

export function extractMessages(input?: string): ExtractedMessage[] {
  if (!input) return [];
  const parsed = tryParseJson(input);
  if (!parsed || typeof parsed !== 'object' || !('messages' in parsed)) return [];

  const messages = (parsed as { messages?: unknown }).messages;
  if (!Array.isArray(messages)) return [];

  return messages.map(message => {
    const msg = message as Record<string, unknown>;
    const result: ExtractedMessage = {
      role: String(msg.role || 'unknown'),
      content: extractMessageContent(msg.content),
    };
    if (Array.isArray(msg.tool_calls) && msg.tool_calls.length > 0) {
      result.tool_calls = msg.tool_calls as ExtractedMessage['tool_calls'];
    }
    if (msg.tool_call_id) result.tool_call_id = String(msg.tool_call_id);
    if (msg.name) result.name = String(msg.name);
    return result;
  });
}

export function extractAssistantContent(output?: string): ExtractedAssistantOutput | null {
  if (!output) return null;
  const parsed = tryParseJson(output);
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>;
    const result: ExtractedAssistantOutput = {
      content: typeof obj.content === 'string' ? obj.content : output,
      reasoning: typeof obj.reasoning_content === 'string' ? obj.reasoning_content : undefined,
    };
    if (Array.isArray(obj.tool_calls) && obj.tool_calls.length > 0) {
      result.tool_calls = obj.tool_calls as ExtractedAssistantOutput['tool_calls'];
    }
    return result;
  }
  return { content: output };
}

export function formatDuration(ms?: number | null): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

export function formatRelativeTime(iso?: string): string {
  if (!iso) return '-';
  const timestamp = new Date(iso).getTime();
  if (!Number.isFinite(timestamp)) return '-';

  const diffMs = Date.now() - timestamp;
  if (diffMs < 60000) return `${Math.max(0, Math.floor(diffMs / 1000))}s ago`;
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}m ago`;
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}h ago`;
  return new Date(timestamp).toLocaleDateString() + ' ' + new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function formatTokenPair(input?: number | null, output?: number | null): string {
  const inputTokens = input || 0;
  const outputTokens = output || 0;
  if (!inputTokens && !outputTokens) return '-';
  return `${inputTokens.toLocaleString()} / ${outputTokens.toLocaleString()}`;
}

export function formatTokenCount(tokens?: number | null): string {
  return typeof tokens === 'number' ? tokens.toLocaleString() : '0';
}

export function formatCostUsd(cost?: number | null): string {
  if (typeof cost !== 'number' || !Number.isFinite(cost)) return '-';
  if (cost === 0) return '$0.0000';
  if (cost < 0.0001) return `$${cost.toFixed(6)}`;
  if (cost < 0.01) return `$${cost.toFixed(4)}`;
  return `$${cost.toFixed(2)}`;
}

export function prettyContent(content?: string): string {
  if (!content) return '';
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}

function tryParseJson(str: string): unknown | null {
  try {
    return JSON.parse(str);
  } catch {
    return null;
  }
}

function extractMessageContent(content: unknown): string {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';

  return content
    .map(part => {
      if (typeof part === 'string') return part;
      if (part && typeof part === 'object') {
        const obj = part as Record<string, unknown>;
        if (typeof obj.text === 'string') return obj.text;
        if (typeof obj.content === 'string') return obj.content;
      }
      return '';
    })
    .join('');
}

function extractFirstString(value: unknown): string {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = extractFirstString(item);
      if (found) return found;
    }
  }
  if (value && typeof value === 'object') {
    for (const item of Object.values(value)) {
      const found = extractFirstString(item);
      if (found) return found;
    }
  }
  return '';
}

function compactText(text: string, maxLength: number): string {
  const compacted = text.replace(/\s+/g, ' ').trim();
  return compacted.length > maxLength ? `${compacted.slice(0, maxLength)}...` : compacted;
}

function toMs(iso?: string | null): number {
  if (!iso) return 0;
  const value = new Date(iso).getTime();
  return Number.isFinite(value) ? value : 0;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
