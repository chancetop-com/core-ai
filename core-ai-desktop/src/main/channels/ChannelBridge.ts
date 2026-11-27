// P0 IM bridge: pull authorized messages from the local OCG daemon, run one engine turn
// per message, and post the final answer back. A message that arrives while a turn is
// running steers that turn, the way a message typed into the chat composer does, instead
// of waiting behind it.
// P1 replaces the CLI polling with the versioned OCG HTTP client and adds
// bindings/authorization/journaling.
import type { StagedAttachment } from '../attachments/AttachmentStager';
import { BridgeStore, type BridgeSessionRecord } from './BridgeStore';
import type { OcgInboundMessage, OcgResult } from './OcgCli';
import type { OriginStore } from './OriginStore';

/** The three OCG operations the bridge needs; P1 swaps the CLI implementation for HTTP. */
export interface OcgPort {
  wait(consumer: string, timeoutSeconds: number): Promise<OcgResult<{ messages: OcgInboundMessage[] }>>;
  reply(id: string, text: string, isError: boolean, consumer: string, clientReplyId?: string): Promise<OcgResult<Record<string, unknown>>>;
  ack(id: string, consumer: string): Promise<OcgResult<Record<string, unknown>>>;
  send(channel: string, to: string, text: string, accountId: string): Promise<OcgResult<Record<string, unknown>>>;
}

export interface ChannelBridgeState {
  enabled: boolean;
  running: boolean;
  consumer: string;
  lastMessageAt: number | null;
  lastReplyAt: number | null;
  lastError: string | null;
  lastPeer: string | null;
  activeSessionId: string | null;
}

export interface ChannelSessionEvent {
  kind: 'created' | 'replied' | 'renamed';
  sessionId: string;
  workspace: string;
  title: string;
  sessionKey: string;
}

export interface ChannelBridgeOptions {
  workspace: (message: OcgInboundMessage) => string | null;
  callEngine: (workspace: string, method: string, params: Record<string, unknown>) => Promise<unknown>;
  ocg: OcgPort;
  storeFile: string;
  consumer: string;
  /** Main-owned origin addresses; never exposed to the engine prompt. */
  origins?: OriginStore;
  /** Display title of an engine session (rename sidecar first, first user message otherwise). */
  sessionTitle?: (workspace: string, sessionId: string) => string | null;
  /** First user message stored in an engine session; used to name legacy WeChat sessions. */
  firstUserText?: (workspace: string, sessionId: string) => string | null;
  /**
   * Copies inbound IM attachments into the session's staging directory. The engine only reads
   * attachment paths inside the workspace or that directory, so OCG's own media paths cannot be
   * sent as-is.
   */
  stageMedia?: (sessionId: string, sourcePaths: string[]) => Promise<StagedAttachment[]>;
  /** Delay before the "processing" hint; a turn that finishes sooner sends no hint. */
  progressDelayMs?: number;
  progressText?: string;
  onState: (state: ChannelBridgeState) => void;
  onSession: (event: ChannelSessionEvent) => void;
  log: (line: string) => void;
}

interface TurnOutcome {
  output?: string;
  error?: string;
  cancelled?: boolean;
}

interface PendingTurn {
  workspace: string;
  sessionId: string;
  turnId: string;
  promise: Promise<TurnOutcome>;
  settle: (outcome: TurnOutcome) => void;
  detach: () => void;
}

/** A message the engine took without opening a turn for it; a turn of its own may still follow. */
interface AbsorbedMessage {
  message: OcgInboundMessage;
  expiresAt: number;
}

/**
 * Holds a session's next message until the turn this bridge just started is reported running.
 * A message sent into that window is queued into the same batch as the previous one, and a
 * batch reports only its first turn id — the later message would never get its own reply.
 */
interface TurnStartGate {
  waiters: Array<() => void>;
  timer: ReturnType<typeof setTimeout>;
}

interface SendAdmission {
  admission?: string;
}

