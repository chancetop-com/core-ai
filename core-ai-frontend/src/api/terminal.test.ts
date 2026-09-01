import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  encodeInputFrame,
  encodeResizeFrame,
  openTerminalSocket,
  parseTerminalFrame,
  requestTicket,
  postTerminalActivity,
  terminalClientId,
  TerminalApiError,
} from './terminal';

describe('parseTerminalFrame', () => {
  it('parses a ready frame', () => {
    expect(parseTerminalFrame('{"t":"ready","id":"term-1","recovered":true}')).toEqual({
      type: 'ready',
      terminalId: 'term-1',
      recovered: true,
    });
  });

  it('parses an output frame', () => {
    expect(parseTerminalFrame('{"t":"o","seq":5,"d":"aGVsbG8="}')).toEqual({
      type: 'output',
      seq: 5,
      data: 'aGVsbG8=',
    });
  });

  it('parses an overflow frame', () => {
    expect(parseTerminalFrame('{"t":"overflow"}')).toEqual({ type: 'overflow' });
  });

  it('parses an exit frame', () => {
    expect(parseTerminalFrame('{"t":"exit","code":0}')).toEqual({ type: 'exit', code: 0 });
  });

  it('parses an err frame', () => {
    expect(parseTerminalFrame('{"t":"err","m":"boom"}')).toEqual({ type: 'error', message: 'boom' });
  });

  it('returns null for malformed JSON', () => {
    expect(parseTerminalFrame('not json')).toBeNull();
  });

  it('returns null for a JSON value that is not an object', () => {
    expect(parseTerminalFrame('42')).toBeNull();
    expect(parseTerminalFrame('null')).toBeNull();
  });

  it('returns null for an unknown frame tag', () => {
    expect(parseTerminalFrame('{"t":"future-frame","x":1}')).toBeNull();
  });

  it('returns null when a known tag is missing its required fields', () => {
    expect(parseTerminalFrame('{"t":"ready","id":"term-1"}')).toBeNull();
    expect(parseTerminalFrame('{"t":"o","seq":5}')).toBeNull();
    expect(parseTerminalFrame('{"t":"exit"}')).toBeNull();
    expect(parseTerminalFrame('{"t":"err"}')).toBeNull();
  });
});

describe('frame encoders', () => {
  it('encodes an input frame', () => {
    expect(encodeInputFrame('aGVsbG8=')).toBe('{"t":"i","d":"aGVsbG8="}');
  });

  it('encodes a resize frame', () => {
    expect(encodeResizeFrame(24, 80)).toBe('{"t":"resize","rows":24,"cols":80}');
  });
});

function createStorageMock() {
  const store = new Map<string, string>();
  return {
    getItem: vi.fn((key: string) => (store.has(key) ? (store.get(key) as string) : null)),
    setItem: vi.fn((key: string, value: string) => {
      store.set(key, value);
    }),
    removeItem: vi.fn((key: string) => {
      store.delete(key);
    }),
  };
}

describe('terminalClientId', () => {
  it('is stable across repeated calls for the same session/sandbox', () => {
    const first = terminalClientId('session-stable', 'sandbox-stable');
    const second = terminalClientId('session-stable', 'sandbox-stable');

    expect(first).toBe(second);
  });

  it('is distinct per (sessionId, sandboxId) pair', () => {
    const a = terminalClientId('session-A', 'sandbox-A');
    const b = terminalClientId('session-B', 'sandbox-B');

    expect(a).not.toBe(b);
  });

  it('persists the generated id via localStorage under the documented key', () => {
    const storage = createStorageMock();
    vi.stubGlobal('localStorage', storage);

    const first = terminalClientId('session-persist', 'sandbox-persist');
    const second = terminalClientId('session-persist', 'sandbox-persist');

    expect(second).toBe(first);
    expect(storage.setItem).toHaveBeenCalledTimes(1);
    expect(storage.setItem).toHaveBeenCalledWith('sandbox-terminal-client:session-persist:sandbox-persist', first);
  });

  it('falls back to an in-memory id when localStorage throws', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => {
        throw new Error('storage disabled');
      }),
      setItem: vi.fn(() => {
        throw new Error('storage disabled');
      }),
      removeItem: vi.fn(),
    });

    const first = terminalClientId('session-nostorage', 'sandbox-nostorage');
    const second = terminalClientId('session-nostorage', 'sandbox-nostorage');

    expect(first).toBe(second);
    expect(typeof first).toBe('string');
    expect(first.length).toBeGreaterThan(0);
  });
});

