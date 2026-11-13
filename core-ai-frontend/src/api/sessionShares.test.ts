import { beforeEach, describe, expect, it, vi } from 'vitest';
import { publicSessionShareApi, sessionShareApi, sharedFileUrl } from './sessionShares';

beforeEach(() => {
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  });
});

describe('session sharing requests', () => {
  it('reads public messages and spans without credentials even for a logged-in visitor', async () => {
    localStorage.setItem('apiKey', 'private-api-key');
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ messages: [], spans: [] }))));
    vi.stubGlobal('fetch', fetcher);
    await publicSessionShareApi.messages('share-token', 50);
    await publicSessionShareApi.spans('share-token', 'trace/id');
    expect(fetcher.mock.calls[0][0]).toBe('/api/public/session-shares/share-token/messages?offset=50&limit=50');
    expect(fetcher.mock.calls[1][0]).toBe('/api/public/session-shares/share-token/traces/trace%2Fid/spans');
    for (const [, options] of fetcher.mock.calls) {
      expect(options.credentials).toBe('omit');
      expect(options.referrerPolicy).toBe('no-referrer');
      expect(options.cache).toBe('no-store');
      expect(options.headers).not.toHaveProperty('Authorization');
    }
  });

  it('preserves login credentials and sends the selected expiry only to management endpoints', async () => {
    localStorage.setItem('apiKey', 'private-api-key');
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'share' })));
    vi.stubGlobal('fetch', fetcher);
    await sessionShareApi.create('session/id', 7);
    expect(fetcher).toHaveBeenCalledWith('/api/sessions/session%2Fid/shares', expect.objectContaining({
      method: 'POST', body: '{"expires_in_days":7}',
      headers: expect.objectContaining({ Authorization: 'Bearer private-api-key' }),
    }));
  });

  it('rejects private, external and different-share download links', () => {
    expect(sharedFileUrl('/api/public/session-shares/token/files/file/content', 'token')).toBe('/api/public/session-shares/token/files/file/content');
    expect(sharedFileUrl('/api/files/file/content', 'token')).toBeNull();
    expect(sharedFileUrl('/api/public/session-shares/other/files/file/content', 'token')).toBeNull();
    expect(sharedFileUrl('https://example.com/api/public/session-shares/token/files/file/content', 'token')).toBeNull();
  });
});
