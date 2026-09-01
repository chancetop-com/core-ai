import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TerminalFrame, TerminalSocket } from '../../../api/terminal';
import type { SandboxTerminalSpec } from '../types';
import SandboxTerminalPanel from './SandboxTerminalPanel';

// The real xterm.js Terminal needs a canvas, which jsdom doesn't provide, so it is
// replaced with a minimal stand-in that records what the component does to it and
// lets tests simulate a keystroke via triggerData().
vi.mock('@xterm/xterm', () => {
  class Terminal {
    static instances: Terminal[] = [];
    options: Record<string, unknown> = {};
    rows = 0;
    cols = 0;
    open = vi.fn();
    write = vi.fn();
    dispose = vi.fn();
    focus = vi.fn();
    loadAddon = vi.fn();
    private handler: ((data: string) => void) | null = null;

    constructor() {
      Terminal.instances.push(this);
    }

    onData(cb: (data: string) => void) {
      this.handler = cb;
    }

    triggerData(data: string) {
      this.handler?.(data);
    }
  }
  return { Terminal };
});

vi.mock('@xterm/addon-fit', () => {
  class FitAddon {
    fit = vi.fn();
  }
  return { FitAddon };
});

vi.mock('../../../api/terminal', () => {
  class TerminalApiError extends Error {
    status: number;
    errorCode?: string;
    constructor(status: number, errorCode: string | undefined, message: string) {
      super(message);
      this.name = 'TerminalApiError';
      this.status = status;
      this.errorCode = errorCode;
    }
  }
  return {
    TerminalApiError,
    terminalClientId: vi.fn((sessionId: string, sandboxId: string) => `client-${sessionId}-${sandboxId}`),
    requestTicket: vi.fn(),
    postTerminalActivity: vi.fn(),
    openTerminalSocket: vi.fn(),
  };
});

import { Terminal as MockedTerminal } from '@xterm/xterm';
import {
  TerminalApiError,
  openTerminalSocket,
  postTerminalActivity,
  requestTicket,
} from '../../../api/terminal';

const mockRequestTicket = vi.mocked(requestTicket);
const mockPostTerminalActivity = vi.mocked(postTerminalActivity);
const mockOpenTerminalSocket = vi.mocked(openTerminalSocket);

interface MockTerminalHandle {
  triggerData: (data: string) => void;
}
const TerminalMock = MockedTerminal as unknown as { instances: MockTerminalHandle[] };
function lastTerminalInstance(): MockTerminalHandle {
  return TerminalMock.instances[TerminalMock.instances.length - 1];
}

function fakeSocket(overrides: Partial<TerminalSocket> = {}): TerminalSocket {
  return {
    sendInput: vi.fn(),
    sendResize: vi.fn(),
    close: vi.fn(),
    ...overrides,
  };
}

const sandbox: SandboxTerminalSpec = {
  sandboxId: 'sandbox-1',
  sessionId: 'session-1',
  hostname: 'sandbox-host',
  ip: '10.0.0.5',
  image: 'python:3.11',
};

function renderPanel() {
  return render(<SandboxTerminalPanel sandbox={sandbox} onClose={vi.fn()} />);
}

