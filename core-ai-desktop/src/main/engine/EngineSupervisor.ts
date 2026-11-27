// One app-server engine per workspace: lazy spawn, initialize + MCP ready barrier, notification
// fan-out, approval-policy-aware restart, per-workspace lifecycle mutex, and graceful shutdown.
import { existsSync } from 'node:fs';
import path from 'node:path';
import type { WorkspaceTrustState } from '../../shared/workspaceTrust';
import { RpcCallError, RpcClient } from './RpcClient';

export interface QuiesceResult {
  status: 'idle' | 'busy' | 'unsupported';
  hasActiveTurn: boolean;
}

export interface McpConfigPayload {
  servers: Array<{ name: string; config: Record<string, unknown> }>;
  secrets: Record<string, Record<string, string>>;
}

export interface EngineSupervisorOptions {
  enginePath: string;
  engineArgs?: string[];
  clientVersion: string;
  /** The engine supports `--desktop-mcp`: the desktop owns MCP configuration for this engine. */
  desktopMcp?: () => Promise<boolean> | boolean;
  /** Pure read-only provider invoked inside the ready barrier (never calls back into McpService IPC). */
  mcpConfigProvider?: (workspace: string, workspaceKey: string) => Promise<McpConfigPayload>;
  /** Invoked inside initialize before mcp/config so Desktop persists legacy trust migration as sole writer. */
  onTrustState?: (workspace: string, trustState: WorkspaceTrustState) => Promise<void> | void;
  mcpRpcTimeoutMs?: number;
  extraEnv?: (workspace: string) => Record<string, string> | Promise<Record<string, string>>;
  onEvent: (workspace: string, method: string, params: Record<string, unknown>) => void;
  onLog?: (workspace: string, line: string) => void;
  /** Fired after ready succeeds and engines.set completes; must not re-push MCP config. */
  onReady?: (workspace: string) => void;
}

interface EngineState {
  workspace: string;
  client: RpcClient;
  approvalPolicy: string;
  capabilities: string[];
  trustState: WorkspaceTrustState | null;
  mcpDegraded: boolean;
  ready: Promise<void>;
}

const CALL_TIMEOUTS: Record<string, number> = { 'speech/transcribe': 10 * 60_000 };
const DEFAULT_MCP_RPC_TIMEOUT_MS = 10_000;

