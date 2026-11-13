import { getAuthHeaders } from './client';
import type { Span, Trace } from './client';
import type { HistoryMessage, SessionArtifact } from './session';

export interface SharedSessionArtifact extends SessionArtifact {
  download_url?: string;
}

export interface SessionShareView {
  id: string;
  session_id: string;
  title?: string;
  url?: string | null;
  max_message_seq: number;
  message_count: number;
  created_at: string;
  expires_at: string;
  revoked_at?: string;
}

export interface SessionShareStatus {
  share?: SessionShareView;
  artifacts: SharedSessionArtifact[];
}

export interface SharedSessionView {
  title?: string;
  max_message_seq: number;
  message_count: number;
  created_at: string;
  expires_at: string;
  artifacts: SharedSessionArtifact[];
}

export interface SharedSessionMessages {
  messages: HistoryMessage[];
  next_offset?: number;
}

async function shareRequest<T>(path: string, anonymous: boolean, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    credentials: anonymous ? 'omit' : 'same-origin',
    headers: anonymous ? { Accept: 'application/json' } : getAuthHeaders(),
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  });
  if (!response.ok) {
    const fallback = anonymous && [401, 403, 404, 410].includes(response.status)
      ? 'This shared content is unavailable. The link may have expired or been revoked.'
      : `Request failed (${response.status}). Please try again.`;
    let message = fallback;
    if (!anonymous) {
      const body = await response.json().catch(() => null);
      if (typeof body?.message === 'string') message = body.message;
    }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return text ? JSON.parse(text) as T : undefined as T;
}

const ownerPath = (id: string) => `/api/sessions/${encodeURIComponent(id)}/shares`;
const publicPath = (token: string) => `/api/public/session-shares/${encodeURIComponent(token)}`;

export const sessionShareApi = {
  get: (sessionId: string) => shareRequest<SessionShareStatus>(ownerPath(sessionId), false),
  create: (sessionId: string, expiresInDays: 1 | 7 | 30) => shareRequest<SessionShareView>(ownerPath(sessionId), false, {
    method: 'POST', body: JSON.stringify({ expires_in_days: expiresInDays }),
  }),
  revoke: (sessionId: string, shareId: string) => shareRequest<void>(`${ownerPath(sessionId)}/${encodeURIComponent(shareId)}`, false, { method: 'DELETE' }),
};

export const publicSessionShareApi = {
  get: (token: string) => shareRequest<SharedSessionView>(publicPath(token), true),
  messages: (token: string, offset = 0, limit = 50) => shareRequest<SharedSessionMessages>(`${publicPath(token)}/messages?${new URLSearchParams({ offset: String(offset), limit: String(limit) })}`, true),
  trace: (token: string, traceId: string) => shareRequest<Trace>(`${publicPath(token)}/traces/${encodeURIComponent(traceId)}`, true),
  spans: async (token: string, traceId: string) => (await shareRequest<{ spans: Span[] }>(`${publicPath(token)}/traces/${encodeURIComponent(traceId)}/spans`, true)).spans ?? [],
  span: (token: string, traceId: string, spanId: string) => shareRequest<Span>(`${publicPath(token)}/traces/${encodeURIComponent(traceId)}/spans/${encodeURIComponent(spanId)}`, true),
};

export function sharedFileUrl(value: string | undefined, token?: string): string | null {
  if (!value) return null;
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin || url.search || url.hash) return null;
    const match = /^\/api\/public\/session-shares\/([^/]+)\/files\/([^/]+)\/content$/.exec(url.pathname);
    if (!match || (token !== undefined && match[1] !== encodeURIComponent(token))) return null;
    return url.pathname;
  } catch {
    return null;
  }
}

export async function downloadSharedFile(artifact: SharedSessionArtifact, token: string): Promise<void> {
  const url = sharedFileUrl(artifact.download_url, token);
  if (!url) throw new Error('This file is not available in this share.');
  const response = await fetch(url, { credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' });
  if (!response.ok) throw new Error('This file is unavailable. The share or original file may no longer be available.');
  const objectUrl = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = artifact.file_name || 'download';
  link.click();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}
