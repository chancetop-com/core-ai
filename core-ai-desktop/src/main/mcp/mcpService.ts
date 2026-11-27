// Desktop MCP lifecycle: the store is the source of truth, the engine is a per-workspace host.
// Changes advance a per-workspace desiredRevision and are applied by a serialized reload loop
// (never dropping back-to-back saves and never getting stuck permanently pending).
import { EngineSupervisor, normalizeWorkspaceKey } from '../engine/EngineSupervisor';
import { McpServerDraft, McpServerRecord, McpServerView, McpStore } from './mcpStore';

export type McpReloadState = 'idle' | 'reloading' | 'waiting_turn' | 'stuck' | 'failed';

export interface McpReloadError {
  code: string;
  message: string;
}

export interface McpReloadStatus {
  desiredRevision: number;
  appliedRevision: number;
  reloadState: McpReloadState;
  lastReloadError: McpReloadError | null;
}

export interface McpSaveRequest {
  server: McpServerDraft;
  secrets?: Record<string, string | null>;
  expectedHash?: string | null;
}

export interface McpServiceOptions {
  runningSessionCount?: (workspace: string) => number;
  onReloaded?: (workspace: string, revision: number) => void;
  onSecretsSaved?: (secrets: Record<string, string | null>) => void;
  quiesceTimeoutMs?: number;
  quiesceBackoffMs?: number[];
  restartBackoffMs?: number[];
  turnWaitTimeoutMs?: number;
  testWaitTimeoutMs?: number;
  delay?: (ms: number) => Promise<void>;
}

interface ProbeWaiter {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

interface WorkspaceReloadTracker {
  desiredRevision: number;
  appliedRevision: number;
  loopRunning: boolean;
  reloadState: McpReloadState;
  lastReloadError: McpReloadError | null;
}

interface TurnWaiter {
  resolve: (ended: boolean) => void;
  timer: NodeJS.Timeout;
}

const DEFAULT_QUIESCE_TIMEOUT_MS = 3_000;
const DEFAULT_QUIESCE_BACKOFF_MS = [250, 1_000, 3_000];
const DEFAULT_RESTART_BACKOFF_MS = [500, 2_000, 5_000];
const DEFAULT_TURN_WAIT_TIMEOUT_MS = 30 * 60_000;
const DEFAULT_TEST_WAIT_TIMEOUT_MS = 30_000;
const PLACEHOLDER_PREFIX = '${env:';

export class McpCodedError extends Error {
  readonly code: string;
  readonly reloadState?: McpReloadState;

  constructor(code: string, message: string, reloadState?: McpReloadState) {
    super(`${code}: ${message}`);
    this.name = 'McpCodedError';
    this.code = code;
    this.reloadState = reloadState;
  }
}

export function validateUnsupportedSecretLocations(config: Record<string, unknown>): void {
  for (const field of ['command', 'url', 'endpoint'] as const) {
    const value = config[field];
    if (typeof value === 'string' && value.includes(PLACEHOLDER_PREFIX)) {
      throw new McpCodedError(
        'MCP_SECRET_UNSUPPORTED_LOCATION',
        'secret placeholders are only allowed in env and headers values',
      );
    }
  }
  if (Array.isArray(config.args)) {
    for (const arg of config.args) {
      if (typeof arg === 'string' && arg.includes(PLACEHOLDER_PREFIX)) {
        throw new McpCodedError(
          'MCP_SECRET_UNSUPPORTED_LOCATION',
          'secret placeholders are only allowed in env and headers values',
        );
      }
    }
  }
}

function defaultDelay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    timer.unref?.();
  });
}

export class McpService {
  private readonly probes = new Map<string, ProbeWaiter>();
  private readonly trackers = new Map<string, WorkspaceReloadTracker>();
  private readonly turnWaiters = new Map<string, Set<TurnWaiter>>();
  private readonly reloadListeners = new Map<string, Set<() => void>>();
  private readonly runningSessionCount: (workspace: string) => number;
  private readonly onReloaded?: (workspace: string, revision: number) => void;
  private readonly onSecretsSaved?: (secrets: Record<string, string | null>) => void;
  private readonly quiesceTimeoutMs: number;
  private readonly quiesceBackoffMs: number[];
  private readonly restartBackoffMs: number[];
  private readonly turnWaitTimeoutMs: number;
  private readonly testWaitTimeoutMs: number;
  private readonly delay: (ms: number) => Promise<void>;
  private shutdownInProgress = false;

