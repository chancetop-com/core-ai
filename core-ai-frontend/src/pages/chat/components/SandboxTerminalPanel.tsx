import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import '@xterm/xterm/css/xterm.css';
import { Loader2, RotateCw, Terminal as TerminalIcon, X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  TerminalApiError,
  openTerminalSocket,
  postTerminalActivity,
  requestTicket,
  terminalClientId,
  type TerminalFrame,
  type TerminalSocket,
} from '../../../api/terminal';
import type { SandboxTerminalSpec } from '../types';

interface Props {
  sandbox: SandboxTerminalSpec;
  onClose: () => void;
}

// ---------------------------------------------------------------------------
// State machine: connecting -> connected -> reconnecting -> connected, with
// failed / exited / replaced / expired / busy as terminal (non-connected)
// states. See docs/superpowers/specs/2026-08-28-sandbox-interactive-terminal-design.md.
// (v2: transport is a ticketed WebSocket direct to the terminal gateway,
// instead of v1's server-proxied REST+SSE — the state machine itself carries
// over unchanged.)
// ---------------------------------------------------------------------------
type PanelState = 'connecting' | 'connected' | 'reconnecting' | 'failed' | 'exited' | 'replaced' | 'expired' | 'busy';

const RECONNECT_DELAYS_MS = [500, 1000, 2000, 5000];
const RECONNECT_WINDOW_MS = 30000;
const INPUT_FLUSH_MS = 16;
const RESIZE_DEBOUNCE_MS = 200;
const HEARTBEAT_INTERVAL_MS = 60000;
const FALLBACK_ROWS = 24;
const FALLBACK_COLS = 80;
const WS_CLOSE_BUSY = 4003;

// Classifies a ticket-mint REST failure. WS-connect-time busy (another client
// already attached) is reported via close code 4003, not a REST error — see
// handleClose below.
function classifyError(err: unknown): PanelState {
  if (err instanceof TerminalApiError) {
    if (err.errorCode === 'SANDBOX_REPLACED') return 'replaced';
    if (err.errorCode === 'SANDBOX_EXPIRED' || err.errorCode === 'TERMINAL_DISABLED') return 'expired';
  }
  // Any other TerminalApiError status, or a raw network-level Error (fetch
  // rejection, not a TerminalApiError instance), lands here.
  return 'failed';
}

const STATE_MESSAGES: Partial<Record<PanelState, string>> = {
  connecting: 'Connecting…',
  reconnecting: 'Reconnecting…',
  failed: 'Connection failed.',
  replaced: 'Sandbox 已替换，请使用最新 Sandbox 卡片',
  expired: 'Sandbox 已过期',
  busy: 'Another client is already connected to this terminal.',
};

