import type { ChatMessage } from './types';

// Stream-recovery invariants for the chat message list:
// - only a turn-begin marker (STATUS_CHANGE running) may clear the active bubble,
//   which makes server-side event replay idempotent after an SSE reconnect;
// - a history re-sync may never delete locally streamed content the persisted
//   history does not have yet (the agent reply is only persisted at turn end).

/**
 * Reset the trailing agent bubble so replayed turn events rebuild it from scratch.
 * Mirrors the server buffer semantics: SessionChannelService clears its event buffer
 * when a RUNNING status event arrives, so replay always starts from the turn begin.
 */
export function clearActiveAgentBubble(messages: ChatMessage[]): ChatMessage[] {
  const last = messages[messages.length - 1];
  if (!last || last.role !== 'agent' || last.segments.length === 0) return messages;
  const updated = [...messages];
  updated[updated.length - 1] = { ...last, segments: [] };
  return updated;
}

/**
 * Replace the local list with authoritative history, but keep the live in-flight
 * bubble when history has no agent reply for the trailing user message yet.
 */
export function mergeHistoryWithLive(hydrated: ChatMessage[], live: ChatMessage[]): ChatMessage[] {
  const lastHydrated = hydrated[hydrated.length - 1];
  if (lastHydrated?.role !== 'user') return hydrated;
  const lastLive = live[live.length - 1];
  if (lastLive?.role === 'agent' && lastLive.segments.length > 0) {
    return [...hydrated, lastLive];
  }
  return [...hydrated, { role: 'agent', segments: [], timestamp: new Date().toISOString() }];
}