const WAIT_SECONDS = 20;
const TURN_TIMEOUT_MS = 10 * 60 * 1000;
const TURN_START_TIMEOUT_MS = 3_000;
const ABSORBED_TTL_MS = 30 * 60 * 1000;
const RETRY_MIN_MS = 5_000;
const RETRY_MAX_MS = 60_000;
const PROGRESS_DELAY_MS = 2_500;
const PROGRESS_TEXT = '收到，正在处理…';
const STEERED_REPLY = '收到，已并入当前任务。';
const QUEUED_REPLY = '收到，正在排队处理。';
const MEDIA_FAILED_TEXT = '附件接收失败，请重试。';
const TITLE_TEXT_MAX = 32;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function truncate(value: string, length: number): string {
  return value.length > length ? `${value.slice(0, length)}…` : value;
}

function channelLabel(channel: string): string {
  switch (channel) {
    case 'openclaw-weixin': return '微信';
    case 'qqbot': return 'QQ';
    case 'telegram': return 'Telegram';
    case 'dingtalk-connector': return '钉钉';
    case 'discord': return 'Discord';
    default: return channel || 'IM';
  }
}

function titleText(value: string | null | undefined): string {
  const oneLine = (value ?? '').replace(/\s+/g, ' ').trim();
  if (!oneLine) return '';
  return oneLine.length > TITLE_TEXT_MAX ? `${oneLine.slice(0, TITLE_TEXT_MAX)}…` : oneLine;
}

export class ChannelBridge {
  private readonly store: BridgeStore;
  // Keyed by turn id, not session id: a message can be queued behind a running turn, so one
  // session may hold several turns that have not answered yet.
  private readonly pending = new Map<string, PendingTurn>();
  // Messages absorbed into a running turn. The engine requeues a steer that missed the turn's
  // last model call, so such a message may still get a turn of its own after the receipt.
  private readonly absorbed = new Map<string, AbsorbedMessage>();
  private readonly turnStarts = new Map<string, TurnStartGate>();
  // Messages this bridge is still working on. OCG can redeliver one while its turn runs; a second
  // turn for the same id would overwrite the first turn's waiter.
  private readonly claimed = new Set<string>();
  private readonly progressTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly progressWork = new Map<string, Promise<void>>();
  private running = false;
  private state: ChannelBridgeState;

  constructor(private readonly options: ChannelBridgeOptions) {
    this.store = new BridgeStore(options.storeFile);
    this.state = {
      enabled: false,
      running: false,
      consumer: options.consumer,
      lastMessageAt: null,
      lastReplyAt: null,
      lastError: null,
      lastPeer: null,
      activeSessionId: null,
    };
  }

  snapshot(): ChannelBridgeState {
    return { ...this.state };
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.state = { ...this.state, enabled: true, running: true, lastError: null };
    this.emit();
    void this.loop();
  }

  stop(): void {
    if (!this.running) return;
    this.running = false;
    this.options.origins?.clearAll();
    for (const timer of this.progressTimers.values()) clearTimeout(timer);
    this.progressTimers.clear();
    this.absorbed.clear();
    this.claimed.clear();
    // Unanswered turns are left to OCG's lease expiry: the message is redelivered and handled afresh.
    for (const turn of [...this.pending.values()]) turn.detach();
    for (const sessionId of [...this.turnStarts.keys()]) this.releaseTurnStart(sessionId);
    this.state = { ...this.state, enabled: false, running: false, activeSessionId: null };
    this.emit();
  }

