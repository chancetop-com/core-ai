// One app-server engine per workspace: lazy spawn, initialize handshake, notification fan-out,
// approval-policy-aware restart and graceful shutdown.
import { existsSync } from 'node:fs';
import path from 'node:path';
import type { WorkspaceTrustState } from '../../shared/workspaceTrust';
import { RpcCallError, RpcClient } from './RpcClient';

export interface EngineSupervisorOptions {
  enginePath: string;
  engineArgs?: string[];
  clientVersion: string;
  /** The engine supports `--desktop-mcp`: the desktop owns MCP configuration for this engine. */
  desktopMcp?: () => Promise<boolean> | boolean;
  extraEnv?: (workspace: string) => Record<string, string> | Promise<Record<string, string>>;
  onEvent: (workspace: string, method: string, params: Record<string, unknown>) => void;
  onLog?: (workspace: string, line: string) => void;
  /** Fired after the handshake succeeds; callers must not await engine RPCs synchronously here. */
  onReady?: (workspace: string) => void;
}
interface EngineState {
  client: RpcClient;
  approvalPolicy: string;
  capabilities: string[];
  trustState: WorkspaceTrustState | null;
  ready: Promise<void>;
}

// Long-running calls get their own budget; speech transcription uploads the whole clip and waits
// for the upstream model, so the default two-minute RPC timeout is too tight for a long recording.
const CALL_TIMEOUTS: Record<string, number> = { 'speech/transcribe': 10 * 60_000 };

export class EngineSupervisor {
  private readonly engines = new Map<string, EngineState>();
  private readonly creating = new Map<string, Promise<EngineState>>();
  constructor(private readonly options: EngineSupervisorOptions) {}

  async call(workspace: string, method: string, params: Record<string, unknown> = {}, approvalPolicy = 'ask'): Promise<unknown> {
    return (await this.acquire(workspace, approvalPolicy)).client.call(method, params, CALL_TIMEOUTS[method]);
  }
  async restart(workspace: string): Promise<void> { await this.dispose(workspace); }
  async shutdownAll(): Promise<void> { await Promise.all([...this.engines.keys()].map((workspace) => this.dispose(workspace))); }
  isRunning(workspace: string): boolean { return this.engines.get(workspace)?.client.isRunning ?? false; }
  runningWorkspaces(): string[] { return [...this.engines.entries()].filter(([, state]) => state.client.isRunning).map(([workspace]) => workspace); }
  capabilities(workspace: string): string[] { return this.engines.get(workspace)?.capabilities ?? []; }
  trustState(workspace: string): WorkspaceTrustState | null { return this.engines.get(workspace)?.trustState ?? null; }
  async ensureRunning(workspace: string, approvalPolicy = 'ask'): Promise<void> { await this.acquire(workspace, approvalPolicy); }
  /** Starts the engine when needed and returns the trust state reported by its handshake. */
  async ensureTrustState(workspace: string, approvalPolicy = 'ask'): Promise<WorkspaceTrustState | null> {
    await this.acquire(workspace, approvalPolicy);
    return this.trustState(workspace);
  }

  /**
   * Pauses new turns/schedule claims and waits for accepted turns to drain. `unsupported` keeps a
   * legacy engine working with the old safe-point heuristic; `busy` means do not restart yet.
   */
  async quiesce(workspace: string, timeoutMs = 3_000): Promise<'ok' | 'busy' | 'unsupported'> {
    const state = this.engines.get(workspace);
    if (!state || !state.client.isRunning) return 'ok';
    try {
      const result = await state.client.call<{ acquired?: boolean }>('engine/quiesce', { timeoutMs }, timeoutMs + 2_000);
      return result?.acquired === true ? 'ok' : 'busy';
    } catch (error) {
      if (error instanceof RpcCallError && error.code === -32601) return 'unsupported';
      return 'busy';
    }
  }

  async resumeIntake(workspace: string): Promise<void> {
    const state = this.engines.get(workspace);
    if (!state || !state.client.isRunning) return;
    await state.client.call('engine/resumeIntake', {}, 5_000).catch(() => undefined);
  }

