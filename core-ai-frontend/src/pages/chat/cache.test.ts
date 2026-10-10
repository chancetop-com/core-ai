import { beforeEach, describe, expect, it, vi } from 'vitest';
import { adoptCachedChat, chatCacheKeys, clearCachedChat } from './cache';

function createStorageMock() {
  const store = new Map<string, string>();
  return {
    getItem: vi.fn((key: string) => store.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn((key: string) => {
      store.delete(key);
    }),
  };
}

function writeCachedConversation(owner: string) {
  adoptCachedChat(owner);
  sessionStorage.setItem(chatCacheKeys.messages, JSON.stringify([{ role: 'user', segments: [{ type: 'text', content: 'hi' }] }]));
  sessionStorage.setItem(chatCacheKeys.sessionId, 'session-1');
  sessionStorage.setItem(chatCacheKeys.artifacts, JSON.stringify([{ id: 'artifact-1' }]));
  sessionStorage.setItem(chatCacheKeys.agentId, 'agent-1');
}

describe('chat cache ownership', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage', createStorageMock());
  });

  it('restores the cached conversation when the same user reloads the page', () => {
    writeCachedConversation('alice');

    expect(adoptCachedChat('alice')).toBe(true);
    expect(sessionStorage.getItem(chatCacheKeys.messages)).not.toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.sessionId)).toBe('session-1');
    expect(sessionStorage.getItem(chatCacheKeys.artifacts)).not.toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.agentId)).toBe('agent-1');
  });

  it('drops the previous user\'s conversation when another user signs in on the same tab', () => {
    writeCachedConversation('alice');

    expect(adoptCachedChat('bob')).toBe(false);
    expect(sessionStorage.getItem(chatCacheKeys.messages)).toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.sessionId)).toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.artifacts)).toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.agentId)).toBeNull();
    // the tab is bob's from now on
    expect(adoptCachedChat('bob')).toBe(true);
  });

  it('wipes the cached conversation on logout so the next user starts empty', () => {
    writeCachedConversation('alice');

    clearCachedChat();

    // the owner tag is gone too, so even alice's own cache is not restored
    expect(adoptCachedChat('alice')).toBe(false);
    expect(sessionStorage.getItem(chatCacheKeys.messages)).toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.sessionId)).toBeNull();
    expect(adoptCachedChat('bob')).toBe(false);
  });

  it('does not adopt an untagged cache left by an older client', () => {
    sessionStorage.setItem(chatCacheKeys.messages, JSON.stringify([{ role: 'user', segments: [] }]));
    sessionStorage.setItem(chatCacheKeys.sessionId, 'session-1');

    expect(adoptCachedChat('alice')).toBe(false);
    expect(sessionStorage.getItem(chatCacheKeys.messages)).toBeNull();
    expect(sessionStorage.getItem(chatCacheKeys.sessionId)).toBeNull();
  });
});
