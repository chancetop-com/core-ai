// Line-delimited JSON-RPC 2.0 client for the core-ai-cli app-server engine: spawns the child
// process, frames calls and dispatches engine notifications. No HTTP, no port — the stdio pipe
// is the trusted channel.
import { ChildProcessWithoutNullStreams, spawn } from 'node:child_process';

export interface RpcErrorShape {
  code: number;
  message: string;
  data?: Record<string, unknown>;
}

export class RpcCallError extends Error {
  readonly code: number;
  readonly data?: Record<string, unknown>;

  constructor(error: RpcErrorShape) {
    super(error.message || 'engine call failed');
    this.name = 'RpcCallError';
    this.code = error.code;
    this.data = error.data;
  }
}

export interface RpcClientOptions {
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  onStderr?: (line: string) => void;
  defaultTimeoutMs?: number;
}

type NotificationHandler = (method: string, params: Record<string, unknown>) => void;

interface Pending {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export class RpcClient {
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<number, Pending>();
  private readonly handlers = new Set<NotificationHandler>();
  private readonly defaultTimeoutMs: number;
  private buffer = '';
  private nextId = 1;
  private exited = false;

  constructor(command: string, args: string[], options: RpcClientOptions = {}) {
    this.defaultTimeoutMs = options.defaultTimeoutMs ?? 120_000;
    this.child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env ?? process.env,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    }) as ChildProcessWithoutNullStreams;
    this.child.stdout.setEncoding('utf8');
    this.child.stderr.setEncoding('utf8');
    this.child.stdout.on('data', (chunk: string) => this.onStdout(chunk));
    this.child.stderr.on('data', (chunk: string) => {
      if (!options.onStderr) return;
      for (const line of chunk.split('\n')) {
        if (line.trim()) options.onStderr(line);
      }
    });
    this.child.on('exit', (code, signal) => this.onExit(code, signal));
    this.child.on('error', (error) => {
      this.exited = true;
      this.rejectPending(new Error(`engine process failed: ${error.message}`));
    });
  }

  get pid(): number | undefined {
    return this.child.pid;
  }

  get isRunning(): boolean {
    return !this.exited;
  }

  onNotification(handler: NotificationHandler): () => void {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  call<T = Record<string, unknown>>(method: string, params?: Record<string, unknown>, timeoutMs?: number): Promise<T> {
    if (this.exited) {
      return Promise.reject(new Error('engine is not running'));
    }
    const id = this.nextId++;
    const frame: Record<string, unknown> = { jsonrpc: '2.0', id, method };
    if (params !== undefined) frame.params = params;
    const budget = timeoutMs ?? this.defaultTimeoutMs;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`rpc timeout: ${method} (${budget} ms)`));
      }, budget);
      this.pending.set(id, { resolve: (value) => resolve(value as T), reject, timer });
      this.child.stdin.write(`${JSON.stringify(frame)}\n`, (error) => {
        if (error && this.pending.has(id)) {
          clearTimeout(timer);
          this.pending.delete(id);
          reject(new Error(`failed to write to engine: ${error.message}`));
        }
      });
    });
  }

  /** Graceful stop: ask the engine to shut down, then fall back to killing the process. */
  async shutdown(): Promise<void> {
    if (this.exited) return;
    try {
      await this.call('shutdown', {}, 10_000);
    } catch {
      // engine already gone or busy shutting down
    }
    const exited = await this.waitForExit(5_000);
    if (!exited) this.kill();
  }

  kill(): void {
    if (!this.exited) this.child.kill();
  }

  private waitForExit(ms: number): Promise<boolean> {
    if (this.exited) return Promise.resolve(true);
    return new Promise((resolve) => {
      const timer = setTimeout(() => resolve(false), ms);
      this.child.once('exit', () => {
        clearTimeout(timer);
        resolve(true);
      });
    });
  }

  private onExit(code: number | null, signal: NodeJS.Signals | null): void {
    this.exited = true;
    this.rejectPending(new Error(`engine exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`));
  }

  private rejectPending(error: Error): void {
    for (const [, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    this.pending.clear();
  }

  private onStdout(chunk: string): void {
    this.buffer += chunk;
    let index = this.buffer.indexOf('\n');
    while (index >= 0) {
      const line = this.buffer.slice(0, index).trim();
      this.buffer = this.buffer.slice(index + 1);
      if (line) this.dispatchFrame(line);
      index = this.buffer.indexOf('\n');
    }
  }

  private dispatchFrame(line: string): void {
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return;
    }
    const id = frame.id;
    if (typeof id === 'number' && this.pending.has(id)) {
      const entry = this.pending.get(id);
      if (entry) {
        this.pending.delete(id);
        clearTimeout(entry.timer);
        if (frame.error) {
          entry.reject(new RpcCallError(frame.error as RpcErrorShape));
        } else {
          entry.resolve(frame.result ?? {});
        }
      }
      return;
    }
    if (typeof frame.method === 'string') {
      const params = (frame.params ?? {}) as Record<string, unknown>;
      for (const handler of this.handlers) {
        handler(frame.method, params);
      }
    }
  }
}
