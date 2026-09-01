import { getAuthHeaders } from './client';

const BASE = '';

// ---------------------------------------------------------------------------
// Terminal client id: identifies "this browser tab's terminal panel" to the
// server so a page reload can recover the same PTY instead of spawning a new
// one. Persisted in localStorage (survives reload); falls back to an
// in-memory id (stable only for this page load) when storage throws, e.g.
// private browsing mode with storage disabled.
// ---------------------------------------------------------------------------

const memoryClientIds = new Map<string, string>();

export function terminalClientId(sessionId: string, sandboxId: string): string {
  const key = `sandbox-terminal-client:${sessionId}:${sandboxId}`;
  const cached = memoryClientIds.get(key);
  if (cached) return cached;
  try {
    const existing = localStorage.getItem(key);
    if (existing) return existing;
    const created = crypto.randomUUID();
    localStorage.setItem(key, created);
    return created;
  } catch {
    const created = crypto.randomUUID();
    memoryClientIds.set(key, created);
    return created;
  }
}

// ---------------------------------------------------------------------------
// SSE frame parsing (pure, no I/O). The terminal event stream is raw SSE
// (id:/event:/data: lines), not the JSON-in-data-field convention used by
// session.ts's chat streams, so it needs its own parser.
// ---------------------------------------------------------------------------

export interface TerminalEvent {
  seq: number;
  type: 'ready' | 'output' | 'overflow' | 'exit' | 'error';
  data: string;
}

export function parseSseBuffer(buffer: string): { events: TerminalEvent[]; rest: string } {
  const events: TerminalEvent[] = [];
  let start = 0;
  for (let sep = buffer.indexOf('\n\n', start); sep !== -1; sep = buffer.indexOf('\n\n', start)) {
    const frame = buffer.slice(start, sep);
    start = sep + 2;
    const event = parseFrame(frame);
    if (event) events.push(event);
  }
  return { events, rest: buffer.slice(start) };
}

function parseFrame(frame: string): TerminalEvent | null {
  let seq = 0;
  let type: TerminalEvent['type'] | null = null;
  const dataLines: string[] = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith('id:')) {
      const parsed = Number(line.slice(3).trim());
      seq = Number.isFinite(parsed) ? parsed : 0;
    } else if (line.startsWith('event:')) {
      type = line.slice(6).trim() as TerminalEvent['type'];
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim());
    }
  }
  if (!type) return null;
  // per the SSE spec, multiple data: lines in one frame join with "\n" — terminal
  // output/input payloads are single-line Base64 in practice, but stay spec-correct.
  return { seq, type, data: dataLines.join('\n') };
}

// ---------------------------------------------------------------------------
// REST calls (create/input/resize/close)
// ---------------------------------------------------------------------------

export class TerminalApiError extends Error {
  readonly status: number;
  readonly errorCode?: string;

  constructor(status: number, errorCode: string | undefined, message: string) {
    super(message);
    this.name = 'TerminalApiError';
    this.status = status;
    this.errorCode = errorCode;
  }
}

async function parseErrorBody(res: Response): Promise<{ errorCode?: string; message?: string }> {
  try {
    const body = await res.json();
    if (body && typeof body === 'object') {
      return {
        errorCode: typeof body.errorCode === 'string' ? body.errorCode : undefined,
        message: typeof body.message === 'string' ? body.message : undefined,
      };
    }
  } catch {
    // response wasn't JSON (e.g. proxy/gateway error page) — no errorCode/message available
  }
  return {};
}

async function terminalRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, { headers: getAuthHeaders(), ...options });
  if (!res.ok) {
    const { errorCode, message } = await parseErrorBody(res);
    throw new TerminalApiError(res.status, errorCode, message ?? `${res.status} ${res.statusText}`);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : (undefined as T);
}

export async function createTerminal(spec: { sessionId: string; sandboxId: string; rows: number; cols: number }):
  Promise<{ terminalId: string; recovered: boolean }> {
  const clientId = terminalClientId(spec.sessionId, spec.sandboxId);
  const res = await terminalRequest<{ terminal_id: string; recovered: boolean }>('/api/sessions/sandbox-terminal', {
    method: 'POST',
    body: JSON.stringify({
      session_id: spec.sessionId,
      sandbox_id: spec.sandboxId,
      client_id: clientId,
      rows: spec.rows,
      cols: spec.cols,
    }),
  });
  return { terminalId: res.terminal_id, recovered: res.recovered };
}

export async function sendTerminalInput(p: { sessionId: string; sandboxId: string; terminalId: string; dataBase64: string }): Promise<void> {
  await terminalRequest<void>('/api/sessions/sandbox-terminal/input', {
    method: 'POST',
    body: JSON.stringify({
      session_id: p.sessionId,
      sandbox_id: p.sandboxId,
      terminal_id: p.terminalId,
      data_base64: p.dataBase64,
    }),
  });
}

export async function resizeTerminal(p: { sessionId: string; sandboxId: string; terminalId: string; rows: number; cols: number }): Promise<void> {
  await terminalRequest<void>('/api/sessions/sandbox-terminal/size', {
    method: 'PUT',
    body: JSON.stringify({
      session_id: p.sessionId,
      sandbox_id: p.sandboxId,
      terminal_id: p.terminalId,
      rows: p.rows,
      cols: p.cols,
    }),
  });
}

export async function closeTerminal(p: { sessionId: string; sandboxId: string; terminalId: string }): Promise<void> {
  await terminalRequest<void>('/api/sessions/sandbox-terminal/close', {
    method: 'POST',
    body: JSON.stringify({
      session_id: p.sessionId,
      sandbox_id: p.sandboxId,
      terminal_id: p.terminalId,
    }),
  });
}

// ---------------------------------------------------------------------------
// SSE stream (fetch + ReadableStream, not XHR — the terminal stream is
// long-lived and open-ended, so we must not let a growing xhr.responseText
// buffer the whole session; only the unparsed `rest` tail is kept between
// reads).
// ---------------------------------------------------------------------------

export function openTerminalStream(p: {
  sessionId: string;
  sandboxId: string;
  terminalId: string;
  onEvent: (e: TerminalEvent) => void;
  onDisconnect: (err?: unknown) => void;
  lastEventId?: number;
}): { abort: () => void } {
  const controller = new AbortController();
  const qs = new URLSearchParams({
    'agent-session-id': p.sessionId,
    'sandbox-id': p.sandboxId,
    'terminal-id': p.terminalId,
  });
  const headers: Record<string, string> = { ...getAuthHeaders(), 'Accept': 'text/event-stream' };
  if (p.lastEventId !== undefined) headers['Last-Event-ID'] = String(p.lastEventId);

  (async () => {
    try {
      const res = await fetch(`${BASE}/api/sessions/sandbox-terminal/events?${qs.toString()}`, {
        headers,
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        const { errorCode, message } = await parseErrorBody(res);
        p.onDisconnect(new TerminalApiError(res.status, errorCode, message ?? `${res.status} ${res.statusText}`));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSseBuffer(buffer);
        buffer = parsed.rest;
        for (const event of parsed.events) p.onEvent(event);
      }
      p.onDisconnect();
    } catch (err) {
      if (controller.signal.aborted) return; // caller-initiated close, not a disconnect
      p.onDisconnect(err);
    }
  })();

  return { abort: () => controller.abort() };
}