  private async acquire(workspace: string, approvalPolicy: string): Promise<EngineState> {
    for (;;) {
      const state = this.engines.get(workspace);
      if (state && !state.client.isRunning) {
        this.engines.delete(workspace);
        this.options.onLog?.(workspace, 'discarded exited engine; respawning');
      } else if (state) {
        if (state.approvalPolicy !== approvalPolicy) { await this.dispose(workspace); continue; }
        await state.ready;
        return state;
      }
      // Concurrent callers share one spawn: a second engine for the same workspace would exit
      // immediately (the engine takes a workspace lock), leaving its caller with a dead client.
      const inflight = this.creating.get(workspace);
      if (inflight) { await inflight.catch(() => undefined); continue; }
      const creation = this.create(workspace, approvalPolicy);
      this.creating.set(workspace, creation);
      try {
        return await creation;
      } finally {
        if (this.creating.get(workspace) === creation) this.creating.delete(workspace);
      }
    }
  }

  private async create(workspace: string, approvalPolicy: string): Promise<EngineState> {
    const client = await this.spawnClient(workspace);
    const created: EngineState = { client, approvalPolicy, capabilities: [], trustState: null, ready: Promise.resolve() };
    this.engines.set(workspace, created);
    created.ready = this.initialize(created, workspace).catch((error: Error) => {
      if (this.engines.get(workspace) === created) this.engines.delete(workspace);
      // A handshake failure leaves the process holding the workspace lock; without this kill the
      // next spawn exits (code=2, another engine is already running) and the workspace stays stuck.
      client.kill();
      throw error;
    });
    await created.ready;
    return created;
  }

  private async initialize(state: EngineState, workspace: string): Promise<void> {
    const result = await state.client.call<{ capabilities?: string[]; trustState?: WorkspaceTrustState }>('initialize', {
      protocolVersion: '1.0', client: { name: 'core-ai-desktop', version: this.options.clientVersion }, workspace, approvalPolicy: state.approvalPolicy,
    });
    state.capabilities = Array.isArray(result?.capabilities) ? result.capabilities : [];
    state.trustState = result?.trustState && typeof result.trustState === 'object' ? result.trustState : null;
    this.options.onLog?.(workspace, `engine ready (pid ${state.client.pid ?? '?'}), policy=${state.approvalPolicy}, trust=${state.trustState?.state ?? 'unknown'}`);
    this.options.onReady?.(workspace);
  }

  private async spawnClient(workspace: string): Promise<RpcClient> {
    const enginePath = this.options.enginePath;
    const extraEnv = (await this.options.extraEnv?.(workspace)) ?? {};
    const clientOptions = { cwd: workspace, env: { ...process.env, ...extraEnv }, onStderr: (line: string) => this.options.onLog?.(workspace, line) };
    const mcpFlag = (await this.options.desktopMcp?.()) === true ? ['--desktop-mcp'] : [];
    if (this.options.engineArgs) {
      const args = [...this.options.engineArgs, ...mcpFlag];
      return this.attach(new RpcClient(enginePath, args, clientOptions), workspace, `engine spawned: ${enginePath} ${args.join(' ')}`);
    }
    if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(enginePath)) {
      const libDir = path.join(path.dirname(path.dirname(enginePath)), 'lib');
      if (existsSync(libDir)) {
        const java = resolveJavaBin();
        const args = ['--enable-native-access=ALL-UNNAMED', '-classpath', path.join(libDir, '*'), 'Main', 'app-server', '--workspace', workspace, ...mcpFlag];
        return this.attach(new RpcClient(java, args, clientOptions), workspace, `engine spawned: ${java} Main app-server --workspace ${workspace} ${mcpFlag.join(' ')}`);
      }
    }
    const args = ['app-server', '--workspace', workspace, ...mcpFlag];
    const client = process.platform === 'win32' && /\.(bat|cmd)$/i.test(enginePath)
      ? new RpcClient('cmd.exe', ['/c', enginePath, ...args], clientOptions)
      : new RpcClient(enginePath, args, clientOptions);
    return this.attach(client, workspace, `engine spawned: ${enginePath} ${args.join(' ')}`);
  }

  private attach(client: RpcClient, workspace: string, message: string): RpcClient {
    client.onNotification((method, params) => this.options.onEvent(workspace, method, params));
    this.options.onLog?.(workspace, message);
    return client;
  }
  private async dispose(workspace: string): Promise<void> {
    const state = this.engines.get(workspace);
    if (!state) return;
    this.engines.delete(workspace);
    await state.client.shutdown();
    this.options.onLog?.(workspace, 'engine stopped');
  }
}
function resolveJavaBin(): string {
  if (process.env.CORE_AI_JAVA) return process.env.CORE_AI_JAVA;
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  return 'java';
}
