import { describe, it, expect } from 'vitest';
import { clearActiveAgentBubble, ensureTrailingAgentBubble, mergeHistoryWithLive, resolveRestoredTurn } from './streamRecovery';
import type { ChatMessage } from './types';

const user = (content: string): ChatMessage => ({
  role: 'user',
  segments: [{ type: 'text', content }],
  timestamp: '2026-01-01T00:00:00Z',
});

const agentWith = (segments: ChatMessage['segments']): ChatMessage => ({
  role: 'agent',
  segments,
  timestamp: '2026-01-01T00:00:01Z',
});

const streamingBubble = (): ChatMessage => agentWith([
  { type: 'thinking', content: 'reasoning...' },
  { type: 'tools', tools: [{ type: 'start', tool: 'search', callId: 'c-1' }] },
]);

describe('clearActiveAgentBubble', () => {
  it('clears segments of the trailing agent bubble', () => {
    const messages = [user('hi'), streamingBubble()];

    const result = clearActiveAgentBubble(messages);

    expect(result[1].segments).toEqual([]);
    expect(result[1].role).toBe('agent');
  });

  it('keeps earlier messages untouched', () => {
    const earlier = agentWith([{ type: 'text', content: 'done earlier' }]);
    const messages = [user('q1'), earlier, user('q2'), streamingBubble()];

    const result = clearActiveAgentBubble(messages);

    expect(result[1]).toBe(earlier);
    expect(result[3].segments).toEqual([]);
  });

  it('returns input unchanged when the trailing message is a user message', () => {
    const messages = [user('hi')];

    expect(clearActiveAgentBubble(messages)).toBe(messages);
  });

  it('returns input unchanged when the trailing agent bubble is already empty', () => {
    const messages = [user('hi'), agentWith([])];

    expect(clearActiveAgentBubble(messages)).toBe(messages);
  });

  it('returns input unchanged for an empty list', () => {
    const messages: ChatMessage[] = [];

    expect(clearActiveAgentBubble(messages)).toBe(messages);
  });
});

describe('mergeHistoryWithLive', () => {
  it('uses history as-is when it already ends with the persisted agent reply', () => {
    const hydrated = [user('hi'), agentWith([{ type: 'text', content: 'final answer' }])];
    const live = [user('hi'), streamingBubble()];

    expect(mergeHistoryWithLive(hydrated, live)).toBe(hydrated);
  });

  it('grafts the live streaming bubble when history lacks the in-flight reply', () => {
    const hydrated = [user('hi')];
    const bubble = streamingBubble();
    const live = [user('hi'), bubble];

    const result = mergeHistoryWithLive(hydrated, live);

    expect(result).toHaveLength(2);
    expect(result[1]).toBe(bubble);
  });

  it('appends an empty placeholder when history ends with user and nothing streamed yet', () => {
    const hydrated = [user('hi')];
    const live = [user('hi'), agentWith([])];

    const result = mergeHistoryWithLive(hydrated, live);

    expect(result).toHaveLength(2);
    expect(result[1].role).toBe('agent');
    expect(result[1].segments).toEqual([]);
  });

  it('appends an empty placeholder when history ends with user and live has no agent bubble', () => {
    const hydrated = [user('hi')];
    const live = [user('hi')];

    const result = mergeHistoryWithLive(hydrated, live);

    expect(result).toHaveLength(2);
    expect(result[1].role).toBe('agent');
    expect(result[1].segments).toEqual([]);
  });

  it('returns empty history unchanged', () => {
    const hydrated: ChatMessage[] = [];
    const live = [user('hi'), streamingBubble()];

    expect(mergeHistoryWithLive(hydrated, live)).toBe(hydrated);
  });
});

describe('resolveRestoredTurn', () => {
  it('resumes when the backend still reports the turn as running', () => {
    expect(resolveRestoredTurn('running', [user('q')])).toBe('resume');
    expect(resolveRestoredTurn('running', [user('q'), agentWith([{ type: 'text', content: 'partial' }])])).toBe('resume');
  });

  it('re-syncs history when the turn ended but the restored list still awaits the reply', () => {
    expect(resolveRestoredTurn('idle', [user('q')])).toBe('resync');
    expect(resolveRestoredTurn('idle', [user('q'), agentWith([])])).toBe('resync');
    expect(resolveRestoredTurn('error', [user('q')])).toBe('resync');
  });

  it('does nothing when the restored list already ends with a persisted reply', () => {
    expect(resolveRestoredTurn('idle', [user('q'), agentWith([{ type: 'text', content: 'a' }])])).toBe('none');
    expect(resolveRestoredTurn('idle', [])).toBe('none');
  });

  it('does nothing when the status could not be read', () => {
    expect(resolveRestoredTurn(null, [user('q')])).toBe('none');
  });
});

describe('ensureTrailingAgentBubble', () => {
  it('appends an empty agent bubble after a trailing user message', () => {
    const result = ensureTrailingAgentBubble([user('q')]);
    expect(result).toHaveLength(2);
    expect(result[1].role).toBe('agent');
    expect(result[1].segments).toEqual([]);
  });

  it('keeps the list unchanged when it already ends with an agent bubble', () => {
    const list = [user('q'), agentWith([])];
    expect(ensureTrailingAgentBubble(list)).toBe(list);
  });
});
