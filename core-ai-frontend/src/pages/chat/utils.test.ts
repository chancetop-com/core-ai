import { describe, expect, it } from 'vitest';
import type { HistoryMessage } from '../../api/session';
import { compressionUsageText, historyToChatMessages, restoreCachedChatMessages } from './utils';

describe('historyToChatMessages', () => {
  it('restores a persisted sandbox segment when reopening a completed session', () => {
    const message: HistoryMessage = {
      role: 'agent',
      content: 'done',
      sandbox: {
        sandbox_id: 'sandbox-1',
        sandbox_type: 'ready',
        message: 'Sandbox is ready',
        duration_ms: 1108,
        hostname: 'sandbox-host',
        ip: '10.0.65.162',
        image: 'sandbox-runtime:latest',
      },
    };

    expect(historyToChatMessages([message])[0].segments).toEqual([
      {
        type: 'sandbox',
        historical: true,
        sandboxId: 'sandbox-1',
        sandboxType: 'ready',
        message: 'Sandbox is ready',
        durationMs: 1108,
        hostname: 'sandbox-host',
        ip: '10.0.65.162',
        image: 'sandbox-runtime:latest',
      },
      { type: 'text', content: 'done' },
    ]);
  });

  it('keeps legacy history messages without sandbox data unchanged', () => {
    const message: HistoryMessage = { role: 'agent', content: 'done' };

    expect(historyToChatMessages([message])[0].segments).toEqual([
      { type: 'text', content: 'done' },
    ]);
  });
});

describe('restoreCachedChatMessages', () => {
  it('marks cached live sandbox segments as historical after a page reload', () => {
    const cached = JSON.stringify([{
      role: 'agent',
      segments: [{
        type: 'sandbox',
        sandboxType: 'ready',
        sandboxId: 'sandbox-1',
        message: 'Sandbox is ready',
      }],
    }]);

    expect(restoreCachedChatMessages(cached)[0].segments[0]).toEqual({
      type: 'sandbox',
      historical: true,
      sandboxType: 'ready',
      sandboxId: 'sandbox-1',
      message: 'Sandbox is ready',
    });
  });

  it('returns an empty list for invalid cached data', () => {
    expect(restoreCachedChatMessages('{')).toEqual([]);
    expect(restoreCachedChatMessages(null)).toEqual([]);
  });
});

describe('compressionUsageText', () => {
  it('reports the context occupancy and the trigger threshold', () => {
    expect(compressionUsageText({ before: 66, after: 29, contextTokens: 39912, maxContextTokens: 128000, triggerThreshold: 0.8 }))
      .toBe(' · 39,912 / 128,000 tokens (31%) · threshold 80%');
  });

  it('omits usage the server did not send', () => {
    expect(compressionUsageText({ before: 66, after: 29 })).toBe('');
    expect(compressionUsageText({ before: 66, after: 29, contextTokens: 39912 })).toBe('');
    expect(compressionUsageText({ before: 66, after: 29, maxContextTokens: 128000 })).toBe('');
  });
});