// ---------------------------------------------------------------------------
// Imperative connection management. Kept as a hook so the component below
// stays a thin render function; everything here is refs + plain functions
// because the logic is driven by async callbacks (WS frames, timers) rather
// than React state transitions.
// ---------------------------------------------------------------------------
function useTerminalSession(sandbox: SandboxTerminalSpec) {
  const [state, setState] = useState<PanelState>('connecting');
  const [exitCode, setExitCode] = useState<string | null>(null);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const mountRef = useRef<HTMLDivElement | null>(null);

  const stateRef = useRef<PanelState>('connecting');
  const socketRef = useRef<TerminalSocket | null>(null);
  const lastSeqRef = useRef<number | undefined>(undefined);
  const generationRef = useRef(0);

  const pendingInputRef = useRef('');
  const inputTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resizeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectStartRef = useRef(0);
  const reconnectAttemptRef = useRef(0);
  const heartbeatTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const inputSinceLastBeatRef = useRef(false);

  const connectRef = useRef<() => void>(() => {});

  const updateState = useCallback((next: PanelState) => {
    stateRef.current = next;
    setState(next);
  }, []);

  useEffect(() => {
    const term = new Terminal({
      convertEol: true,
      fontSize: 13,
      fontFamily: 'Menlo, Monaco, monospace',
      cursorBlink: true,
      theme: { background: '#0d0d0d', foreground: '#d4d4d4' },
    });
    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    if (mountRef.current) term.open(mountRef.current);

    function computeSize(): { rows: number; cols: number } {
      try {
        fitAddon.fit();
      } catch {
        // container has no real layout yet (e.g. zero size, or jsdom in tests)
      }
      if (term.rows > 0 && term.cols > 0) return { rows: term.rows, cols: term.cols };
      return { rows: FALLBACK_ROWS, cols: FALLBACK_COLS };
    }

    function flushInput() {
      if (inputTimerRef.current !== null) {
        clearTimeout(inputTimerRef.current);
        inputTimerRef.current = null;
      }
      const chunk = pendingInputRef.current;
      pendingInputRef.current = '';
      if (!chunk) return;
      const socket = socketRef.current;
      if (!socket) return;
      const dataBase64 = btoa(String.fromCharCode(...new TextEncoder().encode(chunk)));
      socket.sendInput(dataBase64);
    }

    function handleInput(data: string) {
      inputSinceLastBeatRef.current = true;
      pendingInputRef.current += data;
      const hasControlChar = Array.from(data).some((ch) => ch.charCodeAt(0) < 0x20);
      if (hasControlChar) {
        flushInput();
      } else if (inputTimerRef.current === null) {
        inputTimerRef.current = setTimeout(flushInput, INPUT_FLUSH_MS);
      }
    }
    term.onData(handleInput);

    function handleFrame(frame: TerminalFrame) {
      switch (frame.type) {
        case 'ready':
          updateState('connected');
          break;
        case 'output':
          term.write(Uint8Array.from(atob(frame.data), (c) => c.charCodeAt(0)));
          if (frame.seq > 0) lastSeqRef.current = frame.seq;
          break;
        case 'overflow':
          term.write('\r\n[earlier output omitted]\r\n');
          break;
        case 'exit':
          setExitCode(String(frame.code));
          updateState('exited');
          break;
        case 'error':
          updateState('failed');
          break;
      }
    }

    function scheduleReconnect(gen: number) {
      const elapsed = Date.now() - reconnectStartRef.current;
      if (elapsed >= RECONNECT_WINDOW_MS) {
        updateState('failed');
        return;
      }
      const idx = Math.min(reconnectAttemptRef.current, RECONNECT_DELAYS_MS.length - 1);
      reconnectAttemptRef.current += 1;
      reconnectTimerRef.current = setTimeout(() => {
        reconnectTimerRef.current = null;
        if (gen !== generationRef.current) return;
        dial(gen);
      }, RECONNECT_DELAYS_MS[idx]);
    }

    function handleClose(gen: number, code: number) {
      if (gen !== generationRef.current) return;
      if (stateRef.current === 'exited') return; // shell already exited; close is expected
      if (code === WS_CLOSE_BUSY) {
        updateState('busy');
        return;
      }
      // Any other close (runtime unreachable, protocol error, abnormal closure
      // before the ticket's target pod ever answers) is treated as reconnectable —
      // a dead pod surfaces here rather than as a distinct ticket-time error.
      const alreadyReconnecting = stateRef.current === 'reconnecting';
      updateState('reconnecting');
      if (!alreadyReconnecting) {
        reconnectAttemptRef.current = 0;
        reconnectStartRef.current = Date.now();
      }
      scheduleReconnect(generationRef.current);
    }

    // Every dial (initial connect, manual restart, or a backoff retry) mints a
    // fresh ticket first — tickets are one-shot and expire in 30s, so none of
    // them can be reused across attempts.
    function dial(gen: number) {
      const { rows, cols } = computeSize();
      const clientId = terminalClientId(sandbox.sessionId, sandbox.sandboxId);
      requestTicket({ sessionId: sandbox.sessionId, sandboxId: sandbox.sandboxId })
        .then(({ ticket, gatewayUrl }) => {
          if (gen !== generationRef.current) return;
          const socket = openTerminalSocket({
            gatewayUrl,
            ticket,
            clientId,
            rows,
            cols,
            lastSeq: lastSeqRef.current,
            onFrame: (frame) => {
              if (gen !== generationRef.current) return;
              handleFrame(frame);
            },
            onClose: (code) => handleClose(gen, code),
          });
          socketRef.current = socket;
        })
        .catch((err: unknown) => {
          if (gen !== generationRef.current) return;
          updateState(classifyError(err));
        });
    }

    function connect() {
      const gen = ++generationRef.current;
      // A manual restart/reconnect click bypasses the effect-cleanup path, so any
      // still-open socket from the previous connection must be closed explicitly here.
      // close() is deliberate, so it never triggers the previous generation's onClose.
      socketRef.current?.close();
      socketRef.current = null;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      updateState('connecting');
      setExitCode(null);
      lastSeqRef.current = undefined;
      dial(gen);
    }
    connectRef.current = connect;

    connect();

    function sendResize() {
      resizeTimerRef.current = null;
      if (stateRef.current !== 'connected') return;
      socketRef.current?.sendResize(term.rows, term.cols);
    }

    function onContainerResize() {
      try {
        fitAddon.fit();
      } catch {
        // container hidden or not yet laid out
      }
      if (resizeTimerRef.current !== null) clearTimeout(resizeTimerRef.current);
      resizeTimerRef.current = setTimeout(sendResize, RESIZE_DEBOUNCE_MS);
    }

    let resizeObserver: ResizeObserver | undefined;
    if (typeof ResizeObserver !== 'undefined' && containerRef.current) {
      resizeObserver = new ResizeObserver(onContainerResize);
      resizeObserver.observe(containerRef.current);
    }

    // Renewal heartbeat: as long as the panel is connected and the user has
    // typed something since the last beat, tell the server the session is
    // still active so its idle-reclaim timer doesn't fire. Output alone never
    // renews (matches v1). The interval itself just keeps running for the
    // component's lifetime; the connected+input-since-beat guard inside the
    // tick is equivalent to starting/stopping it on every state transition,
    // without the extra bookkeeping.
    heartbeatTimerRef.current = setInterval(() => {
      if (stateRef.current !== 'connected') return;
      if (!inputSinceLastBeatRef.current) return;
      inputSinceLastBeatRef.current = false;
      postTerminalActivity(sandbox.sessionId);
    }, HEARTBEAT_INTERVAL_MS);

    return () => {
      generationRef.current += 1; // invalidate any in-flight ticket/socket callbacks
      resizeObserver?.disconnect();
      if (inputTimerRef.current !== null) clearTimeout(inputTimerRef.current);
      if (resizeTimerRef.current !== null) clearTimeout(resizeTimerRef.current);
      if (reconnectTimerRef.current !== null) clearTimeout(reconnectTimerRef.current);
      if (heartbeatTimerRef.current !== null) clearInterval(heartbeatTimerRef.current);
      // Deliberate close, no server-side close call: v2 has no close endpoint
      // (unlike v1) — the runtime reclaims the PTY on disconnect by design.
      socketRef.current?.close();
      term.dispose();
    };
    // Reconnecting on sandbox identity change (a different sandboxId/sessionId prop
    // without an unmount) is intentional — it tears down the old shell the same way
    // unmount does and opens a fresh one for the new sandbox.
  }, [sandbox.sessionId, sandbox.sandboxId, updateState]);

  const restart = useCallback(() => connectRef.current(), []);

  return { state, exitCode, containerRef, mountRef, restart };
}

