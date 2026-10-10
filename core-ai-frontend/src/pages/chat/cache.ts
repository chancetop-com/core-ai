// The chat page caches its open conversation in sessionStorage so a page reload can
// restore it. sessionStorage survives logout in the same tab, so the cache is tagged
// with the account that wrote it and is discarded when a different user signs in —
// otherwise the next user would be shown the previous user's conversation.

const OWNER_KEY = 'chat_userId';

export const chatCacheKeys = {
  messages: 'chat_messages',
  sessionId: 'chat_sessionId',
  artifacts: 'chat_artifacts',
  agentId: 'chat_agentId',
} as const;

export function clearCachedChat(): void {
  sessionStorage.removeItem(chatCacheKeys.messages);
  sessionStorage.removeItem(chatCacheKeys.sessionId);
  sessionStorage.removeItem(chatCacheKeys.artifacts);
  sessionStorage.removeItem(chatCacheKeys.agentId);
  sessionStorage.removeItem(OWNER_KEY);
}

/**
 * Claims this tab's cached chat for the given user and reports whether the cached
 * conversation belongs to them. Anything cached for another account — or left untagged
 * by an older client — is discarded: the caller starts the page empty instead.
 */
export function adoptCachedChat(userId: string | undefined): boolean {
  if (userId && sessionStorage.getItem(OWNER_KEY) === userId) return true;
  clearCachedChat();
  if (userId) sessionStorage.setItem(OWNER_KEY, userId);
  return false;
}
