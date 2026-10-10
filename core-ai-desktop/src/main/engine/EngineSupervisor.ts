// One app-server engine per workspace (design §4.2): lazy spawn, initialize handshake,
// notification fan-out to the renderer, approval-policy aware restart, graceful shutdown.
import { existsSync } from 'node:fs';
import path from 'node:path';
import { RpcClient } from './RpcClient';

export interface EngineSupervisorOptions {
  enginePath: string;
  clientVersion: string;
  /** Extra environment for engine processes, read fresh at every spawn (e.g. BU_CDP_URL). */
  extraEnv?: () => Record<string, string>;
  onEvent: (workspace: string, method: string, params: Record<string, unknown>) => void;
  onLog?: (workspace: string, line: string) => void;
}

interface EngineState {
  client: RpcClient;
  approvalPolicy: string;
  ready: Promise<void>;
}

export class EngineSupervisor {
  private readonly engines = new Map<string, EngineState>();

  constructor(private readonly options: EngineSupervisorOptions) {}

  async call(workspace: string, method: string, params: Record<string, unknown> = {}, approvalPolicy = 'ask'): Promise<unknown> {
    const state = await this.acquire(workspace, approvalPolicy);
    return state.client.call(method, params);
  }

  /** Approval policy is fixed at initialize time, so a policy change restarts the engine lazily. */
  async restart(workspace: string): Promise<void> {
    await this.dispose(workspace);
  }

  async shutdownAll(): Promise<void> {
    const workspaces = [...this.engines.keys()];
    await Promise.all(workspaces.map((workspace) => this.dispose(workspace)));
  }

  private async acquire(workspace: string, approvalPolicy: string): Promise<EngineState> {
    let state = this.engines.get(workspace);
    if (state && state.approvalPolicy !== approvalPolicy) {
      await this.dispose(workspace);
      state = undefined;
    }
    if (!state) {
      const client = this.spawnClient(workspace);
      const created: EngineState = { client, approvalPolicy, ready: Promise.resolve() };
      created.ready = this.initialize(created, workspace).catch((error: Error) => {
        this.engines.delete(workspace);
        throw error;
      });
      this.engines.set(workspace, created);
      state = created;
    }
    await state.ready;
    return state;
  }

  private async initialize(state: EngineState, workspace: string): Promise<void> {
    await state.client.call('initialize', {
      protocolVersion: '1.0',
      client: { name: 'core-ai-desktop', version: this.options.clientVersion },
      workspace,
      approvalPolicy: state.approvalPolicy,
    });
    this.options.onLog?.(workspace, `engine ready (pid ${state.client.pid ?? '?'}), policy=${state.approvalPolicy}`);
  }

  private spawnClient(workspace: string): RpcClient {
    const enginePath = this.options.enginePath;
    const clientOptions = {
      cwd: workspace,
      env: { ...process.env, ...(this.options.extraEnv?.() ?? {}) },
      onStderr: (line: string) => this.options.onLog?.(workspace, line),
    };
    // On Windows, run the java launcher directly instead of the generated .bat: the batch
    // wrapper needs cmd.exe, whose console window is visible in front of the GUI. java expands
    // the wildcard classpath itself, and windowsHide gives it a console without a window.
    if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(enginePath)) {
      const libDir = path.join(path.dirname(path.dirname(enginePath)), 'lib');
      if (existsSync(libDir)) {
        const java = resolveJavaBin();
        const javaArgs = ['--enable-native-access=ALL-UNNAMED', '-classpath', path.join(libDir, '*'),
          'Main', 'app-server', '--workspace', workspace];
        const client = new RpcClient(java, javaArgs, clientOptions);
        client.onNotification((method, params) => this.options.onEvent(workspace, method, params));
        this.options.onLog?.(workspace, `engine spawned: ${java} Main app-server --workspace ${workspace}`);
        return client;
      }
    }
    const args = ['app-server', '--workspace', workspace];
    let client: RpcClient;
    if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(enginePath)) {
      client = new RpcClient('cmd.exe', ['/c', enginePath, ...args], clientOptions);
    } else {
      client = new RpcClient(enginePath, args, clientOptions);
    }
    client.onNotification((method, params) => this.options.onEvent(workspace, method, params));
    this.options.onLog?.(workspace, `engine spawned: ${enginePath} ${args.join(' ')}`);
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
  if (process.env.JAVA_HOME) {
    return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  }
  return 'java';
}