  /** Called from EngineSupervisor's notification fan-out, for every workspace. */
  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'session/event') return;
    const sessionId = typeof params.sessionId === 'string' ? params.sessionId : '';
    const event = params.event as Record<string, unknown> | undefined;
    if (!sessionId || !event) return;
    const type = typeof event.type === 'string' ? event.type : '';
    if (type === 'status_change') {
      // RUNNING is dispatched after the turn opened its input window, so the next message of this
      // session can be steered instead of being queued next to it.
      if (String(event.status ?? '').toLowerCase() === 'running') this.releaseTurnStart(sessionId);
      return;
    }
    if (type !== 'turn_complete' && type !== 'error') return;
    const turnId = typeof event.turn_id === 'string' ? event.turn_id : '';
    const pending = turnId ? this.pending.get(turnId) : this.onlyPending(sessionId);
    if (pending && pending.workspace === workspace) {
      if (type === 'turn_complete') {
        pending.settle({
          output: typeof event.output === 'string' ? event.output : '',
          cancelled: event.cancelled === true,
        });
      } else {
        pending.settle({ error: typeof event.message === 'string' ? event.message : 'engine error' });
      }
      return;
    }
    const absorbed = turnId ? this.absorbed.get(turnId) : undefined;
    if (!absorbed) return;
    this.absorbed.delete(turnId);
    if (type === 'turn_complete') {
      const output = typeof event.output === 'string' ? event.output.trim() : '';
      if (output) void this.deliverAbsorbed(absorbed, output);
    } else {
      const detail = typeof event.message === 'string' ? event.message : 'engine error';
      void this.deliverAbsorbed(absorbed, `处理失败：${truncate(detail, 200)}`);
    }
  }

  /** Older engines (and command-level errors) do not tag an event with a turn id. */
  private onlyPending(sessionId: string): PendingTurn | undefined {
    let found: PendingTurn | undefined;
    for (const turn of this.pending.values()) {
      if (turn.sessionId !== sessionId) continue;
      if (found) return undefined;
      found = turn;
    }
    return found;
  }

  /** Blocks a message until the turn this bridge just started reports running (or the wait times out). */
  private async waitTurnStart(sessionId: string): Promise<void> {
    const gate = this.turnStarts.get(sessionId);
    if (!gate) return;
    await new Promise<void>((resolve) => gate.waiters.push(resolve));
  }

  private beginTurnStart(sessionId: string): void {
    this.releaseTurnStart(sessionId);
    const timer = setTimeout(() => this.releaseTurnStart(sessionId), TURN_START_TIMEOUT_MS);
    this.turnStarts.set(sessionId, { waiters: [], timer });
  }

  private releaseTurnStart(sessionId: string): void {
    const gate = this.turnStarts.get(sessionId);
    if (!gate) return;
    clearTimeout(gate.timer);
    this.turnStarts.delete(sessionId);
    for (const resolve of gate.waiters) resolve();
  }

  private async loop(): Promise<void> {
    let retryMs = RETRY_MIN_MS;
    while (this.running) {
      const result = await this.options.ocg.wait(this.options.consumer, WAIT_SECONDS);
      if (!this.running) break;
      if (!result.ok) {
        this.fail(result.error ?? 'OCG inbox unavailable');
        await sleep(retryMs);
        retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
        continue;
      }
      retryMs = RETRY_MIN_MS;
      const messages = result.data?.messages ?? [];
      if (this.state.lastError) {
        this.state = { ...this.state, lastError: null };
        this.emit();
      }
      for (const message of messages) {
        if (!this.running) break;
        await this.handle(message);
      }
      if (messages.length === 0) await sleep(500);
    }
  }

  private async handle(message: OcgInboundMessage): Promise<void> {
    if (this.store.isHandled(message.id) || this.claimed.has(message.id)) {
      await this.ack(message.id);
      return;
    }
    if (message.chatType === 'group' && message.wasMentioned !== true) {
      this.options.log(`skip ${message.id}: group message without an explicit mention`);
      await this.finishWithoutReply(message, 'acked');
      return;
    }
    if (message.chatType === 'channel' || message.chatType === 'unknown') {
      this.options.log(`skip ${message.id}: unsupported chat type ${message.chatType}`);
      await this.finishWithoutReply(message, 'acked');
      return;
    }
    const text = (message.text ?? '').trim();
    const mediaPaths: string[] = [];
    for (const entry of message.media ?? []) {
      if (entry.path) mediaPaths.push(entry.path);
      else if (entry.url) this.options.log(`message ${message.id}: media not downloaded yet (${entry.url})`);
    }
    if (!text && mediaPaths.length === 0) {
      this.options.log(`skip ${message.id}: empty message`);
      await this.finishWithoutReply(message, 'acked');
      return;
    }
    const workspace = this.options.workspace(message);
    if (!workspace) {
      this.fail('No workspace is available for IM messages');
      await this.finishWithoutReply(message, 'failed');
      return;
    }

    this.claimed.add(message.id);
    this.scheduleProgress(message);
    this.state = {
      ...this.state,
      lastMessageAt: Date.now(),
      lastPeer: message.sender.name ?? message.sender.id ?? message.target ?? message.channel,
    };
    this.emit();
    this.options.log(`message ${message.id} channel=${message.channel} chat=${message.chatType} textLen=${text.length} media=${mediaPaths.length}`);

    let sessionId: string;
    let title: string;
    try {
      const resolved = await this.resolveSession(message, workspace);
      sessionId = resolved.sessionId;
      title = resolved.title;
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      this.fail(`session setup failed: ${detail}`);
      await this.replyAndFinish(message, `处理失败：${truncate(detail, 200)}`, true, workspace, '', '');
      return;
    }

    this.state = { ...this.state, activeSessionId: sessionId };
    this.emit();
    this.options.origins?.begin({ message, workspace, sessionId });

    const staged = await this.stageInboundMedia(message, sessionId, mediaPaths);
    if (!text && staged.length === 0) {
      this.fail(`media staging failed for ${message.id}: no attachment could be staged`);
      await this.replyAndFinish(message, MEDIA_FAILED_TEXT, true, workspace, sessionId, title);
      return;
    }

    const parts: Array<{ type: string; text?: string; path?: string }> = [];
    if (text) parts.push({ type: 'text', text });
    for (const attachment of staged) parts.push({ type: attachment.kind, path: attachment.path });

    await this.dispatch(message, workspace, sessionId, title, parts);
  }

  /**
   * Hands one message to the engine and returns as soon as the engine accepted it, so the inbox
   * loop keeps consuming: a message that arrives during a running turn then steers that turn
   * instead of waiting behind it. The answer is posted when the turn ends.
   */
  private async dispatch(
    message: OcgInboundMessage,
    workspace: string,
    sessionId: string,
    title: string,
    parts: Array<{ type: string; text?: string; path?: string }>,
  ): Promise<void> {
    await this.waitTurnStart(sessionId);
    if (!this.running) return;
    this.beginTurnStart(sessionId);
    const waiter = this.createWaiter(sessionId, workspace, message.id);
    let admission: string | undefined;
    try {
      const result = await this.options.callEngine(workspace, 'session/send', {
        sessionId,
        turnId: message.id,
        parts,
      }) as SendAdmission | null;
      admission = typeof result?.admission === 'string' ? result.admission : undefined;
    } catch (error) {
      waiter.detach();
      this.releaseTurnStart(sessionId);
      const detail = error instanceof Error ? error.message : String(error);
      this.fail(`session/send failed: ${detail}`);
      await this.replyAndFinish(message, `处理失败：${truncate(detail, 200)}`, true, workspace, sessionId, title);
      return;
    }

    if (admission === 'steered' || admission === 'queued') {
      waiter.detach();
      this.releaseTurnStart(sessionId);
      // A queued message can be drained together with another one and lose its turn id, so it
      // gets the same treatment as a steered message: a receipt now, its answer by turn id later.
      await this.absorb(message, workspace, sessionId, title, admission === 'steered' ? STEERED_REPLY : QUEUED_REPLY);
      return;
    }
    if (admission === 'rejected') {
      waiter.detach();
      this.releaseTurnStart(sessionId);
      await this.replyAndFinish(message, '这一轮未被接收：会话排队已满，请稍后重试。', true, workspace, sessionId, title);
      return;
    }

    void this.awaitTurn(message, waiter, workspace, sessionId, title);
  }

  private async awaitTurn(
    message: OcgInboundMessage,
    waiter: PendingTurn,
    workspace: string,
    sessionId: string,
    title: string,
  ): Promise<void> {
    let outcome: TurnOutcome;
    try {
      outcome = await waiter.promise;
    } catch (error) {
      this.options.log(`turn ${message.id} failed to settle: ${error instanceof Error ? error.message : String(error)}`);
      return;
    }
    this.state = { ...this.state, activeSessionId: this.idleIfNoTurns(sessionId) };

    if (outcome.error) {
      this.fail(`turn failed: ${outcome.error}`);
      await this.replyAndFinish(message, `处理失败：${truncate(outcome.error, 200)}`, true, workspace, sessionId, title);
      return;
    }
    let reply = (outcome.output ?? '').trim();
    if (!reply) reply = await this.lastAssistantText(sessionId, workspace);
    if (!reply) {
      reply = outcome.cancelled ? '已取消。' : '这一轮没有产出。';
    }
    await this.replyAndFinish(message, reply, false, workspace, sessionId, title);
  }

  /**
   * The engine took the message without opening a turn for it (it steered a running turn, or it
   * queued it behind one). The turn that is running answers to the message that started it, so
   * this one gets a receipt and is marked handled; the entry stays around because a steer that
   * missed the turn's last model call, or a queued message, may still run a turn of its own.
   */
  private async absorb(
    message: OcgInboundMessage,
    workspace: string,
    sessionId: string,
    title: string,
    receipt: string,
  ): Promise<void> {
    this.absorbed.set(message.id, { message, expiresAt: Date.now() + ABSORBED_TTL_MS });
    this.pruneAbsorbed();
    await this.cancelProgress(message.id);
    const result = await this.options.ocg.reply(message.id, receipt, false, this.options.consumer, `${message.id}:1`);
    if (!result.ok && result.code !== 'ALREADY_REPLIED') {
      this.absorbed.delete(message.id);
      this.fail(`reply failed: ${result.error ?? 'unknown error'}`);
      await this.finishWithoutReply(message, 'failed');
      return;
    }
    this.store.markHandled(message.id, 'replied');
    this.claimed.delete(message.id);
    this.state = { ...this.state, lastReplyAt: Date.now(), activeSessionId: this.idleIfNoTurns(sessionId) };
    this.emit();
    this.options.log(`accepted ${message.id} without its own turn yet (${receipt})`);
    this.options.origins?.clear(message.id);
    if (sessionId) {
      this.options.onSession({ kind: 'replied', sessionId, workspace, title, sessionKey: this.sessionKeyOf(message) });
    }
  }

  /** The receipt only covered acceptance: the message ran (or was drained) as a turn of its own. */
  private async deliverAbsorbed(entry: AbsorbedMessage, text: string): Promise<void> {
    const to = entry.message.target;
    if (!to) return;
    const result = await this.options.ocg.send(entry.message.channel, to, text, entry.message.accountId);
    if (!result.ok) {
      this.options.log(`answer for ${entry.message.id} send failed: ${result.error ?? 'unknown error'}`);
      return;
    }
    this.options.log(`answered ${entry.message.id} with its own turn`);
  }

  private pruneAbsorbed(): void {
    const now = Date.now();
    for (const [id, entry] of this.absorbed) {
      if (entry.expiresAt <= now) this.absorbed.delete(id);
    }
  }

  private idleIfNoTurns(sessionId: string): string | null {
    for (const turn of this.pending.values()) {
      if (turn.sessionId === sessionId) return sessionId;
    }
    return null;
  }

  private async replyAndFinish(
    message: OcgInboundMessage,
    text: string,
    isError: boolean,
    workspace: string,
    sessionId: string,
    title: string,
  ): Promise<void> {
    await this.cancelProgress(message.id);
    const result = await this.options.ocg.reply(
      message.id,
      text,
      isError,
      this.options.consumer,
      `${message.id}:1`,
    );
    if (!result.ok && result.code !== 'ALREADY_REPLIED') {
      this.fail(`reply failed: ${result.error ?? 'unknown error'}`);
      await this.finishWithoutReply(message, 'failed');
      return;
    }
    this.store.markHandled(message.id, 'replied');
    this.claimed.delete(message.id);
    this.state = { ...this.state, lastReplyAt: Date.now(), activeSessionId: this.idleIfNoTurns(sessionId) };
    this.emit();
    this.options.log(`replied ${message.id} textLen=${text.length}`);
    this.options.origins?.clear(message.id);
    if (sessionId) {
      this.options.onSession({ kind: 'replied', sessionId, workspace, title, sessionKey: this.sessionKeyOf(message) });
    }
  }

  private async finishWithoutReply(message: OcgInboundMessage, state: 'acked' | 'failed'): Promise<void> {
    await this.cancelProgress(message.id);
    await this.ack(message.id);
    this.store.markHandled(message.id, state);
    this.claimed.delete(message.id);
    this.options.origins?.clear(message.id);
  }

  private async ack(id: string): Promise<void> {
    const result = await this.options.ocg.ack(id, this.options.consumer);
    if (!result.ok) this.options.log(`ack ${id} failed: ${result.error ?? 'unknown error'}`);
  }

  private scheduleProgress(message: OcgInboundMessage): void {
    const delay = this.options.progressDelayMs ?? PROGRESS_DELAY_MS;
    const timer = setTimeout(() => {
      this.progressTimers.delete(message.id);
      const work = this.sendProgress(message).catch(() => undefined);
      this.progressWork.set(message.id, work);
    }, delay);
    this.progressTimers.set(message.id, timer);
  }

  private async cancelProgress(id: string): Promise<void> {
    const timer = this.progressTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.progressTimers.delete(id);
    }
    const work = this.progressWork.get(id);
    if (work) {
      await work;
      this.progressWork.delete(id);
    }
  }

  private async sendProgress(message: OcgInboundMessage): Promise<void> {
    if (!this.running || this.store.isHandled(message.id)) return;
    const to = message.target;
    if (!to) return;
    const result = await this.options.ocg.send(
      message.channel,
      to,
      this.options.progressText ?? PROGRESS_TEXT,
      message.accountId,
    );
    if (!result.ok) this.options.log(`progress ${message.id} failed: ${result.error ?? 'unknown error'}`);
  }

  private async stageInboundMedia(
    message: OcgInboundMessage,
    sessionId: string,
    sourcePaths: string[],
  ): Promise<StagedAttachment[]> {
    if (sourcePaths.length === 0) return [];
    if (!this.options.stageMedia) {
      this.options.log(`message ${message.id}: stageMedia is not configured, ${sourcePaths.length} attachment(s) ignored`);
      return [];
    }
    try {
      const staged = await this.options.stageMedia(sessionId, sourcePaths);
      const attachments = (staged ?? []).filter((attachment) => attachment && attachment.path);
      if (attachments.length < sourcePaths.length) {
        this.options.log(`message ${message.id}: staged ${attachments.length}/${sourcePaths.length} attachment(s)`);
      }
      return attachments;
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      this.options.log(`message ${message.id}: media staging failed: ${detail}`);
      return [];
    }
  }

  private async resolveSession(message: OcgInboundMessage, workspace: string): Promise<{ sessionId: string; title: string }> {
    const sessionKey = this.sessionKeyOf(message);
    const existing = this.store.getSession(sessionKey);
    if (existing && existing.workspace === workspace) {
      try {
        await this.options.callEngine(workspace, 'session/resume', { sessionId: existing.sessionId });
        return { sessionId: existing.sessionId, title: await this.retitleLegacySession(message, workspace, existing) };
      } catch (error) {
        const code = engineErrorCode(error);
        // Busy/newer-version sessions are recoverable only by the process/user that owns them;
        // creating a fresh session here would silently fork the IM conversation.
        if (code === 'SESSION_BUSY') throw new Error(`session ${existing.sessionId} is in use by another process`);
        if (code === 'SESSION_NEWER_VERSION') throw new Error(`session ${existing.sessionId} needs a newer core-ai`);
        if (code !== 'SESSION_NOT_FOUND' && code !== 'SESSION_CORRUPT') throw error;
        // deleted or corrupt: fall through and create a replacement
      }
    }
    const title = this.titleFor(message);
    const created = await this.options.callEngine(workspace, 'session/create', { title }) as { sessionId?: unknown };
    const sessionId = typeof created?.sessionId === 'string' ? created.sessionId : '';
    if (!sessionId) throw new Error('session/create returned no sessionId');
    this.store.setSession(sessionKey, { sessionId, workspace, title, updatedAt: Date.now() });
    this.options.onSession({ kind: 'created', sessionId, workspace, title, sessionKey });
    return { sessionId, title };
  }

  private createWaiter(sessionId: string, workspace: string, turnId: string): PendingTurn {
    let resolvePromise: (outcome: TurnOutcome) => void = () => undefined;
    const promise = new Promise<TurnOutcome>((resolve) => { resolvePromise = resolve; });
    const turn: PendingTurn = {
      workspace,
      sessionId,
      turnId,
      promise,
      settle: () => undefined,
      detach: () => undefined,
    };
    const remove = (): void => {
      if (this.pending.get(turnId) === turn) this.pending.delete(turnId);
    };
    const timer = setTimeout(() => turn.settle({ error: 'turn timed out' }), TURN_TIMEOUT_MS);
    turn.settle = (outcome: TurnOutcome): void => {
      clearTimeout(timer);
      remove();
      resolvePromise(outcome);
    };
    turn.detach = (): void => {
      clearTimeout(timer);
      remove();
    };
    this.pending.set(turnId, turn);
    return turn;
  }

  private async lastAssistantText(sessionId: string, workspace: string): Promise<string> {
    try {
      const history = await this.options.callEngine(workspace, 'session/history', { sessionId, limit: 10 }) as {
        messages?: Array<{ role?: string; text?: string }>;
      };
      const messages = history?.messages ?? [];
      for (let index = messages.length - 1; index >= 0; index -= 1) {
        const message = messages[index];
        if (message.role === 'assistant' && typeof message.text === 'string' && message.text.trim()) return message.text.trim();
      }
    } catch { /* fall through to the caller's fallback text */ }
    return '';
  }

  private sessionKeyOf(message: OcgInboundMessage): string {
    if (message.sessionKey) return message.sessionKey;
    return `${message.channel}:${message.accountId}:${message.chatType}:${message.target ?? message.sender.id ?? 'unknown'}`;
  }

  private titleFor(message: OcgInboundMessage): string {
    const label = channelLabel(message.channel);
    if (message.chatType === 'group') {
      return `${label}群 · ${message.groupSubject ?? message.target ?? '未命名'}`;
    }
    // WeChat direct chats only expose an openid, so the opening line (or the attached file) names the session.
    if (message.channel === 'openclaw-weixin') {
      const opening = this.openingOf(message);
      if (opening) return `${label} · ${opening}`;
    }
    return `${label} · ${this.peerName(message)}`;
  }

  /** First typed line, or the first attached file name for a media-only message. */
  private openingOf(message: OcgInboundMessage): string {
    const text = titleText(message.text);
    if (text) return text;
    for (const entry of message.media ?? []) {
      if (!entry.path) continue;
      return titleText(entry.path.replace(/\\/g, '/').split('/').pop() ?? '');
    }
    return '';
  }

  private peerName(message: OcgInboundMessage): string {
    return message.sender.name ?? message.sender.id ?? message.target ?? '未知联系人';
  }

  /** Titles created before the first-message rule carried the peer openid; upgrade the mapped ones once. */
  private async retitleLegacySession(message: OcgInboundMessage, workspace: string, existing: BridgeSessionRecord): Promise<string> {
    if (message.channel !== 'openclaw-weixin' || message.chatType !== 'direct') return existing.title;
    const legacy = `${channelLabel(message.channel)} · ${this.peerName(message)}`;
    if (existing.title !== legacy) return existing.title;
    const currentTitle = this.options.sessionTitle?.(workspace, existing.sessionId) ?? null;
    // Missing or different title means the user renamed it (or it cannot be read); never overwrite that.
    if (!currentTitle) return existing.title;
    if (currentTitle !== legacy) return this.replaceSessionTitle(message, existing, currentTitle);
    const opening = titleText(this.options.firstUserText?.(workspace, existing.sessionId));
    if (!opening) return existing.title;
    const title = `${channelLabel(message.channel)} · ${opening}`;
    try {
      await this.options.callEngine(workspace, 'session/rename', { sessionId: existing.sessionId, title });
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      this.options.log(`session ${existing.sessionId} title upgrade skipped: ${detail}`);
      return existing.title;
    }
    return this.replaceSessionTitle(message, existing, title);
  }

  private replaceSessionTitle(message: OcgInboundMessage, existing: BridgeSessionRecord, title: string): string {
    if (title === existing.title) return title;
    const sessionKey = this.sessionKeyOf(message);
    this.store.setSession(sessionKey, { ...existing, title, updatedAt: Date.now() });
    this.options.onSession({ kind: 'renamed', sessionId: existing.sessionId, workspace: existing.workspace, title, sessionKey });
    return title;
  }

  private fail(message: string): void {
    this.state = { ...this.state, lastError: message };
    this.emit();
    this.options.log(message);
  }

  private emit(): void {
    this.options.onState(this.snapshot());
  }
}

function engineErrorCode(error: unknown): string | undefined {
  const data = (error as { data?: { code?: unknown } } | null)?.data;
  return typeof data?.code === 'string' ? data.code : undefined;
}
