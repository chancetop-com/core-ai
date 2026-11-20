// The host side of a Task Run watch: validates who is asking, binds one concrete run, and turns a
// matching lifecycle fact into a durable outbox row before any network send happens.
import { randomUUID } from 'node:crypto';
import { OriginStore, type OriginGrant } from '../channels/OriginStore';
import { WatchStore, type WatchRecord, type WatchTarget } from './WatchStore';

export interface SubscriptionOptions {
  store: WatchStore;
  origins: OriginStore;
  callEngine: (workspace: string, method: string, params: Record<string, unknown>) => Promise<unknown>;
  send: (target: WatchTarget, text: string) => Promise<{ ok: boolean; error?: string; code?: string }>;
  sessionTitle?: (workspace: string, sessionId: string) => string | null;
  log: (line: string) => void;
  now?: () => number;
  onDelivery?: (delivery: { watchId: string; state: string; error?: string }) => void;
}

export class SubscriptionError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
  }
}

const DEFAULT_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const MIN_TTL_MS = 60 * 1000;
const RECOVERY_WINDOW_MS = 24 * 60 * 60 * 1000;

export class SubscriptionService {
  private running = false;
  private draining = false;
  private scheduled = false;

  constructor(private readonly options: SubscriptionOptions) {}

  async handleHostRequest(workspace: string, params: Record<string, unknown>): Promise<Record<string, unknown>> {
    const operation = String(params.operation ?? '');
    const requesterSessionId = String(params.sessionId ?? '');
    if (!requesterSessionId) throw new SubscriptionError('NO_REQUESTER', 'host request has no requester session');
    const origin = this.options.origins.getForSession(requesterSessionId);
    if (!origin) throw new SubscriptionError('NO_ORIGIN', 'this turn has no original channel origin');
    if (origin.workspace !== workspace) {
      throw new SubscriptionError('WORKSPACE_MISMATCH', 'origin workspace does not match the engine workspace');
    }
    const payload = (params.payload ?? {}) as Record<string, unknown>;
    switch (operation) {
      case 'watch_session':
        return this.watchSession(origin, payload);
      case 'list_session_watches':
        return this.listWatches(origin, payload);
      case 'cancel_session_watch':
        return this.cancelWatch(origin, payload);
      default:
        throw new SubscriptionError('UNKNOWN_OPERATION', `unknown host operation: ${operation}`);
    }
  }

  private async watchSession(origin: OriginGrant, payload: Record<string, unknown>): Promise<Record<string, unknown>> {
    if (origin.chatType !== 'direct') throw new SubscriptionError('GROUP_NOT_SUPPORTED', 'group watches are not supported');
    const sourceSessionId = String(payload.sourceSessionId ?? '');
    const taskRunId = String(payload.sourceTaskRunId ?? '');
    const runGeneration = Number(payload.runGeneration ?? 0);
    const scope = String(payload.scope ?? 'current_task_run');
    if (!sourceSessionId || !taskRunId || !Number.isFinite(runGeneration) || runGeneration <= 0) {
      throw new SubscriptionError('INVALID_WATCH', 'source session, task run and generation are required');
    }
    if (sourceSessionId === origin.sessionId) {
      throw new SubscriptionError('SELF_WATCH', 'a channel agent cannot watch its own current task run');
    }
    const now = this.options.now?.() ?? Date.now();
    const existing = this.options.store.findWatchByRun(origin.sessionId, sourceSessionId, taskRunId, runGeneration, scope);
    if (existing && existing.expiresAt > now) return this.summary(existing);
    const requestedSeconds = Number(payload.expiresInSeconds ?? 0);
    const ttlMs = requestedSeconds > 0
      ? Math.max(MIN_TTL_MS, Math.min(requestedSeconds * 1000, MAX_TTL_MS))
      : DEFAULT_TTL_MS;
    const watchId = `watch-${randomUUID()}`;
    const sourceAlias = typeof payload.sourceAlias === 'string' && payload.sourceAlias.trim()
      ? payload.sourceAlias.trim()
      : this.options.sessionTitle?.(origin.workspace, sourceSessionId) ?? sourceSessionId;
    const target: WatchTarget = {
      channel: origin.channel,
      accountId: origin.accountId,
      target: origin.target,
      chatType: origin.chatType,
    };
    const input = {
      watchId,
      originId: origin.originId,
      ownerSessionId: origin.sessionId,
      sourceWorkspace: origin.workspace,
      sourceSessionId,
      taskRunId,
      runGeneration,
      scope,
      action: 'notify_origin',
      sourceAlias,
      target,
      expiresAt: now + ttlMs,
    };
    const bind = await this.options.callEngine(origin.workspace, 'lifecycle/bind', {
      sessionId: sourceSessionId,
      watchId,
      taskRunId,
      runGeneration,
      ttlMillis: ttlMs,
    }) as Record<string, unknown>;
    const status = String(bind.status ?? '');
    if (status === 'already_ended') {
      const ended = endedOf(bind);
      const eventId = `already-ended:${taskRunId}:${runGeneration}`;
      const delivery = this.options.store.createMatchedWatch(input, eventId, this.render(ended, sourceAlias, watchId));
      this.schedule();
      return {
        status: 'already_ended',
        watchId,
        taskRunId,
        runGeneration,
        scope,
        expiresAt: input.expiresAt,
        deliveryState: delivery.state,
        outcome: ended.outcome,
      };
    }
    if (status !== 'bound') {
      throw new SubscriptionError('BIND_FAILED', `lifecycle bind returned ${status || 'no status'}`);
    }
    const watch = this.options.store.createActiveWatch(input);
    this.options.log(`watch ${watchId} active -> ${sourceSessionId}/${taskRunId}#${runGeneration}`);
    return this.summary(watch);
  }

