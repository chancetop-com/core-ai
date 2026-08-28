import { describe, expect, it } from 'vitest';
import type { HistoryMessage } from '../../api/session';
import { historyToChatMessages, restoreCachedChatMessages } from './utils';

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
