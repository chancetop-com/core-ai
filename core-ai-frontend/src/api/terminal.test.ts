import { describe, it, expect, vi } from 'vitest';
import { parseSseBuffer, terminalClientId } from './terminal';

describe('parseSseBuffer', () => {
  it('parses a single complete event', () => {
    const { events, rest } = parseSseBuffer('id: 1\nevent: output\ndata: aGVsbG8=\n\n');

    expect(events).toEqual([{ seq: 1, type: 'output', data: 'aGVsbG8=' }]);
    expect(rest).toBe('');
  });

  it('returns an incomplete frame as rest, then parses it once the boundary arrives', () => {
    const first = parseSseBuffer('id: 2\nevent: out');
    expect(first.events).toEqual([]);
    expect(first.rest).toBe('id: 2\nevent: out');

    const second = parseSseBuffer(`${first.rest}put\ndata: Zm9v\n\n`);
    expect(second.events).toEqual([{ seq: 2, type: 'output', data: 'Zm9v' }]);
    expect(second.rest).toBe('');
  });

  it('parses id/event/data fields regardless of line order', () => {
    const { events } = parseSseBuffer('event: ready\ndata: ok\nid: 7\n\n');

    expect(events).toEqual([{ seq: 7, type: 'ready', data: 'ok' }]);
  });

  it('defaults seq to 0 when the id line is missing', () => {
    const { events } = parseSseBuffer('event: exit\ndata: 0\n\n');

    expect(events).toEqual([{ seq: 0, type: 'exit', data: '0' }]);
  });

  it('parses multiple events delivered in one chunk', () => {
    const buffer = 'id: 1\nevent: output\ndata: aGVsbG8=\n\n' + 'id: 2\nevent: output\ndata: d29ybGQ=\n\n';

    const { events, rest } = parseSseBuffer(buffer);

    expect(events).toEqual([
      { seq: 1, type: 'output', data: 'aGVsbG8=' },
      { seq: 2, type: 'output', data: 'd29ybGQ=' },
    ]);
    expect(rest).toBe('');
  });

  it('joins multiple data: lines in one frame with "\\n" per the SSE spec', () => {
    const { events } = parseSseBuffer('id: 3\nevent: output\ndata: line1\ndata: line2\n\n');

    expect(events).toEqual([{ seq: 3, type: 'output', data: 'line1\nline2' }]);
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
