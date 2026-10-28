// P0 IM bridge: pull authorized messages from the local OCG daemon, run one engine turn
// per message, and post the final answer back. P1 replaces the CLI polling with the
// versioned OCG HTTP client and adds bindings/authorization/journaling.
import { BridgeStore } from './BridgeStore';
import type { OcgInboundMessage, OcgResult } from './OcgCli';

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
  kind: 'created' | 'replied';
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
  turnId: string;
  finish: (outcome: TurnOutcome) => void;
}

const WAIT_SECONDS = 20;
const TURN_TIMEOUT_MS = 10 * 60 * 1000;
const RETRY_MIN_MS = 5_000;
const RETRY_MAX_MS = 60_000;
const PROGRESS_DELAY_MS = 2_500;
const PROGRESS_TEXT = '收到，正在处理…';

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

export class ChannelBridge {
  private readonly store: BridgeStore;
  private readonly pending = new Map<string, PendingTurn>();
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
    for (const timer of this.progressTimers.values()) clearTimeout(timer);
    this.progressTimers.clear();
    this.state = { ...this.state, enabled: false, running: false, activeSessionId: null };
    this.emit();
  }

  /** Called from EngineSupervisor's notification fan-out, for every workspace. */
  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'session/event') return;
    const sessionId = typeof params.sessionId === 'string' ? params.sessionId : '';
    const event = params.event as Record<string, unknown> | undefined;
    if (!sessionId || !event) return;
    const pending = this.pending.get(sessionId);
    if (!pending || pending.workspace !== workspace) return;
    const turnId = typeof event.turn_id === 'string' ? event.turn_id : undefined;
    if (turnId && turnId !== pending.turnId) return;
    if (event.type === 'turn_complete') {
      pending.finish({
        output: typeof event.output === 'string' ? event.output : '',
        cancelled: event.cancelled === true,
      });
    } else if (event.type === 'error') {
      pending.finish({ error: typeof event.message === 'string' ? event.message : 'engine error' });
    }
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
    if (this.store.isHandled(message.id)) {
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
    if (!text) {
      if (message.media.length > 0) {
        await this.replyAndFinish(message, '当前版本只支持文本消息。', false, '', '', '');
      } else {
        this.options.log(`skip ${message.id}: empty message`);
        await this.finishWithoutReply(message, 'acked');
      }
      return;
    }
    const workspace = this.options.workspace(message);
    if (!workspace) {
      this.fail('No workspace is available for IM messages');
      await this.finishWithoutReply(message, 'failed');
      return;
    }

    this.scheduleProgress(message);
    this.state = {
      ...this.state,
      lastMessageAt: Date.now(),
      lastPeer: message.sender.name ?? message.sender.id ?? message.target ?? message.channel,
    };
    this.emit();
    this.options.log(`message ${message.id} channel=${message.channel} chat=${message.chatType} textLen=${text.length}`);

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

    const waiter = this.createWaiter(sessionId, workspace, message.id);
    try {
      await this.options.callEngine(workspace, 'session/send', {
        sessionId,
        turnId: message.id,
        parts: [{ type: 'text', text }],
      });
    } catch (error) {
      waiter.cancel();
      const detail = error instanceof Error ? error.message : String(error);
      this.fail(`session/send failed: ${detail}`);
      await this.replyAndFinish(message, `处理失败：${truncate(detail, 200)}`, true, workspace, sessionId, title);
      return;
    }

    const outcome = await waiter.promise;
    this.state = { ...this.state, activeSessionId: null };
    const sessionKey = this.sessionKeyOf(message);

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
    this.state = { ...this.state, lastReplyAt: Date.now(), activeSessionId: null };
    this.emit();
    this.options.log(`replied ${message.id} textLen=${text.length}`);
    if (sessionId) {
      this.options.onSession({ kind: 'replied', sessionId, workspace, title, sessionKey: this.sessionKeyOf(message) });
    }
  }

  private async finishWithoutReply(message: OcgInboundMessage, state: 'acked' | 'failed'): Promise<void> {
    await this.cancelProgress(message.id);
    await this.ack(message.id);
    this.store.markHandled(message.id, state);
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

  private async resolveSession(message: OcgInboundMessage, workspace: string): Promise<{ sessionId: string; title: string }> {
    const sessionKey = this.sessionKeyOf(message);
    const existing = this.store.getSession(sessionKey);
    if (existing && existing.workspace === workspace) {
      try {
        await this.options.callEngine(workspace, 'session/resume', { sessionId: existing.sessionId });
        return { sessionId: existing.sessionId, title: existing.title };
      } catch { /* the session was deleted or is no longer loadable */ }
    }
    const title = this.titleFor(message);
    const created = await this.options.callEngine(workspace, 'session/create', { title }) as { sessionId?: unknown };
    const sessionId = typeof created?.sessionId === 'string' ? created.sessionId : '';
    if (!sessionId) throw new Error('session/create returned no sessionId');
    this.store.setSession(sessionKey, { sessionId, workspace, title, updatedAt: Date.now() });
    this.options.onSession({ kind: 'created', sessionId, workspace, title, sessionKey });
    return { sessionId, title };
  }

  private createWaiter(sessionId: string, workspace: string, turnId: string): { promise: Promise<TurnOutcome>; cancel: () => void } {
    let resolvePromise: (outcome: TurnOutcome) => void = () => undefined;
    let finish: (outcome: TurnOutcome) => void = () => undefined;
    const promise = new Promise<TurnOutcome>((resolve) => { resolvePromise = resolve; });
    const entry: PendingTurn = {
      workspace,
      turnId,
      finish: (outcome) => finish(outcome),
    };
    const timer = setTimeout(() => finish({ error: 'turn timed out' }), TURN_TIMEOUT_MS);
    finish = (outcome: TurnOutcome): void => {
      clearTimeout(timer);
      if (this.pending.get(sessionId) === entry) this.pending.delete(sessionId);
      resolvePromise(outcome);
    };
    this.pending.set(sessionId, entry);
    return { promise, cancel: () => finish({ error: 'turn not started' }) };
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
    return `${label} · ${message.sender.name ?? message.sender.id ?? message.target ?? '未知联系人'}`;
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