describe('SandboxTerminalPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    TerminalMock.instances.length = 0;
    mockPostTerminalActivity.mockResolvedValue(undefined);
  });

  it('shows a connecting indicator on mount and requests a ticket for session/sandbox', () => {
    mockRequestTicket.mockReturnValue(new Promise(() => {})); // never resolves in this test

    renderPanel();

    expect(screen.getByText(/connecting/i)).toBeTruthy();
    expect(mockRequestTicket).toHaveBeenCalledTimes(1);
    expect(mockRequestTicket).toHaveBeenCalledWith({ sessionId: 'session-1', sandboxId: 'sandbox-1' });
  });

  it('dials the gateway socket with the fallback size, persisted clientId, and no lastSeq on initial mount', async () => {
    mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    mockOpenTerminalSocket.mockReturnValue(fakeSocket());

    renderPanel();

    // The mocked xterm Terminal reports rows/cols 0 (no real layout in jsdom), so the
    // fallback 24x80 size is what should reach openTerminalSocket.
    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledWith(expect.objectContaining({
      gatewayUrl: 'wss://gw.example',
      ticket: 'tkt-1',
      clientId: 'client-session-1-sandbox-1',
      rows: 24,
      cols: 80,
      lastSeq: undefined,
    })));
  });

  it('shows the replaced message when the ticket request rejects with SANDBOX_REPLACED', async () => {
    mockRequestTicket.mockRejectedValue(new TerminalApiError(409, 'SANDBOX_REPLACED', 'replaced'));

    renderPanel();

    expect(await screen.findByText('Sandbox 已替换，请使用最新 Sandbox 卡片')).toBeTruthy();
  });

  it('shows the expired message when the ticket request rejects with SANDBOX_EXPIRED', async () => {
    mockRequestTicket.mockRejectedValue(new TerminalApiError(410, 'SANDBOX_EXPIRED', 'expired'));

    renderPanel();

    expect(await screen.findByText('Sandbox 已过期')).toBeTruthy();
  });

  it('shows a busy message when the socket closes with code 4003', async () => {
    let onClose: (code: number, wasClean: boolean) => void = () => {};
    mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    mockOpenTerminalSocket.mockImplementation((p) => {
      onClose = p.onClose;
      return fakeSocket();
    });

    renderPanel();
    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalled());

    act(() => {
      onClose(4003, true);
    });

    expect(await screen.findByText(/another client/i)).toBeTruthy();
  });

  it('shows a restart button after the shell exits', async () => {
    let onFrame: (f: TerminalFrame) => void = () => {};
    mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    mockOpenTerminalSocket.mockImplementation((p) => {
      onFrame = p.onFrame;
      return fakeSocket();
    });

    renderPanel();
    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalled());

    act(() => {
      onFrame({ type: 'exit', code: 0 });
    });

    expect(await screen.findByRole('button', { name: /restart/i })).toBeTruthy();
  });

  it('closes the socket exactly once on unmount, with no server-side close call', async () => {
    const closeSpy = vi.fn();
    mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    mockOpenTerminalSocket.mockReturnValue(fakeSocket({ close: closeSpy }));

    const { unmount } = renderPanel();
    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalled());

    unmount();

    expect(closeSpy).toHaveBeenCalledTimes(1);
  });

  it('caps the reconnect backoff window at 30s instead of retrying forever', async () => {
    vi.useFakeTimers();
    mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
    // Every (re)connect attempt closes with a non-busy code immediately, so the
    // component must exhaust the 30s window on its own rather than ever reaching
    // a stable connection.
    mockOpenTerminalSocket.mockImplementation((p) => {
      p.onClose(1011, false);
      return fakeSocket();
    });

    renderPanel();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(40000);
    });

    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy();
    expect(screen.queryByText(/connecting/i)).toBeNull();
  });

  it('closes the previous socket and mints a fresh ticket before opening a new one on restart', async () => {
    const closeSpies: Array<ReturnType<typeof vi.fn>> = [];
    let onFrame: (f: TerminalFrame) => void = () => {};
    mockRequestTicket
      .mockResolvedValueOnce({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' })
      .mockResolvedValueOnce({ ticket: 'tkt-2', gatewayUrl: 'wss://gw.example' });
    mockOpenTerminalSocket.mockImplementation((p) => {
      onFrame = p.onFrame;
      const close = vi.fn();
      closeSpies.push(close);
      return fakeSocket({ close });
    });

    renderPanel();
    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(1));

    act(() => {
      onFrame({ type: 'exit', code: 0 });
    });
    const restartButton = await screen.findByRole('button', { name: /restart/i });

    fireEvent.click(restartButton);

    // Closing the first socket and minting the second ticket both happen synchronously
    // inside connect(), before the second requestTicket promise settles.
    expect(closeSpies[0]).toHaveBeenCalledTimes(1);
    expect(mockRequestTicket).toHaveBeenCalledTimes(2);

    await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(2));
  });

  describe('lastSeq resume', () => {
    it('reconnects using the last seen output sequence after a disconnect', async () => {
      let onFrame: (f: TerminalFrame) => void = () => {};
      let onClose: (code: number, wasClean: boolean) => void = () => {};
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        onFrame = p.onFrame;
        onClose = p.onClose;
        return fakeSocket();
      });

      renderPanel();
      await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(1));

      act(() => {
        onFrame({ type: 'output', seq: 5, data: btoa('hi') });
      });
      act(() => {
        onClose(1011, false);
      });

      await waitFor(
        () => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(2),
        { timeout: 2000 },
      );
      expect(mockOpenTerminalSocket.mock.calls[1][0]).toEqual(
        expect.objectContaining({ ticket: 'tkt-1', lastSeq: 5 }),
      );
    });

    it('ignores a seq of 0 on an output frame so it never overwrites a real resume point', async () => {
      let onFrame: (f: TerminalFrame) => void = () => {};
      let onClose: (code: number, wasClean: boolean) => void = () => {};
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        onFrame = p.onFrame;
        onClose = p.onClose;
        return fakeSocket();
      });

      renderPanel();
      await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(1));

      act(() => {
        onFrame({ type: 'output', seq: 5, data: btoa('hi') });
        onFrame({ type: 'output', seq: 0, data: btoa('bogus') });
      });
      act(() => {
        onClose(1011, false);
      });

      await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(2));
      expect(mockOpenTerminalSocket.mock.calls[1][0]).toEqual(
        expect.objectContaining({ lastSeq: 5 }),
      );
    });

    it('treats the ready frame\'s recovered flag as informational only (no branching on it)', async () => {
      let onFrame: (f: TerminalFrame) => void = () => {};
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        onFrame = p.onFrame;
        return fakeSocket();
      });

      renderPanel();
      await waitFor(() => expect(mockOpenTerminalSocket).toHaveBeenCalledTimes(1));
      // recovered:true carries no special handling — the ring-replay decision already
      // happened via last_seq in the connect URL, before this frame ever arrives.
      act(() => {
        onFrame({ type: 'ready', terminalId: 'term-1', recovered: true });
      });

      expect(screen.queryByText(/connecting/i)).toBeNull();
      expect(screen.queryByRole('status')).toBeNull();
    });
  });

  describe('renewal heartbeat', () => {
    it('posts one activity heartbeat after 60s when input occurred since the last beat', async () => {
      vi.useFakeTimers();
      let onFrame: (f: TerminalFrame) => void = () => {};
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        onFrame = p.onFrame;
        return fakeSocket();
      });

      renderPanel();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      act(() => {
        onFrame({ type: 'ready', terminalId: 'term-1', recovered: false });
      });
      act(() => {
        lastTerminalInstance().triggerData('l');
      });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000);
      });

      expect(mockPostTerminalActivity).toHaveBeenCalledTimes(1);
      expect(mockPostTerminalActivity).toHaveBeenCalledWith('session-1');
    });

    it('posts no activity heartbeat when no input occurred since connecting', async () => {
      vi.useFakeTimers();
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        p.onFrame({ type: 'ready', terminalId: 'term-1', recovered: false });
        return fakeSocket();
      });

      renderPanel();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000);
      });

      expect(mockPostTerminalActivity).not.toHaveBeenCalled();
    });

    it('does not post a heartbeat while disconnected even if input occurred earlier', async () => {
      vi.useFakeTimers();
      let onFrame: (f: TerminalFrame) => void = () => {};
      let onClose: (code: number, wasClean: boolean) => void = () => {};
      mockRequestTicket.mockResolvedValue({ ticket: 'tkt-1', gatewayUrl: 'wss://gw.example' });
      mockOpenTerminalSocket.mockImplementation((p) => {
        onFrame = p.onFrame;
        onClose = p.onClose;
        return fakeSocket();
      });

      renderPanel();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      act(() => {
        onFrame({ type: 'ready', terminalId: 'term-1', recovered: false });
      });
      act(() => {
        lastTerminalInstance().triggerData('l');
      });
      act(() => {
        onClose(1011, false); // drops to 'reconnecting' before the 60s beat fires
      });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000);
      });

      expect(mockPostTerminalActivity).not.toHaveBeenCalled();
    });
  });
});
