import type { McpConnectionState } from '../../api/client';

const STATE_STYLE: Record<McpConnectionState, { bg: string; fg: string; label: string }> = {
  CONNECTED: { bg: '#065f46', fg: '#fff', label: 'Connected' },
  CONNECTING: { bg: '#1f3a8a', fg: '#fff', label: 'Connecting' },
  RECONNECTING: { bg: '#92400e', fg: '#fff', label: 'Reconnecting' },
  DISCONNECTED: { bg: 'var(--color-bg-tertiary)', fg: 'var(--color-text-secondary)', label: 'Disconnected' },
  FAILED: { bg: '#7f1d1d', fg: '#fff', label: 'Failed' },
  NOT_CONNECTED: { bg: 'var(--color-bg-tertiary)', fg: 'var(--color-text-secondary)', label: 'Idle' },
};

export function ConnectionStateBadge({ state }: { state?: McpConnectionState }) {
  const v = STATE_STYLE[state ?? 'NOT_CONNECTED'];
  return (
    <span className="px-2 py-0.5 rounded text-xs" style={{ background: v.bg, color: v.fg }}>
      {v.label}
    </span>
  );
}

export function EnabledBadge({ enabled }: { enabled: boolean }) {
  return (
    <span className="px-2 py-0.5 rounded text-xs"
      style={enabled
        ? { background: '#065f46', color: '#fff' }
        : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  );
}
