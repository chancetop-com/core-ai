// One app-server engine per workspace: lazy spawn, initialize handshake, notification fan-out,
// approval-policy-aware restart and graceful shutdown.
import { existsSync } from 'node:fs';
import path from 'node:path';
import { RpcClient } from './RpcClient';

export interface EngineSupervisorOptions {
  enginePath: string;
  engineArgs?: string[];
  clientVersion: string;
  extraEnv?: () => Record<string, string>;
  onEvent: (workspace: string, method: string, params: Record<string, unknown>) => void;
  onLog?: (workspace: string, line: string) => void;
}
interface EngineState { client: RpcClient; approvalPolicy: string; capabilities: string[]; ready: Promise<void> }

export class EngineSupervisor {
  private readonly engines = new Map<string, EngineState>();
  constructor(private readonly options: EngineSupervisorOptions) {}

  async call(workspace: string, method: string, params: Record<string, unknown> = {}, approvalPolicy = 'ask'): Promise<unknown> {
    return (await this.acquire(workspace, approvalPolicy)).client.call(method, params);
  }
  async restart(workspace: string): Promise<void> { await this.dispose(workspace); }
  async shutdownAll(): Promise<void> { await Promise.all([...this.engines.keys()].map((workspace) => this.dispose(workspace))); }
  isRunning(workspace: string): boolean { return this.engines.get(workspace)?.client.isRunning ?? false; }
  runningWorkspaces(): string[] { return [...this.engines.entries()].filter(([, state]) => state.client.isRunning).map(([workspace]) => workspace); }
  capabilities(workspace: string): string[] { return this.engines.get(workspace)?.capabilities ?? []; }
  async ensureRunning(workspace: string, approvalPolicy = 'ask'): Promise<void> { await this.acquire(workspace, approvalPolicy); }

  private async acquire(workspace: string, approvalPolicy: string): Promise<EngineState> {
    let state = this.engines.get(workspace);
    if (state && !state.client.isRunning) {
      this.engines.delete(workspace);
      this.options.onLog?.(workspace, 'discarded exited engine; respawning');
      state = undefined;
    }
    if (state && state.approvalPolicy !== approvalPolicy) { await this.dispose(workspace); state = undefined; }
    if (!state) {
      const client = this.spawnClient(workspace);
      const created: EngineState = { client, approvalPolicy, capabilities: [], ready: Promise.resolve() };
      created.ready = this.initialize(created, workspace).catch((error: Error) => { this.engines.delete(workspace); throw error; });
      this.engines.set(workspace, created);
      state = created;
    }
    await state.ready;
    return state;
  }

  private async initialize(state: EngineState, workspace: string): Promise<void> {
    const result = await state.client.call<{ capabilities?: string[] }>('initialize', {
      protocolVersion: '1.0', client: { name: 'core-ai-desktop', version: this.options.clientVersion }, workspace, approvalPolicy: state.approvalPolicy,
    });
    state.capabilities = Array.isArray(result?.capabilities) ? result.capabilities : [];
    this.options.onLog?.(workspace, `engine ready (pid ${state.client.pid ?? '?'}), policy=${state.approvalPolicy}`);
  }

  private spawnClient(workspace: string): RpcClient {
    const enginePath = this.options.enginePath;
    const clientOptions = { cwd: workspace, env: { ...process.env, ...(this.options.extraEnv?.() ?? {}) }, onStderr: (line: string) => this.options.onLog?.(workspace, line) };
    if (this.options.engineArgs) return this.attach(new RpcClient(enginePath, this.options.engineArgs, clientOptions), workspace, `engine spawned: ${enginePath} ${this.options.engineArgs.join(' ')}`);
    if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(enginePath)) {
      const libDir = path.join(path.dirname(path.dirname(enginePath)), 'lib');
      if (existsSync(libDir)) {
        const java = resolveJavaBin();
        const args = ['--enable-native-access=ALL-UNNAMED', '-classpath', path.join(libDir, '*'), 'Main', 'app-server', '--workspace', workspace];
        return this.attach(new RpcClient(java, args, clientOptions), workspace, `engine spawned: ${java} Main app-server --workspace ${workspace}`);
      }
    }
    const args = ['app-server', '--workspace', workspace];
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