  /** Local settings view: all watches this profile owns, not scoped to a channel origin. */
  listForUi(): Record<string, unknown> {
    const watches = this.options.store.listAllWatches(50);
    return {
      ok: true,
      watches: watches.map((watch) => ({
        watchId: watch.watchId,
        sourceAlias: watch.sourceAlias,
        sourceSessionId: watch.sourceSessionId,
        taskRunId: watch.taskRunId,
        runGeneration: watch.runGeneration,
        state: watch.state,
        deliveryState: watch.deliveryState,
        channel: watch.target.channel,
        targetMasked: maskTarget(watch.target.target),
        createdAt: watch.createdAt,
        expiresAt: watch.expiresAt,
        lastError: watch.lastError,
      })),
    };
  }

  cancelForUi(watchId: string): Record<string, unknown> {
    const watch = this.options.store.getWatch(watchId);
    if (!watch) return { ok: false, error: 'WATCH_NOT_FOUND' };
    const cancelled = this.options.store.cancelWatch(watchId);
    this.options.log(`watch ${watchId} cancelled from settings`);
    return { ok: true, watchId, deliveryState: cancelled?.deliveryState ?? null };
  }

  private listWatches(origin: OriginGrant, payload: Record<string, unknown>): Record<string, unknown> {
    const limit = Math.max(1, Math.min(Number(payload.limit ?? 20), 50));
    const watches = this.options.store.listWatches(origin.sessionId, limit);
    return {
      status: 'ok',
      watches: watches.map((watch) => this.summary(watch)),
    };
  }

  private cancelWatch(origin: OriginGrant, payload: Record<string, unknown>): Record<string, unknown> {
    const watchId = String(payload.watchId ?? '');
    if (!watchId) throw new SubscriptionError('INVALID_WATCH', 'watchId is required');
    const watch = this.options.store.getWatch(watchId);
    if (!watch || watch.ownerSessionId !== origin.sessionId) {
      throw new SubscriptionError('WATCH_NOT_FOUND', 'watch not found for this conversation');
    }
    const cancelled = this.options.store.cancelWatch(watchId);
    this.options.log(`watch ${watchId} cancelled`);
    return {
      status: 'cancelled',
      watchId,
      deliveryState: cancelled?.deliveryState ?? null,
    };
  }

