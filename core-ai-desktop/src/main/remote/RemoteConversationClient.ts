// Direct remote-conversation transport. The renderer never sees an API key or a server URL it can
// choose: every call is resolved to a stored login and validated here in the main process.
import { randomUUID } from 'node:crypto';
import * as http from 'node:http';
import * as https from 'node:https';
import { AuthEntry, loadAuthEntries } from '../auth/AuthStore';

export interface RemoteServerView {
  serverUrl: string;
  name: string | null;
  userId: string | null;
  active: boolean;
  insecure: boolean;
}

export interface RemoteAgent {
  id: string;
  name: string;
  description?: string | null;
  type?: string | null;
  source?: string | null;
  status?: string | null;
}

export interface RemoteSession {
  id: string;
  agentId: string | null;
  agentName: string | null;
  title: string | null;
  source: string | null;
  messageCount: number | null;
  createdAt: string | null;
  lastMessageAt: string | null;
}

export interface RemoteSnapshot {
  sessionId: string;
  agentId: string | null;
  agentName: string | null;
  title: string | null;
  status: string;
  messageCount: number | null;
  createdAt: string | null;
  lastActiveAt: string | null;
}

export interface RemoteHistory {
  messages: Array<Record<string, unknown>>;
  artifacts?: Array<Record<string, unknown>>;
  hasMore: boolean;
  oldestSeq: number | null;
}

export type RemotePush =
  | { type: 'event'; serverUrl: string; sessionId: string; event: Record<string, unknown> }
  | { type: 'state'; serverUrl: string; sessionId: string; phase: 'connected' | 'reconnecting' | 'rejected' | 'closed'; attempt?: number; status?: number; message?: string };

export class RemoteHttpError extends Error {
  status: number;
  code: string | null;

  constructor(status: number, message: string, code: string | null = null) {
    super(message);
    this.name = 'RemoteHttpError';
    this.status = status;
    this.code = code;
  }
}

export function normalizeServerUrl(value: string): string {
  return String(value ?? '').trim().replace(/\/+$/, '');
}

export function validSessionId(value: string): boolean {
  return /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(String(value ?? ''));
}

/** One SSE parser per connection: core-ng emits one `data: <json>` line per event. */
export class SseFrameParser {
  private buffer = '';

  push(chunk: string): Array<Record<string, unknown>> {
    this.buffer += chunk;
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() ?? '';
    const events: Array<Record<string, unknown>> = [];
    for (const raw of lines) {
      const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (!data) continue;
      try {
        const parsed = JSON.parse(data) as Record<string, unknown>;
        if (parsed && typeof parsed === 'object') events.push(parsed);
      } catch {
        // A malformed frame must not tear down an otherwise healthy stream.
      }
    }
    return events;
  }
}

function errorMessageOf(body: string): { message: string; code: string | null } {
  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    const nested = parsed.error as Record<string, unknown> | undefined;
    const code = String(parsed.error_code ?? parsed.errorCode ?? nested?.code ?? '') || null;
    const message = String(parsed.message ?? nested?.message ?? body).slice(0, 500);
    return { message: message || `HTTP error${code ? ` (${code})` : ''}`, code };
  } catch {
    return { message: body.slice(0, 500) || 'HTTP request failed', code: null };
  }
}

interface StreamState {
  request: http.ClientRequest | null;
  closed: boolean;
  attempt: number;
  timer: ReturnType<typeof setTimeout> | null;
}

export class RemoteConversationClient {
  private readonly entries: () => AuthEntry[];
  private readonly push: (event: RemotePush) => void;
  private readonly streams = new Map<string, StreamState>();
  private readonly timeoutMs: number;

  constructor(options: { loadEntries?: () => AuthEntry[]; onPush?: (event: RemotePush) => void; timeoutMs?: number } = {}) {
    this.entries = options.loadEntries ?? (() => loadAuthEntries());
    this.push = options.onPush ?? (() => undefined);
    this.timeoutMs = options.timeoutMs ?? 30_000;
  }

  servers(): RemoteServerView[] {
    return this.entries()
      .filter((entry) => entry.server_url && entry.api_key)
      .map((entry) => ({
        serverUrl: normalizeServerUrl(entry.server_url),
        name: entry.name ?? null,
        userId: entry.user_id ?? null,
        active: entry.active === true,
        insecure: entry.insecure === true,
      }))
      .sort((a, b) => Number(b.active) - Number(a.active));
  }

