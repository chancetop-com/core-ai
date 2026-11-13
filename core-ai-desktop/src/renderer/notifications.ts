// Pure helpers shared by the composer switch, the setup dialog and Settings.
// Kept outside app.ts so labels, masking and settings shapes can be unit tested.
export interface NotifyTargetView {
  channel: string;
  accountId?: string;
  target: string;
  label?: string;
  chatType?: string;
}

export interface OcgTargetView {
  channel: string;
  accountId?: string;
  target: string;
  chatType?: string;
  senderName?: string | null;
  groupSubject?: string | null;
  lastSeenAt?: number;
}

const CHANNEL_LABELS: Record<string, string> = {
  'openclaw-weixin': '微信',
  qqbot: 'QQ',
  telegram: 'Telegram',
  'dingtalk-connector': '钉钉',
  discord: 'Discord',
};

export function notifyChannelLabel(channel: string): string {
  return CHANNEL_LABELS[channel] ?? (channel || 'notification');
}

export function maskNotifyTarget(value: string): string {
  const text = (value ?? '').trim();
  if (text.length <= 10) return text;
  return `${text.slice(0, 4)}…${text.slice(-4)}`;
}

export function notifyTargetKey(target: Pick<NotifyTargetView, 'channel' | 'accountId' | 'target'>): string {
  return `${target.channel}\u0000${target.accountId ?? ''}\u0000${target.target}`;
}

export function notifyTargetDisplayLabel(target: NotifyTargetView | null | undefined): string {
  if (!target || !target.channel || !target.target) return 'Not configured';
  const shared = target.chatType === 'group' ? ' (shared)' : '';
  return `${notifyChannelLabel(target.channel)} · ${target.label || maskNotifyTarget(target.target)}${shared}`;
}

export function notifyTargetOptionLabel(target: OcgTargetView): string {
  const name = target.senderName || target.groupSubject || maskNotifyTarget(target.target);
  const shared = target.chatType === 'group' ? ' (shared)' : '';
  return `${notifyChannelLabel(target.channel)} · ${name}${shared}`;
}

export function notifyTargetFromOcg(target: OcgTargetView): NotifyTargetView {
  const label = target.senderName || target.groupSubject || maskNotifyTarget(target.target);
  return {
    channel: target.channel,
    ...(target.accountId ? { accountId: target.accountId } : {}),
    target: target.target,
    label,
    ...(target.chatType ? { chatType: target.chatType } : {}),
  };
}

export function normalizeNotifyTargetView(value: unknown): NotifyTargetView | null {
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  const channel = typeof record.channel === 'string' ? record.channel.trim() : '';
  const target = typeof record.target === 'string' ? record.target.trim() : '';
  if (!channel || !target) return null;
  const normalized: NotifyTargetView = { channel, target };
  if (typeof record.accountId === 'string' && record.accountId.trim()) normalized.accountId = record.accountId.trim();
  if (typeof record.label === 'string' && record.label.trim()) normalized.label = record.label.trim();
  if (typeof record.chatType === 'string' && record.chatType.trim()) normalized.chatType = record.chatType.trim();
  return normalized;
}

export function normalizeNotifySessionsMap(value: unknown): Record<string, boolean> {
  const result: Record<string, boolean> = {};
  if (!value || typeof value !== 'object' || Array.isArray(value)) return result;
  for (const [key, enabled] of Object.entries(value as Record<string, unknown>)) {
    if (enabled === true && key && key.length <= 1000) result[key] = true;
  }
  return result;
}

export function notifySessionKey(workspace: string, sessionId: string): string {
  return `${workspace}::${sessionId}`;
}
