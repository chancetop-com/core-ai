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
// REST calls: ticket minting + activity heartbeat. The old create/input/
// size/close/events REST surface no longer exists on the server (v2) — the
// data plane moved to a ticketed WebSocket straight to the terminal gateway.
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

export async function requestTicket(p: { sessionId: string; sandboxId: string }): Promise<{ ticket: string; gatewayUrl: string }> {
  const clientId = terminalClientId(p.sessionId, p.sandboxId);
  const res = await terminalRequest<{ ticket: string; gateway_url: string }>('/api/sessions/sandbox-terminal/ticket', {
    method: 'POST',
    body: JSON.stringify({
      session_id: p.sessionId,
      sandbox_id: p.sandboxId,
      client_id: clientId,
    }),
  });
  return { ticket: res.ticket, gatewayUrl: res.gateway_url };
}

// Renewal heartbeat. Best-effort by design: a dropped beat just means the
// idle-reclaim window on the runtime side may run a little sooner, never a
// user-visible failure, so failures are swallowed here rather than pushed
// onto callers.
export async function postTerminalActivity(sessionId: string): Promise<void> {
  try {
    await terminalRequest<void>('/api/sessions/sandbox-terminal/activity', {
      method: 'POST',
      body: JSON.stringify({ session_id: sessionId }),
    });
  } catch {
    // best-effort heartbeat; see doc comment above
  }
}

// ---------------------------------------------------------------------------
// WebSocket frame parsing (pure, no I/O). Server -> client frames are JSON
// text messages tagged by "t"; unknown/malformed frames are dropped rather
// than throwing, since a forward-incompatible frame from a newer gateway
// must not tear down the connection.
// ---------------------------------------------------------------------------

export type TerminalFrame =
  | { type: 'ready'; terminalId: string; recovered: boolean }
  | { type: 'output'; seq: number; data: string }
  | { type: 'overflow' }
  | { type: 'exit'; code: number }
  | { type: 'error'; message: string };

export function parseTerminalFrame(data: string): TerminalFrame | null {
  let raw: unknown;
  try {
    raw = JSON.parse(data);
  } catch {
    return null;
  }
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  switch (obj.t) {
    case 'ready':
      if (typeof obj.id !== 'string' || typeof obj.recovered !== 'boolean') return null;
      return { type: 'ready', terminalId: obj.id, recovered: obj.recovered };
    case 'o':
      if (typeof obj.seq !== 'number' || typeof obj.d !== 'string') return null;
      return { type: 'output', seq: obj.seq, data: obj.d };
    case 'overflow':
      return { type: 'overflow' };
    case 'exit':
      if (typeof obj.code !== 'number') return null;
      return { type: 'exit', code: obj.code };
    case 'err':
      if (typeof obj.m !== 'string') return null;
      return { type: 'error', message: obj.m };
    default:
      return null; // unknown/future frame tag — ignore rather than fail
  }
}

// Client -> server frame encoders (pure). Extracted so the wire format has a
// single definition, testable without spinning up a real WebSocket.
export function encodeInputFrame(dataBase64: string): string {
  return JSON.stringify({ t: 'i', d: dataBase64 });
}

export function encodeResizeFrame(rows: number, cols: number): string {
  return JSON.stringify({ t: 'resize', rows, cols });
}

// ---------------------------------------------------------------------------
// WebSocket transport. Every connection attempt (a fresh mount, a manual
// restart, or a reconnect after a drop) is handed a freshly-minted ticket by
// the caller — tickets are one-shot and expire in 30s, so none of this module
// retries a stale ticket itself.
// ---------------------------------------------------------------------------

export interface TerminalSocket {
  sendInput: (dataBase64: string) => void;
  sendResize: (rows: number, cols: number) => void;
  close: () => void;
}

export function openTerminalSocket(p: {
  gatewayUrl: string;
  ticket: string;
  clientId: string;
  rows: number;
  cols: number;
  lastSeq?: number;
  onFrame: (frame: TerminalFrame) => void;
  onClose: (code: number, wasClean: boolean) => void;
}): TerminalSocket {
  const qs = new URLSearchParams({
    ticket: p.ticket,
    client_id: p.clientId,
    rows: String(p.rows),
    cols: String(p.cols),
  });
  if (p.lastSeq !== undefined) qs.set('last_seq', String(p.lastSeq));

  const base = p.gatewayUrl.replace(/\/+$/, '');
  const ws = new WebSocket(`${base}/terminal?${qs.toString()}`);
  let deliberateClose = false;

  ws.onmessage = (ev) => {
    if (typeof ev.data !== 'string') return; // server only ever sends JSON text frames
    const frame = parseTerminalFrame(ev.data);
    if (frame) p.onFrame(frame);
  };
  ws.onclose = (ev) => {
    if (deliberateClose) return; // caller-initiated close, not a disconnect
    p.onClose(ev.code, ev.wasClean);
  };

  return {
    sendInput(dataBase64: string) {
      if (ws.readyState !== WebSocket.OPEN) return; // best effort; dropped keystrokes surface via the next output desync, none expected in practice
      ws.send(encodeInputFrame(dataBase64));
    },
    sendResize(rows: number, cols: number) {
      if (ws.readyState !== WebSocket.OPEN) return;
      ws.send(encodeResizeFrame(rows, cols));
    },
    close() {
      deliberateClose = true;
      ws.close();
    },
  };
}
