import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TerminalEvent } from '../../../api/terminal';
import type { SandboxTerminalSpec } from '../types';
import SandboxTerminalPanel from './SandboxTerminalPanel';

// The real xterm.js Terminal needs a canvas, which jsdom doesn't provide, so it is
// replaced with a minimal stand-in that records what the component does to it.
vi.mock('@xterm/xterm', () => {
  class Terminal {
    options: Record<string, unknown> = {};
    rows = 0;
    cols = 0;
    open = vi.fn();
    write = vi.fn();
    dispose = vi.fn();
    focus = vi.fn();
    loadAddon = vi.fn();
    onData = vi.fn();
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
    createTerminal: vi.fn(),
    sendTerminalInput: vi.fn(),
    resizeTerminal: vi.fn(),
    closeTerminal: vi.fn(),
    openTerminalStream: vi.fn(),
  };
});

import { TerminalApiError, closeTerminal, createTerminal, openTerminalStream } from '../../../api/terminal';

const mockCreateTerminal = vi.mocked(createTerminal);
const mockCloseTerminal = vi.mocked(closeTerminal);
const mockOpenTerminalStream = vi.mocked(openTerminalStream);

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
    // Sane defaults so tests that don't exercise close/input/resize directly
    // don't crash on an un-mocked `.catch()` of an undefined return value.
    mockCloseTerminal.mockResolvedValue(undefined);
  });

  it('shows a connecting indicator on mount', () => {
    mockCreateTerminal.mockReturnValue(new Promise(() => {})); // never resolves in this test

    renderPanel();

    expect(screen.getByText(/connecting/i)).toBeTruthy();
    // The mocked xterm Terminal reports rows/cols 0 (no real layout in jsdom), so the
    // fallback 24x80 size is what should reach createTerminal on mount.
    expect(mockCreateTerminal).toHaveBeenCalledTimes(1);
    expect(mockCreateTerminal).toHaveBeenCalledWith({
      sessionId: 'session-1',
      sandboxId: 'sandbox-1',
      rows: 24,
      cols: 80,
    });
  });

  it('shows the replaced message when create rejects with SANDBOX_REPLACED', async () => {
    mockCreateTerminal.mockRejectedValue(new TerminalApiError(409, 'SANDBOX_REPLACED', 'replaced'));

    renderPanel();

    expect(await screen.findByText('Sandbox 已替换，请使用最新 Sandbox 卡片')).toBeTruthy();
  });

  it('shows the expired message when create rejects with SANDBOX_EXPIRED', async () => {
    mockCreateTerminal.mockRejectedValue(new TerminalApiError(410, 'SANDBOX_EXPIRED', 'expired'));

    renderPanel();

    expect(await screen.findByText('Sandbox 已过期')).toBeTruthy();
  });

  it('shows a busy message when create rejects with status 429', async () => {
    mockCreateTerminal.mockRejectedValue(new TerminalApiError(429, 'TOO_MANY_REQUESTS', 'busy'));

    renderPanel();

    expect(await screen.findByText(/another client/i)).toBeTruthy();
  });

  it('shows a restart button after the shell exits', async () => {
    let onEvent: (e: TerminalEvent) => void = () => {};
    mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: false });
    mockOpenTerminalStream.mockImplementation((p) => {
      onEvent = p.onEvent;
      return { abort: vi.fn() };
    });

    renderPanel();
    await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalled());

    act(() => {
      onEvent({ seq: 1, type: 'exit', data: '0' });
    });

    expect(await screen.findByRole('button', { name: /restart/i })).toBeTruthy();
  });

  it('closes the terminal exactly once on unmount', async () => {
    mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: false });
    mockOpenTerminalStream.mockReturnValue({ abort: vi.fn() });
    mockCloseTerminal.mockResolvedValue(undefined);

    const { unmount } = renderPanel();
    await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalled());

    unmount();

    expect(mockCloseTerminal).toHaveBeenCalledTimes(1);
    expect(mockCloseTerminal).toHaveBeenCalledWith({
      sessionId: 'session-1',
      sandboxId: 'sandbox-1',
      terminalId: 'term-1',
    });
  });

  it('caps the reconnect backoff window at 30s instead of retrying forever', async () => {
    vi.useFakeTimers();
    mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: false });
    // Every (re)connect attempt disconnects again immediately, so the component must
    // exhaust the 30s window on its own rather than ever reaching a stable connection.
    mockOpenTerminalStream.mockImplementation((p) => {
      p.onDisconnect(undefined);
      return { abort: vi.fn() };
    });

    renderPanel();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(40000);
    });

    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy();
    expect(screen.queryByText(/connecting/i)).toBeNull();
  });

  it('aborts the previous stream before opening a new one on restart', async () => {
    const abortSpies: Array<ReturnType<typeof vi.fn>> = [];
    let onEvent: (e: TerminalEvent) => void = () => {};
    mockCreateTerminal
      .mockResolvedValueOnce({ terminalId: 'term-1', recovered: false })
      .mockResolvedValueOnce({ terminalId: 'term-2', recovered: false });
    mockOpenTerminalStream.mockImplementation((p) => {
      onEvent = p.onEvent;
      const abort = vi.fn();
      abortSpies.push(abort);
      return { abort };
    });

    renderPanel();
    await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalledTimes(1));

    act(() => {
      onEvent({ seq: 1, type: 'exit', data: '0' });
    });
    const restartButton = await screen.findByRole('button', { name: /restart/i });

    fireEvent.click(restartButton);

    // Aborting the first stream and issuing the second createTerminal call both happen
    // synchronously inside connect(), before the second createTerminal promise settles.
    expect(abortSpies[0]).toHaveBeenCalledTimes(1);
    expect(mockCreateTerminal).toHaveBeenCalledTimes(2);

    await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalledTimes(2));
  });

  describe('lastEventId branching', () => {
    it('opens the stream with lastEventId 0 when createTerminal reports a recovered terminal', async () => {
      mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: true });
      mockOpenTerminalStream.mockReturnValue({ abort: vi.fn() });

      renderPanel();

      await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalledWith(
        expect.objectContaining({ terminalId: 'term-1', lastEventId: 0 }),
      ));
    });

    it('opens the stream with lastEventId undefined when createTerminal reports a fresh terminal', async () => {
      mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: false });
      mockOpenTerminalStream.mockReturnValue({ abort: vi.fn() });

      renderPanel();

      await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalledWith(
        expect.objectContaining({ terminalId: 'term-1', lastEventId: undefined }),
      ));
    });

    it('reconnects using the last seen event seq after a disconnect', async () => {
      let onEvent: (e: TerminalEvent) => void = () => {};
      let onDisconnect: (err?: unknown) => void = () => {};
      mockCreateTerminal.mockResolvedValue({ terminalId: 'term-1', recovered: false });
      mockOpenTerminalStream.mockImplementation((p) => {
        onEvent = p.onEvent;
        onDisconnect = p.onDisconnect;
        return { abort: vi.fn() };
      });

      renderPanel();
      await waitFor(() => expect(mockOpenTerminalStream).toHaveBeenCalledTimes(1));

      act(() => {
        onEvent({ seq: 5, type: 'output', data: btoa('hi') });
      });
      act(() => {
        onDisconnect(undefined);
      });

      await waitFor(
        () => expect(mockOpenTerminalStream).toHaveBeenCalledTimes(2),
        { timeout: 2000 },
      );
      expect(mockOpenTerminalStream.mock.calls[1][0]).toEqual(
        expect.objectContaining({ terminalId: 'term-1', lastEventId: 5 }),
      );
    });
  });
});
