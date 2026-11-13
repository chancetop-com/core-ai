import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../../App';
import { historyToChatMessages } from '../chat/utils';

const trace = {
  id: 'trace-1', traceId: 'trace-1', name: 'agent.turn', sessionId: 'session-1',
  userId: 'owner', status: 'COMPLETED', metadata: {}, inputTokens: 4, outputTokens: 2,
  totalTokens: 6, durationMs: 50, startedAt: '2026-11-13T00:00:00Z',
  completedAt: '2026-11-13T00:00:01Z', createdAt: '2026-11-13T00:00:00Z',
};
const span = {
  id: 'span-1', traceId: 'trace-1', spanId: 'span-1', parentSpanId: null,
  name: 'llm.call', type: 'LLM', model: 'model', status: 'OK', inputTokens: 4, outputTokens: 2,
  input: '{"messages":[{"role":"system","content":"Shared system prompt"}]}',
  output: 'Shared model result', durationMs: 50, startedAt: '2026-11-13T00:00:00Z', completedAt: '2026-11-13T00:00:01Z',
};

beforeEach(() => {
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  });
  localStorage.setItem('apiKey', 'expired-private-key');
  localStorage.setItem('userId', 'owner');
  localStorage.setItem('userPermissions', '["*"]');
  window.history.replaceState({}, '', '/shared/sessions/public-token');
});

describe('public session page', () => {
  it('opens without auth bootstrap and lazily reads trace payloads only through public endpoints', async () => {
    const fetcher = vi.fn(async (url: string) => {
      const base = '/api/public/session-shares/public-token';
      if (url === base) return new Response(JSON.stringify({
        title: 'Shared debugging session', message_count: 3, max_message_seq: 3,
        created_at: '2026-11-13T00:00:00Z', expires_at: '2035-01-01T00:00:00Z', artifacts: [],
      }));
      if (url === `${base}/messages?offset=0&limit=50`) return new Response(JSON.stringify({
        messages: [{ role: 'agent', content: 'Inspect this reply\n\n<iframe src="https://example.com"></iframe>', seq: 2, trace_id: 'trace-1' }], next_offset: 2,
      }));
      if (url === `${base}/messages?offset=2&limit=50`) return new Response(JSON.stringify({
        messages: [{ role: 'user', content: 'Another shared message', seq: 3 }],
      }));
      if (url === `${base}/traces/trace-1`) return new Response(JSON.stringify(trace));
      if (url === `${base}/traces/trace-1/spans`) return new Response(JSON.stringify({ spans: [span] }));
      if (url === `${base}/traces/trace-1/spans/span-1`) return new Response(JSON.stringify(span));
      throw new Error(`Unexpected private request: ${url}`);
    });
    vi.stubGlobal('fetch', fetcher);
    const { container } = render(<App />);
    expect(await screen.findByText('Shared debugging session')).toBeTruthy();
    expect(container.querySelector('iframe')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'View trace' }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledWith(
      '/api/public/session-shares/public-token/traces/trace-1/spans/span-1', expect.anything(),
    ));
    expect(screen.queryByRole('button', { name: /Replay/ })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Chat' })).toBeNull();
    expect(screen.queryByTitle('Open full trace page')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Load more messages' }));
    expect(await screen.findByText('Another shared message')).toBeTruthy();
    for (const [url, options] of fetcher.mock.calls as unknown as [string, RequestInit][]) {
      expect(url).toMatch(/^\/api\/public\/session-shares\/public-token/);
      expect(options.credentials).toBe('omit');
      expect(options.headers).not.toHaveProperty('Authorization');
    }
    expect(localStorage.getItem('apiKey')).toBe('expired-private-key');
  });

  it('shows an unavailable link without redirecting to login', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response('', { status: 404 }))));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'This shared content is unavailable. The link may have expired or been revoked.');
    expect(window.location.pathname).toBe('/shared/sessions/public-token');
    expect(localStorage.getItem('apiKey')).toBe('expired-private-key');
  });

  it('renders a bare rewritten image URL from history inline and opens its read-only viewer', async () => {
    const base = '/api/public/session-shares/public-token';
    const imageUrl = `${base}/files/image-1/content`;
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:shared-menu');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    const fetcher = vi.fn(async (url: string) => {
      if (url === base) return new Response(JSON.stringify({
        title: 'Image debugging', message_count: 1, max_message_seq: 1,
        created_at: '2026-11-13T00:00:00Z', expires_at: '2035-01-01T00:00:00Z',
        artifacts: [{ file_id: 'image-1', file_name: 'menu.png', content_type: 'image/png', download_url: imageUrl }],
      }));
      if (url === `${base}/messages?offset=0&limit=50`) return new Response(JSON.stringify({
        messages: [{ role: 'user', content: `Check this menu\n\n${imageUrl}`, seq: 1 }],
      }));
      if (url === imageUrl) return new Response('image bytes', { headers: { 'Content-Type': 'image/png' } });
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal('fetch', fetcher);
    render(<App />);
    const image = await screen.findByAltText('menu.png');
    expect(image.getAttribute('src')).toBe('blob:shared-menu');
    expect(screen.getByText('Check this menu')).toBeTruthy();
    expect(screen.queryByText(imageUrl)).toBeNull();
    fireEvent.click(image);
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getAllByAltText('menu.png')).toHaveLength(2);
    expect(screen.queryByTitle('圈选编辑')).toBeNull();
    expect(fetcher).toHaveBeenCalledWith(imageUrl, { credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' });
  });

  it('preserves the durable message sequence and trace association', () => {
    expect(historyToChatMessages([{ role: 'agent', content: 'Done', seq: 4, trace_id: 'trace-4' }])[0]).toMatchObject({
      seq: 4, traceId: 'trace-4', segments: [{ type: 'text', content: 'Done' }],
    });
  });
});
