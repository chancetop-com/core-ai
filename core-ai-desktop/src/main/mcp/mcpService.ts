// Desktop MCP lifecycle: the store is the source of truth, the engine is a per-workspace host.
// A save only touches the current workspace: quiesce its engine, push the new configuration, and
// never shut down other workspaces.
import { EngineSupervisor } from '../engine/EngineSupervisor';
import { McpServerDraft, McpServerRecord, McpServerView, McpStore } from './mcpStore';

const RELOAD_RETRY_MS = 5_000;
const RELOAD_RETRY_LIMIT = 24;

interface ProbeWaiter {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export interface McpSaveRequest {
  server: McpServerDraft;
  secrets?: Record<string, string | null>;
  expectedHash?: string | null;
}

export class McpService {
  private readonly probes = new Map<string, ProbeWaiter>();
  private readonly pendingReload = new Set<string>();
  private readonly reloadTimers = new Map<string, NodeJS.Timeout>();
  private readonly retryCount = new Map<string, number>();
  private readonly syncing = new Set<string>();

  constructor(
    private readonly store: McpStore,
    private readonly supervisor: EngineSupervisor,
    private readonly workspaceKeyOf: (workspace: string) => string,
    private readonly approvalPolicy: () => string,
    private readonly log: (workspace: string, message: string) => void,
  ) {}

  async list(workspace: string): Promise<{ servers: McpServerView[]; hash: string; supported: boolean; pendingReload: boolean }> {
    const key = this.workspaceKeyOf(workspace);
    if (!this.supervisor.isRunning(workspace)) {
      // Read-only listing may start the current workspace's engine; it never writes or restarts another.
      await this.supervisor.ensureRunning(workspace, this.approvalPolicy()).catch(() => undefined);
    }
    const { servers, secretNames } = await this.store.list(key);
    return {
      servers: servers.map((server) => ({ ...server, secretNames: secretNames[server.id] ?? [] })),
      hash: this.store.hashOf(servers),
      supported: this.supported(workspace),
      pendingReload: this.pendingReload.has(workspace),
    };
  }

  supported(workspace: string): boolean {
    return this.supervisor.capabilities(workspace).includes('mcp_management_v1');
  }

  async save(workspace: string, request: McpSaveRequest): Promise<{ server: McpServerRecord; pending: boolean }> {
    const key = this.workspaceKeyOf(workspace);
    const before = await this.store.list(key);
    if (request.expectedHash && request.expectedHash !== this.store.hashOf(before.servers)) {
      throw new Error('MCP configuration changed in another window; reload before saving');
    }
    const snapshot = await this.store.copyForRollback(key);
    const server = await this.store.save(key, request.server, request.secrets);
    const pending = await this.reload(workspace);
    if (pending === 'failed') {
      await this.store.restore(key, snapshot);
      await this.reload(workspace).catch(() => undefined);
      throw new Error('the engine rejected the MCP configuration; the previous one was restored');
    }
    return { server, pending: pending === 'pending' };
  }

  async setEnabled(workspace: string, id: string, enabled: boolean): Promise<{ pending: boolean }> {
    const key = this.workspaceKeyOf(workspace);
    const snapshot = await this.store.copyForRollback(key);
    await this.store.setEnabled(key, id, enabled);
    const pending = await this.reload(workspace);
    if (pending === 'failed') {
      await this.store.restore(key, snapshot);
      await this.reload(workspace).catch(() => undefined);
      throw new Error('the engine rejected the MCP configuration; the previous one was restored');
    }
    return { pending: pending === 'pending' };
  }

  async remove(workspace: string, id: string): Promise<{ pending: boolean }> {    const key = this.workspaceKeyOf(workspace);
    const snapshot = await this.store.copyForRollback(key);
    await this.store.remove(key, id);
    const pending = await this.reload(workspace);
    if (pending === 'failed') {
      await this.store.restore(key, snapshot);
      await this.reload(workspace).catch(() => undefined);
      throw new Error('the engine rejected the MCP configuration; the previous one was restored');
    }
    return { pending: pending === 'pending' };
  }

  async importServers(
    workspace: string,
    servers: McpServerDraft[],
    mode: 'skip' | 'replace' | 'rename',
  ): Promise<{ imported: string[]; skipped: string[]; renamed: string[]; pending: boolean }> {
    const key = this.workspaceKeyOf(workspace);
    const snapshot = await this.store.copyForRollback(key);
    const result = await this.store.importServers(key, servers, mode);
    const pending = await this.reload(workspace);
    if (pending === 'failed') {
      await this.store.restore(key, snapshot);
      throw new Error('the engine rejected the imported MCP configuration; the previous one was restored');
    }
    return { ...result, pending: pending === 'pending' };
  }

  async status(workspace: string): Promise<unknown> {
    if (!this.supported(workspace)) return { supported: false };
    return this.supervisor.call(workspace, 'mcp/status', {}, this.approvalPolicy());
  }

