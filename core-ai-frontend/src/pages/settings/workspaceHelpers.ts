import type { WorkspaceView } from '../../api/client';

export type Tone = 'ok' | 'info' | 'warn' | 'error' | 'muted';

export const MIB = 1024 ** 2;
export const GIB = 1024 ** 3;
export const USAGE_WARN_RATIO = 0.9;
export const USAGE_CRITICAL_RATIO = 0.95;
export const PURGE_AFTER_DAYS = 7;

export const TONE_STYLES: Record<Tone, { bg: string; text: string }> = {
  ok: { bg: '#dcfce7', text: '#16a34a' },
  info: { bg: '#dbeafe', text: '#2563eb' },
  warn: { bg: '#fef3c7', text: '#b45309' },
  error: { bg: '#fee2e2', text: '#dc2626' },
  muted: { bg: '#f1f5f9', text: '#64748b' },
};

export function workspaceStatus(workspace: Pick<WorkspaceView, 'volume_status' | 'runtime_status'>): { label: string; tone: Tone } {
  if (workspace.volume_status === 'ERROR') return { label: 'Volume error', tone: 'error' };
  if (workspace.volume_status === 'PROVISIONING') return { label: 'Preparing', tone: 'warn' };
  if (workspace.runtime_status === 'READY') return { label: 'Running', tone: 'ok' };
  if (workspace.runtime_status === 'PROVISIONING') return { label: 'Waking', tone: 'info' };
  if (workspace.runtime_status === 'ERROR') return { label: 'Wake error', tone: 'error' };
  return { label: 'Sleeping', tone: 'muted' };
}

export function relativeTime(iso?: string): string {
  if (!iso) return 'never';
  const diff = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(diff) || diff < 0) return 'just now';
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function purgeDaysLeft(deletedAt: string): number {
  const elapsed = (Date.now() - new Date(deletedAt).getTime()) / 86_400_000;
  return Math.max(0, Math.ceil(PURGE_AFTER_DAYS - elapsed));
}

export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Workspace request failed.';
}

export function workspaceDisabled(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { errorCode?: string }).errorCode === 'WORKSPACE_UNSUPPORTED';
}

export function isNotFound(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const candidate = err as { status?: number; errorCode?: string };
  return candidate.status === 404 || candidate.errorCode === 'WORKSPACE_NOT_FOUND';
}

export function formatBytes(bytes: number): string {
  if (bytes >= GIB) {
    const gib = bytes / GIB;
    return `${gib >= 100 ? gib.toFixed(0) : gib.toFixed(1)} GiB`;
  }
  if (bytes >= MIB) {
    const mib = bytes / MIB;
    return `${mib >= 100 ? mib.toFixed(0) : mib.toFixed(1)} MiB`;
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KiB`;
  }
  return `${bytes} B`;
}

export function usageSummary(usedBytes?: number, capacityBytes?: number): {
  label: string;
  percent: number;
  warning: boolean;
  critical: boolean;
} | null {
  if (usedBytes == null || capacityBytes == null || capacityBytes <= 0) return null;
  const ratio = usedBytes / capacityBytes;
  return {
    label: `${formatBytes(usedBytes)} / ${formatBytes(capacityBytes)}`,
    percent: Math.round(ratio * 100),
    warning: ratio >= USAGE_WARN_RATIO,
    critical: ratio >= USAGE_CRITICAL_RATIO,
  };
}

export function payloadSummary(payload?: string): string {
  if (!payload) return '';
  try {
    const value = JSON.parse(payload) as Record<string, unknown>;
    return Object.entries(value).map(([key, entry]) => `${key}=${String(entry)}`).join(' · ');
  } catch {
    return payload;
  }
}

export function truncateLog(log?: string, maxLines = 18, maxChars = 1500): { text: string; truncated: boolean } {
  if (!log) return { text: '', truncated: false };
  const lines = log.split('\n');
  let truncated = false;
  let sliced = log;
  if (lines.length > maxLines) {
    sliced = lines.slice(-maxLines).join('\n');
    truncated = true;
  }
  if (sliced.length > maxChars) {
    sliced = sliced.slice(-maxChars);
    truncated = true;
  }
  return { text: sliced, truncated };
}
