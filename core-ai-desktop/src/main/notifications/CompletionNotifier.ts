// Session-completion notifications: the engine's terminal turn events are the only trigger.
// Delivery lives in the main process so it keeps working while the window is hidden in the tray.
export interface NotifyTarget {
  channel: string;
  accountId?: string;
  target: string;
  label?: string;
  chatType?: string;
}

export interface NotifyDelivery {
  ok: boolean;
  workspace: string;
  sessionId: string;
  targetLabel?: string;
  error?: string;
}

export interface CompletionNotice {
  title: string;
  failed: boolean;
  status: string;
  durationMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  costUsd?: number | null;
  output?: string | null;
  error?: string | null;
}

export interface CompletionNotifierOptions {
  isEnabled: (workspace: string, sessionId: string) => boolean;
  target: () => NotifyTarget | null;
  send: (target: NotifyTarget, text: string) => Promise<{ ok: boolean; error?: string }>;
  sessionTitle: (workspace: string, sessionId: string) => string | null;
  log: (line: string) => void;
  onDelivery?: (result: NotifyDelivery) => void;
}

const MAX_DETAIL_LENGTH = 200;
const MAX_SENT_KEYS = 200;

const isText = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;

/**
 * Persistent settings format. Kept here so the main process can validate both loaded and patched
 * values without importing the Electron-dependent main module into tests.
 */
export function normalizeNotifyTarget(value: unknown): NotifyTarget | null {
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  const channel = isText(record.channel) ? record.channel.trim().slice(0, 120) : '';
  const target = isText(record.target) ? record.target.trim().slice(0, 500) : '';
  if (!channel || !target) return null;
  const normalized: NotifyTarget = { channel, target };
  if (isText(record.accountId)) normalized.accountId = record.accountId.trim().slice(0, 200);
  if (isText(record.label)) normalized.label = record.label.trim().slice(0, 200);
  if (isText(record.chatType)) normalized.chatType = record.chatType.trim().slice(0, 40);
  return normalized;
}

export function normalizeNotifySessions(value: unknown): Record<string, boolean> {
  const result: Record<string, boolean> = {};
  if (!value || typeof value !== 'object' || Array.isArray(value)) return result;
  let count = 0;
  for (const [key, enabled] of Object.entries(value as Record<string, unknown>)) {
    if (enabled !== true) continue;
    if (!key || key.length > 1000) continue;
    result[key] = true;
    count += 1;
    if (count >= MAX_SENT_KEYS * 5) break;
  }
  return result;
}

export function notifySessionKey(workspace: string, sessionId: string): string {
  return `${workspace}::${sessionId}`;
}

function truncateDetail(value: string | null | undefined): string {
  const text = (value ?? '').replace(/\s+/g, ' ').trim();
  if (!text) return '';
  return text.length <= MAX_DETAIL_LENGTH ? text : `${text.slice(0, MAX_DETAIL_LENGTH)}...`;
}

function compact(value: number, scale: number): string {
  const tenths = Math.round((value / scale) * 10);
  if (tenths % 10 === 0) return String(tenths / 10);
  return `${Math.floor(tenths / 10)}.${tenths % 10}`;
}

function compactTokens(tokens: number): string {
  if (tokens >= 1_000_000) return `${compact(tokens, 1_000_000)}M`;
  if (tokens >= 1_000) return `${compact(tokens, 1_000)}K`;
  return String(tokens);
}

function formatCost(cost: number): string {
  const fixed = cost > 0 && cost < 0.01 ? cost.toFixed(4) : cost.toFixed(2);
  return `$${fixed.replace(/0+$/, '').replace(/\.$/, '')}`;
}

export function formatDuration(durationMs: number): string {
  if (durationMs < 1_000) return `${Math.max(0, Math.round(durationMs))}ms`;
  const totalSeconds = Math.max(1, Math.round(durationMs / 1_000));
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  const two = (value: number): string => (value < 10 ? `0${value}` : String(value));
  if (hours > 0) return `${hours}h ${two(minutes)}m`;
  if (minutes > 0) return `${minutes}m ${two(seconds)}s`;
  return `${seconds}s`;
}