  async test(workspace: string, name: string): Promise<unknown> {
    if (this.pendingReload.has(workspace) || this.reloadTimers.has(workspace)) {
      throw new Error('MCP_RELOAD_PENDING: the engine is still applying a configuration change');
    }
    if (!this.supported(workspace)) throw new Error('this engine does not support MCP management');
    const started = (await this.supervisor.call(workspace, 'mcp/probe/start', { name }, this.approvalPolicy())) as { probeId?: string };
    const probeId = started?.probeId;
    if (!probeId) throw new Error('the engine did not return a probe id');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.probes.delete(probeId);
        reject(new Error('MCP probe timed out after 90 seconds'));
      }, 90_000);
      this.probes.set(probeId, { resolve, reject, timer });
    });
  }

  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'mcp/probe/result') return;
    const probeId = typeof params.probeId === 'string' ? params.probeId : '';
    const waiter = this.probes.get(probeId);
    if (!waiter) return;
    this.probes.delete(probeId);
    clearTimeout(waiter.timer);
    waiter.resolve(params);
  }

  /** Engine restart: dispose only this workspace, then relaunch and push. */
  async restart(workspace: string): Promise<void> {
    await this.reload(workspace);
  }

  /**
   * Called whenever an engine becomes ready: push the saved configuration into a fresh engine so a
   * workspace's servers load without the user opening Settings. Idempotent per engine.
   */
  async syncWorkspace(workspace: string): Promise<void> {
    if (this.syncing.has(workspace)) return;
    this.syncing.add(workspace);
    try {
      if (!this.supported(workspace)) return;
      const status = (await this.supervisor.call(workspace, 'mcp/status', {}, this.approvalPolicy()).catch(() => null)) as
        { configLoaded?: boolean } | null;
      if (status?.configLoaded) return;
      await this.pushConfiguration(workspace);
    } catch (error) {
      this.log(workspace, `MCP configuration failed: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.syncing.delete(workspace);
    }
  }

  private async pushConfiguration(workspace: string): Promise<void> {
    const key = this.workspaceKeyOf(workspace);
    const trust = this.supervisor.trustState(workspace);
    if (!trust || trust.state !== 'trusted') {
      this.log(workspace, 'MCP configuration is saved; the workspace is restricted so nothing was loaded');
      return;
    }
    const { servers } = await this.store.list(key);
    const enabled = servers.filter((server) => server.enabled);
    const payload: Array<{ name: string; config: Record<string, unknown> }> = [];
    const secrets: Record<string, Record<string, string>> = {};
    for (const server of enabled) {
      payload.push({ name: server.name, config: server.config });
      const values = await this.store.serverSecrets(key, server.id);
      if (Object.keys(values).length > 0) secrets[server.name] = values;
    }
    await this.supervisor.call(workspace, 'mcp/config', { servers: payload }, this.approvalPolicy());
    await this.supervisor.call(workspace, 'mcp/secrets', { secrets }, this.approvalPolicy());
    await this.supervisor.call(workspace, 'mcp/finalize', {}, this.approvalPolicy());
    this.log(workspace, `MCP configuration applied (${enabled.length} enabled server${enabled.length === 1 ? '' : 's'})`);
  }

  private async reload(workspace: string): Promise<'ok' | 'pending' | 'failed'> {
    const quiesce = await this.supervisor.quiesce(workspace);
    if (quiesce === 'busy') {
      this.scheduleRetry(workspace);
      return 'pending';
    }
    try {
      await this.supervisor.restart(workspace);
      await this.supervisor.ensureTrustState(workspace, this.approvalPolicy()).catch(() => null);
      if (!this.supported(workspace)) {
        this.log(workspace, 'MCP configuration saved; this engine does not support desktop-managed MCP');
        return 'ok';
      }
      await this.pushConfiguration(workspace);
      this.pendingReload.delete(workspace);
      this.retryCount.delete(workspace);
      return 'ok';
    } catch (error) {
      this.log(workspace, `MCP configuration failed: ${error instanceof Error ? error.message : String(error)}`);
      this.pendingReload.delete(workspace);
      return 'failed';
    }
  }

  private scheduleRetry(workspace: string): void {
    this.pendingReload.add(workspace);
    if (this.reloadTimers.has(workspace)) return;
    const attempt = (this.retryCount.get(workspace) ?? 0) + 1;
    this.retryCount.set(workspace, attempt);
    if (attempt > RELOAD_RETRY_LIMIT) {
      this.pendingReload.delete(workspace);
      this.log(workspace, 'MCP configuration is still pending; restart the engine manually from Settings');
      return;
    }
    const timer = setTimeout(() => {
      this.reloadTimers.delete(workspace);
      void this.reload(workspace).then((result) => {
        if (result === 'pending') this.scheduleRetry(workspace);
      });
    }, RELOAD_RETRY_MS);
    timer.unref?.();
    this.reloadTimers.set(workspace, timer);
  }
}