  async capabilities(serverUrl: string): Promise<Record<string, unknown>> {
    return this.request(serverUrl, 'GET', '/api/conversations/v1/capabilities');
  }

  async agents(serverUrl: string, query = '', limit = 50): Promise<RemoteAgent[]> {
    const qs = new URLSearchParams();
    if (query) qs.set('query', query);
    qs.set('limit', String(limit));
    const body = await this.request(serverUrl, 'GET', `/api/conversations/v1/agents?${qs.toString()}`);
    const agents = Array.isArray(body.agents) ? (body.agents as Array<Record<string, unknown>>) : [];
    return agents
      .filter((agent) => typeof agent.id === 'string')
      .map((agent) => ({
        id: String(agent.id),
        name: String(agent.name ?? agent.id),
        description: agent.description == null ? null : String(agent.description),
        type: agent.type == null ? null : String(agent.type),
        source: agent.source == null ? null : String(agent.source),
        status: agent.status == null ? null : String(agent.status),
      }));
  }

  async sessions(serverUrl: string, offset = 0, limit = 30): Promise<{ sessions: RemoteSession[]; total: number }> {
    const qs = new URLSearchParams({ offset: String(Math.max(0, offset)), limit: String(Math.max(1, limit)) });
    const body = await this.request(serverUrl, 'GET', `/api/conversations/v1/sessions?${qs.toString()}`);
    const sessions = Array.isArray(body.sessions) ? (body.sessions as Array<Record<string, unknown>>) : [];
    return {
      sessions: sessions
        .filter((session) => typeof session.id === 'string')
        .map((session) => ({
          id: String(session.id),
          agentId: session.agent_id == null ? null : String(session.agent_id),
          agentName: session.agent_name == null ? null : String(session.agent_name),
          title: session.title == null ? null : String(session.title),
          source: session.source == null ? null : String(session.source),
          messageCount: typeof session.message_count === 'number' ? session.message_count : null,
          createdAt: session.created_at == null ? null : String(session.created_at),
          lastMessageAt: session.last_message_at == null ? null : String(session.last_message_at),
        })),
      total: typeof body.total === 'number' ? body.total : sessions.length,
    };
  }

  async create(serverUrl: string, agentId: string): Promise<{ sessionId: string; created: boolean; agentName: string | null; model: string | null }> {
    if (!agentId) throw new Error('agent id is required');
    const body = await this.request(serverUrl, 'POST', '/api/conversations/v1/sessions', {
      agent_id: agentId,
      client_request_id: randomUUID(),
    });
    return {
      sessionId: String(body.session_id ?? ''),
      created: body.created === true,
      agentName: body.agent_name == null ? null : String(body.agent_name),
      model: body.model == null ? null : String(body.model),
    };
  }

  async snapshot(serverUrl: string, sessionId: string): Promise<RemoteSnapshot> {
    const body = await this.request(serverUrl, 'GET', `/api/conversations/v1/sessions/${encodeURIComponent(this.session(sessionId))}`);
    return {
      sessionId: String(body.session_id ?? sessionId),
      agentId: body.agent_id == null ? null : String(body.agent_id),
      agentName: body.agent_name == null ? null : String(body.agent_name),
      title: body.title == null ? null : String(body.title),
      status: String(body.status ?? 'idle').toLowerCase(),
      messageCount: typeof body.message_count === 'number' ? body.message_count : null,
      createdAt: body.created_at == null ? null : String(body.created_at),
      lastActiveAt: body.last_active_at == null ? null : String(body.last_active_at),
    };
  }

  async history(serverUrl: string, sessionId: string, beforeSeq: number | null = null, limit = 50): Promise<RemoteHistory> {
    const qs = new URLSearchParams({ limit: String(Math.min(Math.max(1, limit), 200)) });
    if (beforeSeq != null) qs.set('before_seq', String(beforeSeq));
    const body = await this.request(serverUrl, 'GET',
      `/api/conversations/v1/sessions/${encodeURIComponent(this.session(sessionId))}/history?${qs.toString()}`);
    return {
      messages: Array.isArray(body.messages) ? (body.messages as Array<Record<string, unknown>>) : [],
      artifacts: Array.isArray(body.artifacts) ? (body.artifacts as Array<Record<string, unknown>>) : undefined,
      hasMore: body.has_more === true,
      oldestSeq: typeof body.oldest_seq === 'number' ? body.oldest_seq : null,
    };
  }