export function formatCompletionNotice(notice: CompletionNotice): string {
  const title = truncateDetail(notice.title) || 'session';
  const lines: string[] = [`${notice.failed ? 'Session failed' : 'Session finished'} — ${title}`];
  if (notice.failed) {
    const detail = truncateDetail(notice.error);
    const status = detail ? `failed — ${detail}` : 'failed';
    lines.push(notice.durationMs != null ? `Duration: ${formatDuration(notice.durationMs)} · ${status}` : `Status: ${status}`);
    return lines.join('\n');
  }

  const status = notice.status || 'completed';
  lines.push(notice.durationMs != null ? `Duration: ${formatDuration(notice.durationMs)} · ${status}` : `Status: ${status}`);
  const usage: string[] = [];
  if (notice.costUsd != null && notice.costUsd > 0) usage.push(`Cost: ${formatCost(notice.costUsd)}`);
  if (notice.inputTokens != null || notice.outputTokens != null) {
    usage.push(`tokens ${compactTokens(notice.inputTokens ?? 0)} in / ${compactTokens(notice.outputTokens ?? 0)} out`);
  }
  if (usage.length) lines.push(usage.join(' · '));
  const detail = truncateDetail(notice.output);
  if (detail) lines.push(detail);
  return lines.join('\n');
}

export class CompletionNotifier {
  private readonly turnStarted = new Map<string, number>();
  private readonly sent = new Set<string>();

  constructor(private readonly options: CompletionNotifierOptions) {}

  /** Called from the engine notification fan-out; never throws into the RPC loop. */
  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'session/event') return;
    const sessionId = typeof params.sessionId === 'string' ? params.sessionId : '';
    const rawEvent = params.event;
    if (!sessionId || !rawEvent || typeof rawEvent !== 'object') return;
    const event = rawEvent as Record<string, unknown>;
    const type = typeof event.type === 'string' ? event.type : '';
    const turnKey = `${workspace}\u0000${sessionId}`;

    if (type === 'status_change') {
      if (event.status === 'running') this.turnStarted.set(turnKey, Date.now());
      else this.turnStarted.delete(turnKey);
      return;
    }
    if (type !== 'turn_complete' && type !== 'error') return;
    const startedAt = this.turnStarted.get(turnKey);
    this.turnStarted.delete(turnKey);
    if (type === 'turn_complete' && event.cancelled === true) return;
    if (!this.options.isEnabled(workspace, sessionId)) return;

    const target = this.options.target();
    if (!target) {
      this.options.log('skipped: notification target is not configured');
      return;
    }
    const turnId = typeof event.turn_id === 'string' ? event.turn_id : '';
    const dedupeKey = `${turnKey}\u0000${turnId}\u0000${type}`;
    if (turnId && this.sent.has(dedupeKey)) return;
    if (turnId) this.remember(dedupeKey);

    const title = this.options.sessionTitle(workspace, sessionId)?.trim() || `session ${sessionId.slice(0, 8)}`;
    const durationMs = typeof event.duration_ms === 'number'
      ? event.duration_ms
      : startedAt
        ? Date.now() - startedAt
        : null;
    const text = formatCompletionNotice({
      title,
      failed: type === 'error',
      status: type === 'error' ? 'failed' : completionStatus(event),
      durationMs,
      inputTokens: typeof event.input_tokens === 'number' ? event.input_tokens : null,
      outputTokens: typeof event.output_tokens === 'number' ? event.output_tokens : null,
      costUsd: typeof event.cost_usd === 'number' ? event.cost_usd : null,
      output: typeof event.output === 'string' ? event.output : null,
      error: typeof event.message === 'string' ? event.message : null,
    });
    void this.deliver(workspace, sessionId, target, text);
  }

  private async deliver(workspace: string, sessionId: string, target: NotifyTarget, text: string): Promise<void> {
    const targetLabel = target.label || target.channel;
    try {
      const result = await this.options.send(target, text);
      if (result.ok) {
        this.options.log(`sent session completion, sessionId=${sessionId}, channel=${target.channel}`);
        this.report({ ok: true, workspace, sessionId, targetLabel });
        return;
      }
      const error = result.error || 'unknown error';
      this.options.log(`send failed, sessionId=${sessionId}, channel=${target.channel}: ${error}`);
      this.report({ ok: false, workspace, sessionId, targetLabel, error });
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      this.options.log(`send failed, sessionId=${sessionId}, channel=${target.channel}: ${detail}`);
      this.report({ ok: false, workspace, sessionId, targetLabel, error: detail });
    }
  }

  private report(result: NotifyDelivery): void {
    try {
      this.options.onDelivery?.(result);
    } catch {
      // A broken renderer listener must never turn a delivery outcome into an exception.
    }
  }

  private remember(key: string): void {
    this.sent.add(key);
    if (this.sent.size <= MAX_SENT_KEYS) return;
    const oldest = this.sent.values().next().value;
    if (oldest) this.sent.delete(oldest);
  }
}

function completionStatus(event: Record<string, unknown>): string {
  let status = event.max_turns_reached === true ? 'stopped at max turns' : 'completed';
  if (event.budget_exhausted === true) status += ' · budget exhausted';
  return status;
}