  constructor(
    private readonly store: McpStore,
    private readonly supervisor: EngineSupervisor,
    private readonly workspaceKeyOf: (workspace: string) => string,
    private readonly approvalPolicy: () => string,
    private readonly log: (workspace: string, message: string) => void,
    options: McpServiceOptions = {},
  ) {
    this.runningSessionCount = options.runningSessionCount ?? (() => 0);
    this.onReloaded = options.onReloaded;
    this.onSecretsSaved = options.onSecretsSaved;
    this.quiesceTimeoutMs = options.quiesceTimeoutMs ?? DEFAULT_QUIESCE_TIMEOUT_MS;
    this.quiesceBackoffMs = options.quiesceBackoffMs ?? DEFAULT_QUIESCE_BACKOFF_MS;
    this.restartBackoffMs = options.restartBackoffMs ?? DEFAULT_RESTART_BACKOFF_MS;
    this.turnWaitTimeoutMs = options.turnWaitTimeoutMs ?? DEFAULT_TURN_WAIT_TIMEOUT_MS;
    this.testWaitTimeoutMs = options.testWaitTimeoutMs ?? DEFAULT_TEST_WAIT_TIMEOUT_MS;
    this.delay = options.delay ?? defaultDelay;
  }

  async list(workspace: string): Promise<{
    servers: McpServerView[];
    hash: string;
    supported: boolean;
    pendingReload: boolean;
    reloadState: McpReloadState;
    desiredRevision: number;
    appliedRevision: number;
    lastReloadError: McpReloadError | null;
  }> {
    const key = this.workspaceKeyOf(workspace);
    if (!this.supervisor.isRunning(workspace)) {
      await this.supervisor.ensureRunning(workspace, this.approvalPolicy()).catch(() => undefined);
    }
    const { servers, secretNames } = await this.store.list(key);
    const tracker = this.trackerFor(workspace);
    return {
      servers: servers.map((server) => ({ ...server, secretNames: secretNames[server.id] ?? [] })),
      hash: this.store.hashOf(servers),
      supported: this.supported(workspace),
      pendingReload: tracker.appliedRevision < tracker.desiredRevision,
      reloadState: tracker.reloadState,
      desiredRevision: tracker.desiredRevision,
      appliedRevision: tracker.appliedRevision,
      lastReloadError: tracker.lastReloadError,
    };
  }

  supported(workspace: string): boolean {
    return this.supervisor.capabilities(workspace).includes('mcp_management_v1');
  }

  reloadStatus(workspace: string): McpReloadStatus {
    const tracker = this.trackerFor(workspace);
    return {
      desiredRevision: tracker.desiredRevision,
      appliedRevision: tracker.appliedRevision,
      reloadState: tracker.reloadState,
      lastReloadError: tracker.lastReloadError ? { ...tracker.lastReloadError } : null,
    };
  }

  async save(workspace: string, request: McpSaveRequest): Promise<{
    server: McpServerRecord;
    revision: number;
    pending: boolean;
    reloadState: McpReloadState;
  }> {
    validateUnsupportedSecretLocations(request.server.config);
    const key = this.workspaceKeyOf(workspace);
    const before = await this.store.list(key);
    if (request.expectedHash && request.expectedHash !== this.store.hashOf(before.servers)) {
      throw new McpCodedError('MCP_STORE_CHANGED', 'MCP configuration changed in another window; reload before saving');
    }
    if (request.secrets) {
      this.onSecretsSaved?.(request.secrets);
    }
    const server = await this.store.save(key, request.server, request.secrets);
    const { revision, pending, reloadState } = this.requestReload(workspace);
    return { server, revision, pending, reloadState };
  }

  async setEnabled(workspace: string, id: string, enabled: boolean): Promise<{
    revision: number;
    pending: boolean;
    reloadState: McpReloadState;
  }> {
    const key = this.workspaceKeyOf(workspace);
    await this.store.setEnabled(key, id, enabled);
    return this.requestReload(workspace);
  }