describe('requestTicket', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    // This environment's global `localStorage` (Node's built-in, not jsdom's)
    // lacks a working getItem — stub a real in-memory Storage so getAuthHeaders()
    // (called internally by every REST helper) doesn't throw.
    vi.stubGlobal('localStorage', createStorageMock());
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.unstubAllGlobals();
  });

  it('posts session/sandbox/client id and maps the response to camelCase', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: async () => JSON.stringify({ ticket: 'tkt-1', gateway_url: 'wss://gw.example' }),
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    const result = await requestTicket({ sessionId: 'session-x', sandboxId: 'sandbox-x' });

    expect(result).toEqual({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/sessions/sandbox-terminal/ticket');
    expect(options.method).toBe('POST');
    const body = JSON.parse(options.body);
    expect(body.session_id).toBe('session-x');
    expect(body.sandbox_id).toBe('sandbox-x');
    expect(typeof body.client_id).toBe('string');
    expect(body.client_id.length).toBeGreaterThan(0);
  });

  it('throws a TerminalApiError carrying the server errorCode on failure', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      statusText: 'Conflict',
      json: async () => ({ errorCode: 'SANDBOX_REPLACED', message: 'replaced' }),
    }) as unknown as typeof fetch;

    await expect(requestTicket({ sessionId: 's', sandboxId: 'b' })).rejects.toMatchObject({
      name: 'TerminalApiError',
      status: 409,
      errorCode: 'SANDBOX_REPLACED',
    });
  });

  it('rejects with the underlying error on a network failure', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('network down')) as unknown as typeof fetch;

    await expect(requestTicket({ sessionId: 's', sandboxId: 'b' })).rejects.toBeInstanceOf(TypeError);
  });
});

describe('postTerminalActivity', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    vi.stubGlobal('localStorage', createStorageMock());
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.unstubAllGlobals();
  });

  it('posts the session id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, text: async () => '' });
    global.fetch = fetchMock as unknown as typeof fetch;

    await postTerminalActivity('session-y');

    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/sessions/sandbox-terminal/activity');
    expect(JSON.parse(options.body)).toEqual({ session_id: 'session-y' });
  });

  it('never throws, even when the request fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('boom')) as unknown as typeof fetch;

    await expect(postTerminalActivity('session-y')).resolves.toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// openTerminalSocket — a minimal fake WebSocket standing in for the browser
// API. It records the constructed URL and every sent frame, and lets tests
// drive onmessage/onclose to exercise the wiring.
// ---------------------------------------------------------------------------

class FakeWebSocket {
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.OPEN;
  url: string;
  sent: string[] = [];
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: { code: number; wasClean: boolean }) => void) | null = null;
  closeCalls = 0;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {
    this.closeCalls += 1;
    this.readyState = FakeWebSocket.CLOSED;
  }
}