export default function SandboxTerminalPanel({ sandbox, onClose }: Props) {
  const { state, exitCode, containerRef, mountRef, restart } = useTerminalSession(sandbox);
  const overlayVisible = state !== 'connected';
  const showSpinner = state === 'connecting' || state === 'reconnecting';
  const overlayMessage = state === 'exited'
    ? `Shell exited (code ${exitCode ?? '?'})`
    : STATE_MESSAGES[state];

  return (
    <div className="w-[520px] shrink-0 flex flex-col border-l"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b shrink-0"
        style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 min-w-0">
          <TerminalIcon size={16} style={{ color: 'var(--color-text-secondary)' }} />
          <div className="min-w-0">
            <div className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
              Sandbox Terminal
            </div>
            <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)', fontFamily: 'monospace' }}>
              {sandbox.hostname || sandbox.sandboxId}
            </div>
          </div>
        </div>
        <button onClick={onClose}
          className="p-1 rounded-md hover:opacity-80"
          style={{ color: 'var(--color-text-secondary)' }}>
          <X size={16} />
        </button>
      </div>

      {/* Info bar */}
      <div className="flex flex-wrap gap-x-4 gap-y-0.5 px-4 py-2 border-b shrink-0 text-xs"
        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}>
        {sandbox.ip && (
          <span>
            <span style={{ color: 'var(--color-text-secondary)' }}>ip</span>{' '}
            <span style={{ fontFamily: 'monospace' }}>{sandbox.ip}</span>
          </span>
        )}
        {sandbox.image && (
          <span>
            <span style={{ color: 'var(--color-text-secondary)' }}>image</span>{' '}
            <span style={{ fontFamily: 'monospace' }}>{sandbox.image}</span>
          </span>
        )}
      </div>

      {/* Terminal body */}
      <div className="flex-1 min-h-0 flex flex-col p-4"
        style={{ background: '#1e1e1e' }}>
        <div className="flex-1 rounded-md border overflow-hidden flex flex-col"
          style={{ borderColor: '#333', background: '#0d0d0d' }}>
          {/* Terminal toolbar */}
          <div className="flex items-center gap-2 px-3 py-1.5 border-b shrink-0"
            style={{ borderColor: '#333', background: '#1a1a1a' }}>
            <span className="text-xs" style={{ color: '#888' }}>bash</span>
          </div>
          {/* xterm mounts here; the state overlay sits above it while not connected */}
          <div ref={containerRef} className="flex-1 min-h-0 relative">
            <div ref={mountRef} className="absolute inset-0" />
            {overlayVisible && (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 text-xs text-center px-6"
                style={{ background: 'rgba(13,13,13,0.92)', color: '#ccc' }}>
                {showSpinner && <Loader2 size={18} className="animate-spin" />}
                <div role="status">{overlayMessage}</div>
                {state === 'exited' && (
                  <button onClick={restart}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs"
                    style={{
                      background: 'var(--color-primary)' + '18',
                      color: 'var(--color-primary)',
                      border: '1px solid var(--color-primary)' + '30',
                    }}>
                    <RotateCw size={12} /> Restart
                  </button>
                )}
                {state === 'failed' && (
                  <button onClick={restart}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs"
                    style={{
                      background: 'var(--color-primary)' + '18',
                      color: 'var(--color-primary)',
                      border: '1px solid var(--color-primary)' + '30',
                    }}>
                    <RotateCw size={12} /> Reconnect
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