  async send(serverUrl: string, sessionId: string, message: string): Promise<{ duplicate: boolean }> {
    if (!message.trim()) throw new Error('message is required');
    const body = await this.request(serverUrl, 'POST',
      `/api/conversations/v1/sessions/${encodeURIComponent(this.session(sessionId))}/messages`,
      { message, client_request_id: randomUUID() });
    return { duplicate: body.duplicate === true };
  }

  async cancel(serverUrl: string, sessionId: string): Promise<{ cancelled: boolean }> {
    const body = await this.request(serverUrl, 'POST',
      `/api/conversations/v1/sessions/${encodeURIComponent(this.session(sessionId))}/cancel`, {});
    return { cancelled: body.cancelled === true };
  }

  async rename(serverUrl: string, sessionId: string, title: string): Promise<string> {
    if (!title.trim()) throw new Error('title is required');
    const body = await this.request(serverUrl, 'PUT',
      `/api/conversations/v1/sessions/${encodeURIComponent(this.session(sessionId))}/title`, { title: title.trim() });
    return String(body.title ?? title.trim());
  }

  /**
   * Opens the authenticated event stream. The server replays its per-turn buffer on connect, so a
   * reconnect re-attaches the running turn without reposting the user message. Failures that are
   * authoritative for this caller (401/403/404) stop here; transient failures retry with backoff.
   */
  subscribe(serverUrl: string, sessionId: string): void {
    const key = `${normalizeServerUrl(serverUrl)}\u0000${this.session(sessionId)}`;
    const existing = this.streams.get(key);
    if (existing) {
      if (!existing.closed) return;
      if (existing.timer) clearTimeout(existing.timer);
      this.streams.delete(key);
    }
    const state: StreamState = { request: null, closed: false, attempt: 0, timer: null };
    this.streams.set(key, state);
    this.openStream(key, serverUrl, sessionId, state);
  }

  unsubscribe(serverUrl: string, sessionId: string): void {
    const key = `${normalizeServerUrl(serverUrl)}\u0000${sessionId}`;
    const state = this.streams.get(key);
    if (!state) return;
    state.closed = true;
    if (state.timer) clearTimeout(state.timer);
    state.request?.destroy();
    this.streams.delete(key);
    this.emitState(serverUrl, sessionId, { phase: 'closed' });
  }

  close(): void {
    for (const key of [...this.streams.keys()]) {
      const [serverUrl, sessionId] = key.split('\u0000');
      this.unsubscribe(serverUrl, sessionId);
    }
  }