describe('openTerminalSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('dials the gateway with ticket/client_id/rows/cols and omits last_seq when not resuming', () => {
    openTerminalSocket({
      gatewayUrl: 'wss://gw.example',
      ticket: 'tkt-1',
      clientId: 'client-1',
      rows: 24,
      cols: 80,
      onFrame: () => {},
      onClose: () => {},
    });

    const ws = FakeWebSocket.instances[0];
    const url = new URL(ws.url);
    expect(`${url.origin}${url.pathname}`).toBe('wss://gw.example/terminal');
    expect(url.searchParams.get('ticket')).toBe('tkt-1');
    expect(url.searchParams.get('client_id')).toBe('client-1');
    expect(url.searchParams.get('rows')).toBe('24');
    expect(url.searchParams.get('cols')).toBe('80');
    expect(url.searchParams.has('last_seq')).toBe(false);
  });

  it('includes last_seq when resuming', () => {
    openTerminalSocket({
      gatewayUrl: 'wss://gw.example',
      ticket: 'tkt-1',
      clientId: 'client-1',
      rows: 24,
      cols: 80,
      lastSeq: 42,
      onFrame: () => {},
      onClose: () => {},
    });

    const url = new URL(FakeWebSocket.instances[0].url);
    expect(url.searchParams.get('last_seq')).toBe('42');
  });

  it('strips a trailing slash from the gateway base URL', () => {
    openTerminalSocket({
      gatewayUrl: 'wss://gw.example/',
      ticket: 't',
      clientId: 'c',
      rows: 24,
      cols: 80,
      onFrame: () => {},
      onClose: () => {},
    });

    expect(FakeWebSocket.instances[0].url.startsWith('wss://gw.example/terminal?')).toBe(true);
  });

  it('delivers parsed frames to onFrame and drops unparseable ones', () => {
    const onFrame = vi.fn();
    openTerminalSocket({
      gatewayUrl: 'wss://gw.example', ticket: 't', clientId: 'c', rows: 24, cols: 80,
      onFrame, onClose: () => {},
    });
    const ws = FakeWebSocket.instances[0];

    ws.onmessage?.({ data: '{"t":"ready","id":"term-1","recovered":false}' });
    ws.onmessage?.({ data: 'garbage' });
    ws.onmessage?.({ data: '{"t":"o","seq":1,"d":"aGk="}' });

    expect(onFrame).toHaveBeenCalledTimes(2);
    expect(onFrame).toHaveBeenNthCalledWith(1, { type: 'ready', terminalId: 'term-1', recovered: false });
    expect(onFrame).toHaveBeenNthCalledWith(2, { type: 'output', seq: 1, data: 'aGk=' });
  });

  it('sends input and resize frames only while open', () => {
    const socket = openTerminalSocket({
      gatewayUrl: 'wss://gw.example', ticket: 't', clientId: 'c', rows: 24, cols: 80,
      onFrame: () => {}, onClose: () => {},
    });
    const ws = FakeWebSocket.instances[0];

    socket.sendInput('aGk=');
    socket.sendResize(30, 100);

    expect(ws.sent).toEqual([
      '{"t":"i","d":"aGk="}',
      '{"t":"resize","rows":30,"cols":100}',
    ]);

    ws.readyState = FakeWebSocket.CLOSED;
    socket.sendInput('bm9wZQ==');
    expect(ws.sent).toHaveLength(2); // dropped: socket no longer open
  });

  it('forwards an unexpected close to onClose with code and cleanliness', () => {
    const onClose = vi.fn();
    openTerminalSocket({
      gatewayUrl: 'wss://gw.example', ticket: 't', clientId: 'c', rows: 24, cols: 80,
      onFrame: () => {}, onClose,
    });
    const ws = FakeWebSocket.instances[0];

    ws.onclose?.({ code: 4003, wasClean: true });

    expect(onClose).toHaveBeenCalledWith(4003, true);
  });

  it('suppresses onClose for a deliberate close() call', () => {
    const onClose = vi.fn();
    const socket = openTerminalSocket({
      gatewayUrl: 'wss://gw.example', ticket: 't', clientId: 'c', rows: 24, cols: 80,
      onFrame: () => {}, onClose,
    });
    const ws = FakeWebSocket.instances[0];

    socket.close();
    ws.onclose?.({ code: 1000, wasClean: true }); // simulates the browser firing close after our close() call

    expect(ws.closeCalls).toBe(1);
    expect(onClose).not.toHaveBeenCalled();
  });
});

// TerminalApiError is exercised transitively above; this locks its own shape.
describe('TerminalApiError', () => {
  it('carries status, errorCode and message', () => {
    const err = new TerminalApiError(404, 'SANDBOX_EXPIRED', 'expired');
    expect(err.name).toBe('TerminalApiError');
    expect(err.status).toBe(404);
    expect(err.errorCode).toBe('SANDBOX_EXPIRED');
    expect(err.message).toBe('expired');
  });
});