export function normalizeWorkspaceKey(workspace: string): string {
  const resolved = path.resolve(workspace);
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

function isUnknownDesktopMcpOptionError(client: RpcClient, error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  const combined = `${client.recentStderr}\n${message}`;
  if (/already running|EngineAlreadyRunningException|workspace lock/i.test(combined)) {
    return false;
  }
  const hasExit2 = client.exitCode === 2 || /\bcode=2\b/.test(message);
  if (!hasExit2) return false;
  const mentionsOption = /unknown option|unmatched argument/i.test(combined);
  const mentionsFlag = /--desktop-mcp/i.test(combined);
  return mentionsOption && mentionsFlag;
}

export class EngineSupervisor {
  private readonly engines = new Map<string, EngineState>();
  private readonly creating = new Map<string, Promise<EngineState>>();
  private readonly starting = new Set<RpcClient>();
  private readonly lifecycleLocks = new Map<string, Promise<unknown>>();
  private readonly unsupportedDesktopMcpPaths = new Set<string>();
  private shutdownAllInProgress = false;
  private shutdownGeneration = 0;

  constructor(private readonly options: EngineSupervisorOptions) {}

  get isShuttingDown(): boolean {
    return this.shutdownAllInProgress;
  }

  normalizeWorkspace(workspace: string): string {
    return normalizeWorkspaceKey(workspace);
  }

  async withLifecycleLock<T>(workspace: string, action: () => Promise<T>): Promise<T> {
    const key = normalizeWorkspaceKey(workspace);
    const prev = this.lifecycleLocks.get(key) ?? Promise.resolve();
    const run = prev.then(action, action);
    const settled = run.then(() => undefined, () => undefined);
    this.lifecycleLocks.set(key, settled);
    try {
      return await run;
    } finally {
      if (this.lifecycleLocks.get(key) === settled) {
        this.lifecycleLocks.delete(key);
      }
    }
  }

  async call(workspace: string, method: string, params: Record<string, unknown> = {}, approvalPolicy = 'ask'): Promise<unknown> {
    return (await this.acquire(workspace, approvalPolicy)).client.call(method, params, CALL_TIMEOUTS[method]);
  }

  async restart(workspace: string): Promise<void> {
    await this.dispose(workspace);
  }

  async shutdownAll(): Promise<void> {
    this.shutdownAllInProgress = true;
    this.shutdownGeneration += 1;
    try {
      for (const client of this.starting) {
        client.kill();
      }
      this.starting.clear();
      this.creating.clear();
      const keys = [...this.engines.keys()];
      await Promise.all(keys.map((key) => this.disposeByKey(key)));
    } finally {
      this.shutdownAllInProgress = false;
    }
  }

  isRunning(workspace: string): boolean {
    return this.engines.get(normalizeWorkspaceKey(workspace))?.client.isRunning ?? false;
  }

  mcpDegraded(workspace: string): boolean {
    return this.engines.get(normalizeWorkspaceKey(workspace))?.mcpDegraded ?? false;
  }

  runningWorkspaces(): string[] {
    return [...this.engines.values()]
      .filter((state) => state.client.isRunning)
      .map((state) => state.workspace);
  }

  capabilities(workspace: string): string[] {
    return this.engines.get(normalizeWorkspaceKey(workspace))?.capabilities ?? [];
  }

  trustState(workspace: string): WorkspaceTrustState | null {
    return this.engines.get(normalizeWorkspaceKey(workspace))?.trustState ?? null;
  }

  async ensureRunning(workspace: string, approvalPolicy = 'ask'): Promise<void> {
    await this.acquire(workspace, approvalPolicy);
  }

  /** Starts the engine when needed and returns the trust state reported by its handshake. */
  async ensureTrustState(workspace: string, approvalPolicy = 'ask'): Promise<WorkspaceTrustState | null> {
    await this.acquire(workspace, approvalPolicy);
    return this.trustState(workspace);
  }

  /**
   * Pauses new turns/schedule claims and waits for accepted turns to drain.
   * Reports `hasActiveTurn` so callers can distinguish active work from a stuck quiesce timeout.
   */
  async quiesce(workspace: string, timeoutMs = 3_000): Promise<QuiesceResult> {
    const state = this.engines.get(normalizeWorkspaceKey(workspace));
    if (!state || !state.client.isRunning) {
      return { status: 'idle', hasActiveTurn: false };
    }
    try {
      const result = await state.client.call<{ acquired?: boolean; active?: number }>(
        'engine/quiesce',
        { timeoutMs },
        timeoutMs + 2_000,
      );
      if (result?.acquired === true) {
        return { status: 'idle', hasActiveTurn: false };
      }
      const hasActiveTurn = typeof result?.active === 'number' ? result.active > 0 : false;
      return { status: 'busy', hasActiveTurn };
    } catch (error) {
      if (error instanceof RpcCallError && error.code === -32601) {
        return { status: 'unsupported', hasActiveTurn: false };
      }
      return { status: 'busy', hasActiveTurn: false };
    }
  }

  async resumeIntake(workspace: string): Promise<void> {
    const state = this.engines.get(normalizeWorkspaceKey(workspace));
    if (!state || !state.client.isRunning) return;
    await state.client.call('engine/resumeIntake', {}, 5_000).catch(() => undefined);
  }

  private async acquire(workspace: string, approvalPolicy: string): Promise<EngineState> {
    const key = normalizeWorkspaceKey(workspace);
    for (;;) {
      if (this.shutdownAllInProgress) {
        throw new Error('engine supervisor is shutting down');
      }
      const state = this.engines.get(key);
      if (state && !state.client.isRunning) {
        this.engines.delete(key);
        this.options.onLog?.(workspace, 'discarded exited engine; respawning');
      } else if (state) {
        if (state.approvalPolicy !== approvalPolicy) {
          await this.disposeByKey(key);
          continue;
        }
        await state.ready;
        if (!state.client.isRunning) {
          if (this.engines.get(key) === state) this.engines.delete(key);
          continue;
        }
        return state;
      }
      const inflight = this.creating.get(key);
      if (inflight) {
        await inflight.catch(() => undefined);
        continue;
      }
      const creation = this.create(workspace, key, approvalPolicy);
      this.creating.set(key, creation);
      try {
        return await creation;
      } finally {
        if (this.creating.get(key) === creation) this.creating.delete(key);
      }
    }
  }

  private async create(workspace: string, key: string, approvalPolicy: string): Promise<EngineState> {
    const generation = this.shutdownGeneration;
    const shouldTryDesktopMcp = !this.unsupportedDesktopMcpPaths.has(this.options.enginePath)
      && (await this.options.desktopMcp?.()) === true;
    const first = await this.createAttempt(workspace, key, approvalPolicy, shouldTryDesktopMcp, generation);
    if (first.ok) return first.state;
    if (
      shouldTryDesktopMcp
      && !this.shutdownAllInProgress
      && this.shutdownGeneration === generation
      && isUnknownDesktopMcpOptionError(first.client, first.error)
    ) {
      this.unsupportedDesktopMcpPaths.add(this.options.enginePath);
      this.options.onLog?.(workspace, 'engine does not support --desktop-mcp; retrying once without it');
      const fallback = await this.createAttempt(workspace, key, approvalPolicy, false, generation);
      if (fallback.ok) return fallback.state;
      throw fallback.error;
    }
    throw first.error;
  }

  private async createAttempt(
    workspace: string,
    key: string,
    approvalPolicy: string,
    withDesktopMcp: boolean,
    generation: number,
  ): Promise<{ ok: true; state: EngineState } | { ok: false; client: RpcClient; error: unknown }> {
    if (this.shutdownAllInProgress || this.shutdownGeneration !== generation) {
      throw new Error('engine supervisor is shutting down');
    }
    const client = await this.spawnClient(workspace, withDesktopMcp);
    this.starting.add(client);
    if (this.shutdownAllInProgress || this.shutdownGeneration !== generation) {
      this.starting.delete(client);
      client.kill();
      throw new Error('engine supervisor is shutting down');
    }
    const created: EngineState = {
      workspace,
      client,
      approvalPolicy,
      capabilities: [],
      trustState: null,
      mcpDegraded: false,
      ready: Promise.resolve(),
    };
    created.ready = this.initialize(created, workspace, withDesktopMcp, generation);
    try {
      await created.ready;
    } catch (error) {
      this.starting.delete(client);
      if (this.engines.get(key) === created) this.engines.delete(key);
      client.kill();
      return { ok: false, client, error };
    }
    this.starting.delete(client);
    if (this.shutdownAllInProgress || this.shutdownGeneration !== generation || !client.isRunning) {
      client.kill();
      return { ok: false, client, error: new Error('engine stopped before ready completed') };
    }
    this.engines.set(key, created);
    this.options.onReady?.(workspace);
    return { ok: true, state: created };
  }

  private async initialize(
    state: EngineState,
    workspace: string,
    withDesktopMcp: boolean,
    generation: number,
  ): Promise<void> {
    const result = await state.client.call<{ capabilities?: string[]; trustState?: WorkspaceTrustState }>('initialize', {
      protocolVersion: '1.0',
      client: { name: 'core-ai-desktop', version: this.options.clientVersion },
      workspace,
      approvalPolicy: state.approvalPolicy,
    });
    this.assertAlive(state, generation);
    state.capabilities = Array.isArray(result?.capabilities) ? result.capabilities : [];
    state.trustState = result?.trustState && typeof result.trustState === 'object' ? result.trustState : null;
    if (withDesktopMcp && !state.capabilities.includes('mcp_management_v1')) {
      this.unsupportedDesktopMcpPaths.add(this.options.enginePath);
    }
    if (state.trustState && this.options.onTrustState) {
      try {
        await this.options.onTrustState(workspace, state.trustState);
      } catch {
        this.options.onLog?.(workspace, 'workspace trust migration persistence failed; continuing with engine trust decision');
      }
    }
    this.assertAlive(state, generation);
    if (state.capabilities.includes('mcp_management_v1')) {
      await this.initializeMcpInBarrier(state, workspace, generation);
    }
    this.assertAlive(state, generation);
    this.options.onLog?.(
      workspace,
      `engine ready (pid ${state.client.pid ?? '?'}), policy=${state.approvalPolicy}, trust=${state.trustState?.state ?? 'unknown'}`,
    );
  }

  private async initializeMcpInBarrier(state: EngineState, workspace: string, generation: number): Promise<void> {
    const timeoutMs = this.options.mcpRpcTimeoutMs ?? DEFAULT_MCP_RPC_TIMEOUT_MS;
    const callStep = async (method: string, params: Record<string, unknown>): Promise<boolean> => {
      this.assertAlive(state, generation);
      try {
        await state.client.call(method, params, timeoutMs);
        this.assertAlive(state, generation);
        return true;
      } catch (error) {
        if (this.shutdownAllInProgress || this.shutdownGeneration !== generation || !state.client.isRunning) {
          throw error;
        }
        state.mcpDegraded = true;
        this.options.onLog?.(workspace, `mcp: ${method} failed while engine is alive; continuing with MCP unavailable`);
        return false;
      }
    };

    if (!state.trustState || state.trustState.state !== 'trusted') {
      await callStep('mcp/finalize', {});
      return;
    }

    let payload: McpConfigPayload = { servers: [], secrets: {} };
    if (this.options.mcpConfigProvider) {
      try {
        const workspaceKey = state.trustState.workspaceKey ?? workspace;
        payload = await this.options.mcpConfigProvider(workspace, workspaceKey);
      } catch {
        state.mcpDegraded = true;
        this.options.onLog?.(workspace, 'mcp: failed to load MCP configuration from store; sending empty configuration');
      }
    }
    this.assertAlive(state, generation);

    const configOk = await callStep('mcp/config', { servers: payload.servers });
    await callStep('mcp/secrets', { secrets: configOk ? payload.secrets : {} });
    await callStep('mcp/finalize', {});
  }

  private assertAlive(state: EngineState, generation: number): void {
    if (this.shutdownAllInProgress || this.shutdownGeneration !== generation) {
      throw new Error('engine supervisor is shutting down');
    }
    if (!state.client.isRunning) {
      throw new Error('engine exited during initialization');
    }
  }

  private async spawnClient(workspace: string, withDesktopMcp: boolean): Promise<RpcClient> {
    const enginePath = this.options.enginePath;
    const extraEnv = (await this.options.extraEnv?.(workspace)) ?? {};
    const clientOptions = {
      cwd: workspace,
      env: { ...process.env, ...extraEnv },
      onStderr: (line: string) => this.options.onLog?.(workspace, line),
    };
    const mcpFlag = withDesktopMcp ? ['--desktop-mcp'] : [];
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
    await this.disposeByKey(normalizeWorkspaceKey(workspace));
  }

  private async disposeByKey(key: string): Promise<void> {
    const state = this.engines.get(key);
    if (!state) return;
    this.engines.delete(key);
    await state.client.shutdown();
    this.options.onLog?.(state.workspace, 'engine stopped');
  }
}

function resolveJavaBin(): string {
  if (process.env.CORE_AI_JAVA) return process.env.CORE_AI_JAVA;
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  return 'java';
}