  private openStream(key: string, serverUrl: string, sessionId: string, state: StreamState): void {
    if (state.closed) return;
    let entry: AuthEntry;
    try {
      entry = this.entry(serverUrl);
    } catch (error) {
      this.emitState(serverUrl, sessionId, { phase: 'rejected', message: error instanceof Error ? error.message : String(error) });
      return;
    }
    const target = new URL(`/api/sessions/events?agent-session-id=${encodeURIComponent(sessionId)}`, normalizeServerUrl(serverUrl));
    const transport = target.protocol === 'http:' ? http : https;
    const request = transport.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || (target.protocol === 'http:' ? 80 : 443),
      path: `${target.pathname}${target.search}`,
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${entry.api_key}`,
        Accept: 'text/event-stream',
      },
      rejectUnauthorized: entry.insecure !== true,
    });
    state.request = request;
    request.setTimeout(this.timeoutMs, () => request.destroy(new Error('stream timeout')));

    request.on('response', (response) => {
      if (state.closed) {
        response.resume();
        return;
      }
      const status = response.statusCode ?? 0;
      if (status !== 200) {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', (chunk: string) => { body += chunk; });
        response.on('end', () => {
          const parsed = errorMessageOf(body);
          this.emitState(serverUrl, sessionId, { phase: 'rejected', status, message: parsed.message });
          if (status >= 500) this.scheduleReconnect(key, serverUrl, sessionId, state);
        });
        return;
      }
      state.attempt = 0;
      this.emitState(serverUrl, sessionId, { phase: 'connected' });
      const parser = new SseFrameParser();
      response.setEncoding('utf8');
      response.on('data', (chunk: string) => {
        for (const raw of parser.push(chunk)) {
          if (typeof raw.errorCode === 'string') {
            this.emitState(serverUrl, sessionId, { phase: 'rejected', message: String(raw.message ?? raw.errorCode) });
            continue;
          }
          this.push({ type: 'event', serverUrl: normalizeServerUrl(serverUrl), sessionId, event: raw });
        }
      });
      response.on('end', () => this.scheduleReconnect(key, serverUrl, sessionId, state));
      response.on('error', () => this.scheduleReconnect(key, serverUrl, sessionId, state));
    });
    request.on('error', () => {
      if (state.closed) return;
      this.scheduleReconnect(key, serverUrl, sessionId, state);
    });
    request.end();
  }

  private scheduleReconnect(key: string, serverUrl: string, sessionId: string, state: StreamState): void {
    if (state.closed || state.timer) return;
    if (this.streams.get(key) !== state) return;
    state.attempt += 1;
    const delay = Math.min(500 * 2 ** Math.min(state.attempt - 1, 4), 10_000);
    this.emitState(serverUrl, sessionId, { phase: 'reconnecting', attempt: state.attempt });
    state.timer = setTimeout(() => {
      state.timer = null;
      if (state.closed) return;
      try {
        this.openStream(key, serverUrl, sessionId, state);
      } catch {
        this.scheduleReconnect(key, serverUrl, sessionId, state);
      }
    }, delay);
  }

  private emitState(serverUrl: string, sessionId: string, extra: Omit<Extract<RemotePush, { type: 'state' }>, 'type' | 'serverUrl' | 'sessionId'>): void {
    this.push({ type: 'state', serverUrl: normalizeServerUrl(serverUrl), sessionId, ...extra });
  }

  private session(sessionId: string): string {
    if (!validSessionId(sessionId)) throw new Error('invalid session id');
    return sessionId;
  }

  private entry(serverUrl: string): AuthEntry {
    const normalized = normalizeServerUrl(serverUrl);
    const entry = this.entries().find((candidate) => normalizeServerUrl(candidate.server_url) === normalized && candidate.api_key);
    if (!entry) throw new Error(`not signed in to ${serverUrl}`);
    return entry;
  }

  private request(serverUrl: string, method: string, path: string, body?: Record<string, unknown>): Promise<Record<string, unknown>> {
    let entry: AuthEntry;
    try {
      entry = this.entry(serverUrl);
    } catch (error) {
      return Promise.reject(error);
    }
    const target = new URL(path, normalizeServerUrl(serverUrl));
    const payload = body == null ? null : JSON.stringify(body);
    const transport = target.protocol === 'http:' ? http : https;
    return new Promise((resolve, reject) => {
      const request = transport.request({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || (target.protocol === 'http:' ? 80 : 443),
        path: `${target.pathname}${target.search}`,
        method,
        headers: {
          Authorization: `Bearer ${entry.api_key}`,
          Accept: 'application/json',
          ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': String(Buffer.byteLength(payload)) } : {}),
        },
        rejectUnauthorized: entry.insecure !== true,
      }, (response) => {
        const status = response.statusCode ?? 0;
        const chunks: Buffer[] = [];
        response.on('data', (chunk: Buffer) => chunks.push(chunk));
        response.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          if (status >= 200 && status < 300) {
            if (!text.trim()) {
              resolve({});
              return;
            }
            try {
              resolve(JSON.parse(text) as Record<string, unknown>);
            } catch {
              reject(new RemoteHttpError(status, 'server returned an invalid JSON response'));
            }
            return;
          }
          const parsed = errorMessageOf(text);
          reject(new RemoteHttpError(status, parsed.message, parsed.code));
        });
      });
      request.setTimeout(this.timeoutMs, () => request.destroy(new Error('request timeout')));
      request.on('error', (error) => reject(error));
      if (payload) request.write(payload);
      request.end();
    });
  }
}
