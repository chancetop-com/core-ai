import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, CheckCircle2, EyeOff, RotateCcw } from 'lucide-react';
import {
  skillTelemetryApi,
  type SkillPapercut,
  type SkillPapercutStatus,
  type SkillTelemetryResponse,
} from '../../api/quality';

interface Props {
  skillId: string;
  canManage: boolean;
}

const STATUS_BADGE_STYLES: Record<SkillPapercutStatus, { bg: string; color: string }> = {
  OPEN: { bg: 'rgba(239,68,68,0.16)', color: '#fca5a5' },
  RESOLVED: { bg: 'rgba(16,185,129,0.16)', color: '#6ee7b7' },
  IGNORED: { bg: 'rgba(148,163,184,0.18)', color: '#cbd5e1' },
};

const FILTER_OPTIONS: Array<SkillPapercutStatus | 'ALL'> = ['ALL', 'OPEN', 'RESOLVED', 'IGNORED'];

export default function SkillHealthPanel({ skillId, canManage }: Props) {
  const [telemetry, setTelemetry] = useState<SkillTelemetryResponse | null>(null);
  const [papercuts, setPapercuts] = useState<SkillPapercut[]>([]);
  const [statusFilter, setStatusFilter] = useState<SkillPapercutStatus | 'ALL'>('ALL');
  const [loading, setLoading] = useState(true);
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    Promise.all([
      skillTelemetryApi.getTelemetry(skillId),
      skillTelemetryApi.listPapercuts(skillId, statusFilter === 'ALL' ? undefined : statusFilter),
    ])
      .then(([statsRes, papercutRes]) => {
        if (!active) return;
        setTelemetry(statsRes);
        setPapercuts(papercutRes.papercuts || []);
      })
      .catch(() => {
        if (!active) return;
        setTelemetry(null);
        setPapercuts([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [skillId, statusFilter]);

  const handleStatusChange = async (papercut: SkillPapercut, nextStatus: SkillPapercutStatus) => {
    if (papercut.status === nextStatus) return;
    setUpdatingId(papercut.id);
    setError(null);
    try {
      const updated = await skillTelemetryApi.updatePapercutStatus(skillId, papercut.id, nextStatus);
      setPapercuts(prev => {
        const mapped = prev.map(item => (item.id === updated.id ? updated : item));
        return statusFilter === 'ALL' ? mapped : mapped.filter(item => item.status === statusFilter);
      });
      setTelemetry(prev => {
        if (!prev) return prev;
        let delta = 0;
        if (papercut.status === 'OPEN' && nextStatus !== 'OPEN') delta = -1;
        else if (papercut.status !== 'OPEN' && nextStatus === 'OPEN') delta = 1;
        return delta === 0 ? prev : { ...prev, open_papercut_count: Math.max(0, prev.open_papercut_count + delta) };
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update papercut status');
    } finally {
      setUpdatingId(null);
    }
  };

  const formatTime = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '-');

  return (
    <div
      data-testid="skill-health-panel"
      className="mb-6 p-4 rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
    >
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <Activity size={16} style={{ color: 'var(--color-primary)' }} />
          <h3 className="text-sm font-medium">Runtime Health &amp; Papercuts</h3>
        </div>
        {telemetry && !telemetry.telemetry_available && (
          <span className="text-xs px-2 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            Telemetry unavailable (PostgreSQL mode required)
          </span>
        )}
      </div>

      {loading && (
        <div className="text-xs py-4" style={{ color: 'var(--color-text-secondary)' }}>
          Loading runtime telemetry...
        </div>
      )}

      {!loading && telemetry && (
        <>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-4">
            <MetricCard label="Invocations" value={String(telemetry.total_invoke_count)} testId="metric-invocations" />
            <MetricCard label="Resource Reads" value={String(telemetry.total_resource_read_count)} testId="metric-resource-reads" />
            <MetricCard
              label="Script Errors"
              value={`${telemetry.total_attributed_script_errors} / ${telemetry.total_attributed_script_calls}`}
              testId="metric-script-errors"
              highlight={telemetry.total_attributed_script_errors > 0}
            />
            <MetricCard
              label="Open Papercuts"
              value={String(telemetry.open_papercut_count)}
              testId="metric-open-papercuts"
              highlight={telemetry.open_papercut_count > 0}
            />
            <MetricCard label="Last Invoked" value={formatTime(telemetry.last_invoked_at)} testId="metric-last-invoked" />
          </div>

          {(telemetry.total_attributed_script_errors > 0 || telemetry.open_papercut_count > 0) && (
            <div
              data-testid="skill-health-alert"
              className="mb-4 px-3 py-2 rounded-lg border text-xs flex items-center gap-2"
              style={{ background: 'rgba(245,158,11,0.12)', borderColor: '#f59e0b', color: '#fde68a' }}
            >
              <AlertTriangle size={14} style={{ color: '#fbbf24' }} />
              <span>
                Runtime friction detected ({telemetry.total_attributed_script_errors} script error(s), {telemetry.open_papercut_count} open papercut(s)). Review and update the skill if needed.
              </span>
            </div>
          )}

          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
              Papercuts ({papercuts.length})
            </span>
            <div className="flex items-center gap-1.5">
              {FILTER_OPTIONS.map(option => (
                <button
                  key={option}
                  type="button"
                  onClick={() => setStatusFilter(option)}
                  className="px-2 py-1 rounded text-xs cursor-pointer border"
                  style={{
                    borderColor: statusFilter === option ? 'var(--color-primary)' : 'var(--color-border)',
                    background: statusFilter === option ? 'var(--color-primary)' : 'transparent',
                    color: statusFilter === option ? '#fff' : 'var(--color-text-secondary)',
                  }}
                >
                  {option}
                </button>
              ))}
            </div>
          </div>

          {error && (
            <div role="alert" className="mb-3 px-3 py-2 rounded-lg text-xs"
              style={{ background: 'rgba(220,38,38,0.14)', color: '#fca5a5' }}>
              {error}
            </div>
          )}

          {papercuts.length === 0 ? (
            <div className="text-xs py-3" style={{ color: 'var(--color-text-secondary)' }}>
              No papercuts recorded for this filter.
            </div>
          ) : (
            <div className="space-y-3" data-testid="papercut-list">
              {papercuts.map(pc => {
                const badge = STATUS_BADGE_STYLES[pc.status] || STATUS_BADGE_STYLES.OPEN;
                const busy = updatingId === pc.id;
                return (
                  <div
                    key={pc.id}
                    data-testid={`papercut-row-${pc.id}`}
                    className="p-3 rounded-lg border text-xs"
                    style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)' }}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <span
                          data-testid={`papercut-status-${pc.id}`}
                          className="px-2 py-0.5 rounded font-medium"
                          style={{ background: badge.bg, color: badge.color }}
                        >
                          {pc.status}
                        </span>
                        <span className="px-2 py-0.5 rounded font-mono"
                          style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                          {pc.category}
                        </span>
                        <span className="font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                          x{pc.occurrence_count}
                        </span>
                        <span style={{ color: 'var(--color-text-secondary)' }}>
                          Last seen: {formatTime(pc.last_seen_at)}
                        </span>
                      </div>

                      {canManage && (
                        <div className="flex items-center gap-1.5">
                          {pc.status !== 'RESOLVED' && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => handleStatusChange(pc, 'RESOLVED')}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded border cursor-pointer disabled:opacity-40"
                              style={{ borderColor: '#10b981', color: '#6ee7b7' }}
                            >
                              <CheckCircle2 size={12} /> Resolve
                            </button>
                          )}
                          {pc.status !== 'IGNORED' && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => handleStatusChange(pc, 'IGNORED')}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded border cursor-pointer disabled:opacity-40"
                              style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}
                            >
                              <EyeOff size={12} /> Ignore
                            </button>
                          )}
                          {pc.status !== 'OPEN' && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => handleStatusChange(pc, 'OPEN')}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded border cursor-pointer disabled:opacity-40"
                              style={{ borderColor: '#f59e0b', color: '#fcd34d' }}
                            >
                              <RotateCcw size={12} /> Reopen
                            </button>
                          )}
                        </div>
                      )}
                    </div>

                    <div className="text-sm font-medium mb-1">{pc.summary}</div>

                    {pc.latest_observed_error && (
                      <div className="mt-1 p-2 rounded font-mono text-[11px] whitespace-pre-wrap"
                        style={{ background: 'var(--color-bg-secondary)', color: '#fca5a5' }}>
                        {pc.latest_observed_error}
                      </div>
                    )}

                    {pc.suggested_fix && (
                      <div className="mt-1.5" style={{ color: 'var(--color-text-secondary)' }}>
                        <span className="font-medium">Suggested fix: </span>
                        {pc.suggested_fix}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function MetricCard({ label, value, testId, highlight = false }: {
  label: string;
  value: string;
  testId: string;
  highlight?: boolean;
}) {
  return (
    <div
      data-testid={testId}
      className="p-2.5 rounded-lg border"
      style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)' }}
    >
      <div className="text-[11px] mb-0.5" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-sm font-semibold truncate" title={value} style={highlight ? { color: '#fbbf24' } : undefined}>
        {value}
      </div>
    </div>
  );
}