  /** Called for every engine notification; only a matching terminal run creates a delivery. */
  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'session/lifecycle') return;
    const event = (params.event ?? {}) as Record<string, unknown>;
    if (String(event.type ?? '') !== 'task_run.ended') return;
    const sourceSessionId = String(params.sessionId ?? '');
    const taskRunId = String(event.task_run_id ?? '');
    const runGeneration = Number(event.run_generation ?? 0);
    if (!sourceSessionId || !taskRunId || !runGeneration) return;
    const watches = this.options.store.findActiveWatchesForEnded(workspace, sourceSessionId, taskRunId, runGeneration);
    if (watches.length === 0) return;
    const now = this.options.now?.() ?? Date.now();
    for (const watch of watches) {
      if (now > watch.expiresAt + RECOVERY_WINDOW_MS) {
        this.options.log(`watch ${watch.watchId} ignored late ended event after recovery window`);
        continue;
      }
      const body = this.render(event, watch.sourceAlias ?? watch.sourceSessionId, watch.watchId);
      const delivery = this.options.store.matchWatch(watch.watchId, String(event.event_id ?? `${taskRunId}:${runGeneration}`), body);
      if (!delivery) continue;
      this.options.log(`watch ${watch.watchId} matched ${taskRunId}#${runGeneration} -> ${delivery.deliveryId}`);
    }
    this.schedule();
  }

  start(): void {
    this.running = true;
    void this.reconcile().finally(() => this.schedule());
  }

  stop(): void {
    this.running = false;
  }

  /** Catches watches whose ended event was emitted while the host process was not running. */
  private async reconcile(): Promise<void> {
    const now = this.options.now?.() ?? Date.now();
    for (const watch of this.options.store.listActiveWatches(50)) {
      if (watch.expiresAt + RECOVERY_WINDOW_MS < now) continue;
      try {
        const bind = await this.options.callEngine(watch.sourceWorkspace, 'lifecycle/bind', {
          sessionId: watch.sourceSessionId,
          watchId: watch.watchId,
          taskRunId: watch.taskRunId,
          runGeneration: watch.runGeneration,
          ttlMillis: Math.max(0, watch.expiresAt - now),
        }) as Record<string, unknown>;
        if (String(bind.status ?? '') !== 'already_ended') continue;
        const ended = endedOf(bind);
        const eventId = `recovered:${watch.taskRunId}:${watch.runGeneration}`;
        const delivery = this.options.store.matchWatch(watch.watchId, eventId,
          this.render(ended, watch.sourceAlias, watch.watchId));
        if (delivery) this.options.log(`watch ${watch.watchId} recovered ended fact after restart`);
      } catch (error) {
        this.options.log(`watch ${watch.watchId} reconciliation failed: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
  }

  private schedule(): void {
    if (!this.running || this.scheduled) return;
    this.scheduled = true;
    setTimeout(() => {
      this.scheduled = false;
      void this.drain();
    }, 0);
  }

  private async drain(): Promise<void> {
    if (this.draining || !this.running) return;
    this.draining = true;
    try {
      for (;;) {
        const queued = this.options.store.listQueuedDeliveries(50);
        if (queued.length === 0) break;
        for (const delivery of queued) {
          if (!this.options.store.claimDelivery(delivery.deliveryId)) continue;
          let result: { ok: boolean; error?: string; code?: string };
          try {
            result = await this.options.send({
              channel: delivery.channel,
              accountId: delivery.accountId,
              target: delivery.target,
              chatType: delivery.chatType,
            }, delivery.body);
          } catch (error) {
            result = { ok: false, error: error instanceof Error ? error.message : String(error) };
          }
          if (result.ok) {
            this.options.store.finishDelivery(delivery.deliveryId, 'accepted', null);
            this.options.log(`delivery ${delivery.deliveryId} accepted by gateway`);
            this.options.onDelivery?.({ watchId: delivery.watchId, state: 'accepted' });
          } else {
            // The gateway accepted the request but its outcome is unknown, or it may have sent
            // before failing. Never auto-retry: a duplicate is worse than a visible unknown.
            const state = result.code === 'PREFLIGHT_FAILED' ? 'failed' : 'unknown';
            this.options.store.finishDelivery(delivery.deliveryId, state, result.error ?? 'send failed');
            this.options.log(`delivery ${delivery.deliveryId} ${state}: ${result.error ?? 'send failed'}`);
            this.options.onDelivery?.({ watchId: delivery.watchId, state, error: result.error });
          }
        }
      }
    } finally {
      this.draining = false;
    }
    if (this.running && this.options.store.listQueuedDeliveries(1).length > 0) this.schedule();
  }

  private summary(watch: WatchRecord): Record<string, unknown> {
    return {
      status: watch.state,
      watchId: watch.watchId,
      sourceSessionId: watch.sourceSessionId,
      taskRunId: watch.taskRunId,
      runGeneration: watch.runGeneration,
      scope: watch.scope,
      sourceAlias: watch.sourceAlias,
      expiresAt: watch.expiresAt,
      deliveryState: watch.deliveryState,
      condition: 'task_run.ended, including failure, cancellation and limits',
    };
  }

  private render(ended: Record<string, unknown>, alias: string | null, watchId: string): string {
    const outcome = String(ended.outcome ?? 'unknown');
    const taskRunId = String(ended.taskRunId ?? ended.task_run_id ?? 'unknown');
    const duration = formatDuration(Number(ended.durationMs ?? ended.duration_ms ?? 0));
    const name = alias ? `「${alias}」` : '该任务';
    switch (outcome) {
      case 'returned':
        return `你关注的${name}这次任务运行已正常收尾。\n必要后台工作已结束，按运行策略所需的结果处理已完成。${duration}\n这不是业务质量验收结论。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
      case 'failed':
        return `你关注的${name}这次任务运行已失败。\n原因类别：运行失败，详情可在获授权后查询。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
      case 'cancelled':
        return `你关注的${name}这次任务运行已取消。\n本次运行已逻辑终止，外部进程停止尚未确认。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
      case 'limited':
        return `你关注的${name}这次任务运行达到运行限制而结束。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
      case 'interrupted':
        return `你关注的${name}状态无法确认：运行引擎中断，且没有可信的结束事实。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
      default:
        return `你关注的${name}这次任务运行已结束，但结果状态无法确认。\n任务运行：${taskRunId}。关注编号：${watchId}。`;
    }
  }
}

function endedOf(result: Record<string, unknown>): Record<string, unknown> {
  const last = (result.lastEndedTaskRun ?? result) as Record<string, unknown>;
  const ended = (last.ended ?? last) as Record<string, unknown>;
  const run = (last.run ?? {}) as Record<string, unknown>;
  return {
    ...run,
    ...ended,
    taskRunId: ended.taskRunId ?? ended.task_run_id ?? run.taskRunId ?? run.task_run_id,
  };
}

function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '';
  const minutes = Math.round(ms / 60_000);
  if (minutes < 1) return '用时：不到1分钟。';
  if (minutes < 60) return `用时：${minutes}分。`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `用时：${hours}小时${rest > 0 ? `${rest}分` : ''}。`;
}

function maskTarget(value: string): string {
  const text = value ?? '';
  if (text.length <= 10) return text;
  return `${text.slice(0, 4)}…${text.slice(-4)}`;
}