  async remove(workspace: string, id: string): Promise<{
    revision: number;
    pending: boolean;
    reloadState: McpReloadState;
  }> {
    const key = this.workspaceKeyOf(workspace);
    await this.store.remove(key, id);
    return this.requestReload(workspace);
  }

  async importServers(
    workspace: string,
    servers: McpServerDraft[],
    mode: 'skip' | 'replace' | 'rename',
  ): Promise<{
    imported: string[];
    skipped: string[];
    renamed: string[];
    revision: number;
    pending: boolean;
    reloadState: McpReloadState;
  }> {
    for (const draft of servers) {
      validateUnsupportedSecretLocations(draft.config);
    }
    const key = this.workspaceKeyOf(workspace);
    const result = await this.store.importServers(key, servers, mode);
    const reload = this.requestReload(workspace);
    return { ...result, ...reload };
  }

  async status(workspace: string): Promise<unknown> {
    if (!this.supported(workspace)) return { supported: false };
    return this.supervisor.call(workspace, 'mcp/status', {}, this.approvalPolicy());
  }

  async test(workspace: string, name: string, targetRevision?: number): Promise<unknown> {
    const tracker = this.trackerFor(workspace);
    const requiredRevision = Math.max(targetRevision ?? 0, tracker.desiredRevision);
    if (tracker.appliedRevision < requiredRevision) {
      await this.waitForRevision(workspace, requiredRevision, this.testWaitTimeoutMs);
    }
    if (!this.supported(workspace)) {
      throw new McpCodedError('MCP_UNSUPPORTED', 'this engine does not support MCP management');
    }
    const started = (await this.supervisor.call(workspace, 'mcp/probe/start', { name }, this.approvalPolicy())) as { probeId?: string };
    const probeId = started?.probeId;
    if (!probeId) throw new McpCodedError('MCP_PROBE_FAILED', 'the engine did not return a probe id');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.probes.delete(probeId);
        reject(new McpCodedError('MCP_PROBE_TIMEOUT', 'MCP probe timed out after 90 seconds'));
      }, 90_000);
      this.probes.set(probeId, { resolve, reject, timer });
    });
  }

  async waitForRevision(workspace: string, revision: number, timeoutMs = this.testWaitTimeoutMs): Promise<void> {
    const tracker = this.trackerFor(workspace);
    if (tracker.appliedRevision >= revision) return;
    if (tracker.reloadState === 'stuck' || tracker.reloadState === 'failed') {
      throw new McpCodedError(
        'MCP_RELOAD_PENDING',
        `the engine reload is ${tracker.reloadState}`,
        tracker.reloadState,
      );
    }
    if (!tracker.loopRunning && tracker.appliedRevision < tracker.desiredRevision) {
      void this.runReloadLoop(workspace);
    }
    const wsKey = normalizeWorkspaceKey(workspace);
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      const cleanup = (): void => {
        clearTimeout(timer);
        this.reloadListeners.get(wsKey)?.delete(onUpdate);
      };
      const onUpdate = (): void => {
        if (settled) return;
        const current = this.trackerFor(workspace);
        if (current.appliedRevision >= revision) {
          settled = true;
          cleanup();
          resolve();
        } else if (current.reloadState === 'stuck' || current.reloadState === 'failed') {
          settled = true;
          cleanup();
          reject(new McpCodedError(
            'MCP_RELOAD_PENDING',
            `the engine reload is ${current.reloadState}`,
            current.reloadState,
          ));
        }
      };
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        cleanup();
        const current = this.trackerFor(workspace);
        reject(new McpCodedError(
          'MCP_RELOAD_PENDING',
          `the engine is still applying a configuration change (${current.reloadState})`,
          current.reloadState,
        ));
      }, timeoutMs);
      timer.unref?.();
      let set = this.reloadListeners.get(wsKey);
      if (!set) {
        set = new Set();
        this.reloadListeners.set(wsKey, set);
      }
      set.add(onUpdate);
      onUpdate();
    });
  }

  retryReload(workspace: string): McpReloadStatus {
    const tracker = this.trackerFor(workspace);
    if (tracker.reloadState === 'stuck' || tracker.reloadState === 'failed') {
      tracker.reloadState = 'idle';
      tracker.lastReloadError = null;
    }
    if (tracker.appliedRevision < tracker.desiredRevision) {
      void this.runReloadLoop(workspace);
    }
    return this.reloadStatus(workspace);
  }

  async restart(workspace: string): Promise<{ revision: number; pending: boolean; reloadState: McpReloadState }> {
    return this.requestReload(workspace);
  }

  notifyShutdown(): void {
    this.shutdownInProgress = true;
    for (const [wsKey, waiters] of this.turnWaiters.entries()) {
      for (const waiter of waiters) {
        clearTimeout(waiter.timer);
        waiter.resolve(false);
      }
      waiters.clear();
      this.turnWaiters.delete(wsKey);
    }
  }

  onTurnEnded(workspace: string): void {
    if (this.runningSessionCount(workspace) > 0) return;
    this.wakeTurnWaiters(normalizeWorkspaceKey(workspace), true);
  }

  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method === 'status_change') {
      this.onTurnEnded(workspace);
      return;
    }
    if (method !== 'mcp/probe/result') return;
    const probeId = typeof params.probeId === 'string' ? params.probeId : '';
    const waiter = this.probes.get(probeId);
    if (!waiter) return;
    this.probes.delete(probeId);
    clearTimeout(waiter.timer);
    waiter.resolve(params);
  }

  private requestReload(workspace: string): { revision: number; pending: boolean; reloadState: McpReloadState } {
    const tracker = this.trackerFor(workspace);
    tracker.desiredRevision += 1;
    if (tracker.reloadState === 'stuck' || tracker.reloadState === 'failed') {
      tracker.reloadState = 'idle';
      tracker.lastReloadError = null;
    }
    const revision = tracker.desiredRevision;
    void this.runReloadLoop(workspace);
    return {
      revision,
      pending: tracker.appliedRevision < revision,
      reloadState: tracker.reloadState,
    };
  }

  private async runReloadLoop(workspace: string): Promise<void> {
    if (this.shutdownInProgress || this.supervisor.isShuttingDown) return;
    const tracker = this.trackerFor(workspace);
    if (tracker.loopRunning) return;
    tracker.loopRunning = true;
    try {
      let quiesceAttempts = 0;
      let restartAttempts = 0;
      while (tracker.appliedRevision < tracker.desiredRevision) {
        if (this.shutdownInProgress || this.supervisor.isShuttingDown) return;
        const target = tracker.desiredRevision;
        if (this.runningSessionCount(workspace) > 0) {
          tracker.reloadState = 'waiting_turn';
          const ended = await this.waitForTurnEnd(workspace);
          if (this.shutdownInProgress || this.supervisor.isShuttingDown) return;
          if (!ended) {
            tracker.reloadState = 'stuck';
            tracker.lastReloadError = {
              code: 'MCP_RELOAD_STUCK',
              message: 'Timed out waiting for active turn to finish before MCP reload',
            };
            this.notifyReloadListeners(workspace);
            break;
          }
          continue;
        }
        tracker.reloadState = 'reloading';
        type ReloadStepResult = 'applied' | 'waiting_turn' | 'quiesce_timeout' | 'restart_failed' | 'shutdown';
        const stepResult: ReloadStepResult = await this.supervisor.withLifecycleLock(workspace, async (): Promise<ReloadStepResult> => {
          if (this.shutdownInProgress || this.supervisor.isShuttingDown) {
            return 'shutdown';
          }
          const quiesce = await this.supervisor.quiesce(workspace, this.quiesceTimeoutMs);
          if (quiesce.status === 'busy') {
            return quiesce.hasActiveTurn ? 'waiting_turn' : 'quiesce_timeout';
          }
          try {
            await this.supervisor.restart(workspace);
            if (this.shutdownInProgress || this.supervisor.isShuttingDown) {
              return 'shutdown';
            }
            await this.supervisor.ensureRunning(workspace, this.approvalPolicy());
            return 'applied';
          } catch {
            return 'restart_failed';
          }
        });
        if (stepResult === 'shutdown') return;
        if (stepResult === 'waiting_turn') {
          quiesceAttempts = 0;
          tracker.reloadState = 'waiting_turn';
          const ended = await this.waitForTurnEnd(workspace);
          if (this.shutdownInProgress || this.supervisor.isShuttingDown) return;
          if (!ended) {
            tracker.reloadState = 'stuck';
            tracker.lastReloadError = {
              code: 'MCP_RELOAD_STUCK',
              message: 'Timed out waiting for active turn to finish before MCP reload',
            };
            this.notifyReloadListeners(workspace);
            break;
          }
          continue;
        }
        if (stepResult === 'quiesce_timeout') {
          quiesceAttempts += 1;
          if (quiesceAttempts > this.quiesceBackoffMs.length) {
            tracker.reloadState = 'stuck';
            tracker.lastReloadError = {
              code: 'MCP_RELOAD_STUCK',
              message: 'Engine could not quiesce for MCP reload; retry or restart the engine',
            };
            this.log(workspace, 'mcp: reload stuck after quiesce timeout');
            this.notifyReloadListeners(workspace);
            break;
          }
          await this.delay(this.quiesceBackoffMs[quiesceAttempts - 1]);
          continue;
        }
        if (stepResult === 'restart_failed') {
          restartAttempts += 1;
          if (restartAttempts > this.restartBackoffMs.length) {
            tracker.reloadState = 'failed';
            tracker.lastReloadError = {
              code: 'MCP_RELOAD_FAILED',
              message: 'Engine failed to restart for MCP reload',
            };
            this.log(workspace, 'mcp: reload failed after restart retries');
            this.notifyReloadListeners(workspace);
            break;
          }
          await this.delay(this.restartBackoffMs[restartAttempts - 1]);
          continue;
        }
        quiesceAttempts = 0;
        restartAttempts = 0;
        tracker.appliedRevision = target;
        tracker.lastReloadError = null;
        if (tracker.appliedRevision >= tracker.desiredRevision) {
          tracker.reloadState = 'idle';
        }
        this.onReloaded?.(workspace, target);
        this.notifyReloadListeners(workspace);
      }
    } finally {
      tracker.loopRunning = false;
      if (
        !this.shutdownInProgress
        && !this.supervisor.isShuttingDown
        && tracker.desiredRevision > tracker.appliedRevision
        && tracker.reloadState === 'idle'
      ) {
        void this.runReloadLoop(workspace);
      }
    }
  }

  private waitForTurnEnd(workspace: string): Promise<boolean> {
    if (this.shutdownInProgress || this.supervisor.isShuttingDown) return Promise.resolve(false);
    const wsKey = normalizeWorkspaceKey(workspace);
    return new Promise<boolean>((resolve) => {
      const waiter: TurnWaiter = {
        resolve: (ended: boolean) => {
          this.turnWaiters.get(wsKey)?.delete(waiter);
          resolve(ended);
        },
        timer: setTimeout(() => {
          this.turnWaiters.get(wsKey)?.delete(waiter);
          resolve(false);
        }, this.turnWaitTimeoutMs),
      };
      waiter.timer.unref?.();
      let set = this.turnWaiters.get(wsKey);
      if (!set) {
        set = new Set();
        this.turnWaiters.set(wsKey, set);
      }
      set.add(waiter);
    });
  }

  private wakeTurnWaiters(wsKey: string, ended: boolean): void {
    const waiters = this.turnWaiters.get(wsKey);
    if (!waiters || waiters.size === 0) return;
    for (const waiter of [...waiters]) {
      clearTimeout(waiter.timer);
      waiter.resolve(ended);
    }
  }

  private notifyReloadListeners(workspace: string): void {
    const listeners = this.reloadListeners.get(normalizeWorkspaceKey(workspace));
    if (!listeners) return;
    for (const listener of [...listeners]) {
      listener();
    }
  }

  private trackerFor(workspace: string): WorkspaceReloadTracker {
    const wsKey = normalizeWorkspaceKey(workspace);
    let tracker = this.trackers.get(wsKey);
    if (!tracker) {
      tracker = {
        desiredRevision: 0,
        appliedRevision: 0,
        loopRunning: false,
        reloadState: 'idle',
        lastReloadError: null,
      };
      this.trackers.set(wsKey, tracker);
    }
    return tracker;
  }
}
