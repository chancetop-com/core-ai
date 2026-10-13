import { useCallback, useEffect, useRef, useState } from 'react';
import { CircleAlert, RefreshCw, Save } from 'lucide-react';
import { api, type RuntimeLimits, type RuntimeStats } from '../../api/client';

/**
 * Runtime governance: the limits the pods enforce (entry admission, session budgets, run queue,
 * sandbox budgets) plus the live per-pod snapshot they are calibrated against. Everything is read
 * live from the server, so a save here takes effect on every replica without a restart.
 */
const NUMBER_FIELDS: { key: keyof RuntimeLimits; label: string; hint: string }[] = [
  { key: 'heap_watermark_percent', label: 'Heap watermark %', hint: 'reject when heap used reaches this share of the JVM max' },
  { key: 'max_in_flight_turns_per_pod', label: 'In-flight turns / pod', hint: 'turns executing on one pod' },
  { key: 'max_sessions_per_pod', label: 'Sessions / pod', hint: 'observed only — reported in the snapshot, not refused yet' },
  { key: 'max_sessions_per_user', label: 'Sessions / user', hint: 'not enforced yet' },
  { key: 'max_concurrent_turns_per_user', label: 'Turns / user', hint: 'not enforced yet' },
  { key: 'max_queued_messages_per_session', label: 'Queued messages / session', hint: 'messages waiting behind the running turn' },
  { key: 'turn_timeout_seconds', label: 'Turn timeout (s)', hint: 'wall clock per turn, 0 = no deadline' },
  { key: 'run_queue_capacity', label: 'Run queue capacity', hint: 'accepted runs waiting for a pool thread' },
  { key: 'max_sandboxes_per_pod', label: 'Sandboxes / pod', hint: 'created sandboxes held by one pod' },
  { key: 'max_sandboxes_per_user', label: 'Sandboxes / user', hint: '0 = unlimited' },
  { key: 'max_sandbox_memory_mb_per_user', label: 'Sandbox memory / user (Mi)', hint: 'sum of requested sandbox memory' },
];

export default function RuntimePanel() {
  const [limits, setLimits] = useState<RuntimeLimits | null>(null);
  const [draft, setDraft] = useState<RuntimeLimits>({});
  const [stats, setStats] = useState<RuntimeStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');
  const savedTimer = useRef<number | null>(null);

  const loadLimits = useCallback(async () => {
    try {
      const result = await api.runtime.limits();
      setLimits(result);
      setDraft(result);
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load runtime limits');
    }
  }, []);

  const loadStats = useCallback(async () => {
    try {
      setStats(await api.runtime.stats());
    } catch {
      // stats are advisory: a stale panel must not look like a broken settings page
    }
  }, []);

  useEffect(() => {
    void (async () => {
      await loadLimits();
      await loadStats();
      setLoading(false);
    })();
    const timer = window.setInterval(() => void loadStats(), 10000);
    return () => window.clearInterval(timer);
  }, [loadLimits, loadStats]);

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const result = await api.runtime.updateLimits(draft);
      setLimits(result);
      setDraft(result);
      setSaved(true);
      if (savedTimer.current) window.clearTimeout(savedTimer.current);
      savedTimer.current = window.setTimeout(() => setSaved(false), 2500);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to save runtime limits');
    } finally {
      setSaving(false);
    }
  };

  const numberValue = (key: keyof RuntimeLimits) => {
    const value = draft[key];
    return value === undefined || value === null ? '' : String(value);
  };

  const setNumber = (key: keyof RuntimeLimits, raw: string) => {
    setDraft(previous => ({ ...previous, [key]: raw === '' ? undefined : Number(raw) }));
  };

  if (loading) {
    return <p className="text-sm mt-6" style={{ color: 'var(--color-text-secondary)' }}>Loading runtime limits…</p>;
  }

  return (
    <>
      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b flex items-start justify-between gap-4" style={{ borderColor: 'var(--color-border)' }}>
          <div>
            <h2 className="font-semibold">Entry admission</h2>
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              While enforcement is off the guards only count and log what they would refuse — turn it on once the
              counters below show real headroom.
            </p>
          </div>
          <label className="flex items-center gap-2 text-sm whitespace-nowrap">
            <input
              type="checkbox"
              checked={draft.admission_enabled === true}
              onChange={event => setDraft(previous => ({ ...previous, admission_enabled: event.target.checked }))}
            />
            Enforce limits
          </label>
        </div>
        <div className="p-5 space-y-4">
          {error && (
            <div className="flex items-center gap-2 text-sm px-3 py-2 rounded-lg"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger, #dc2626)' }}>
              <CircleAlert size={14} /> {error}
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {NUMBER_FIELDS.map(field => (
              <div key={String(field.key)}>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>{field.label}</label>
                <input
                  type="number"
                  min={0}
                  value={numberValue(field.key)}
                  onChange={event => setNumber(field.key, event.target.value)}
                  className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text)' }}
                />
                <p className="text-[10px] mt-0.5" style={{ color: 'var(--color-text-tertiary)' }}>{field.hint}</p>
              </div>
            ))}
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => void save()}
              disabled={saving}
              className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium"
              style={{ background: saved ? '#16a34a' : 'var(--color-primary)', color: '#fff', opacity: saving ? 0.7 : 1 }}
            >
              <Save size={14} /> {saved ? 'Saved' : saving ? 'Saving…' : 'Save limits'}
            </button>
            {limits?.updated_by && (
              <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
                last changed by {limits.updated_by}
              </span>
            )}
          </div>
        </div>
      </section>

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b flex items-start justify-between gap-4" style={{ borderColor: 'var(--color-border)' }}>
          <div>
            <h2 className="font-semibold">Live snapshot (this pod)</h2>
            <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              What one replica is holding right now against the limits above. Saturated counts are the requests that
              would have been refused with enforcement on.
            </p>
          </div>
          <button
            onClick={() => void loadStats()}
            className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm whitespace-nowrap"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
          >
            <RefreshCw size={14} /> Refresh
          </button>
        </div>
        <div className="p-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3 text-sm">
          <Stat label="Pod" value={stats?.pod ?? '—'} sub={`uptime ${formatSeconds(stats?.uptime_seconds)}`} />
          <Stat label="Heap" value={`${stats?.memory?.heap_used_percent ?? '—'}%`}
            sub={`${formatMb(stats?.memory?.heap_used)} / ${formatMb(stats?.memory?.heap_max)}`} />
          <Stat label="Sessions" value={counter(stats?.sessions?.active, stats?.sessions?.max)} sub="active / limit" />
          <Stat label="In-flight turns" value={counter(stats?.turns?.in_flight, stats?.turns?.max)}
            sub={`saturated ${stats?.turns?.saturated_total ?? 0}`} />
          <Stat label="Runs" value={counter(stats?.runs?.active, stats?.runs?.max_queue)}
            sub={`queued ${stats?.runs?.queued ?? 0} · rejected ${stats?.runs?.rejected_total ?? 0}`} />
          <Stat label="Sandboxes" value={counter(stats?.sandbox?.live, stats?.sandbox?.max)}
            sub={fleetSummary(stats)} />
          <Stat label="Span ingest" value={counter(stats?.span_ingest?.queue_depth, stats?.span_ingest?.capacity)}
            sub={`dropped ${stats?.span_ingest?.dropped_total ?? 0}`} />
        </div>
      </section>
    </>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
      <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-lg font-medium mt-0.5">{value}</div>
      {sub && <div className="text-[10px] mt-0.5" style={{ color: 'var(--color-text-tertiary)' }}>{sub}</div>}
    </div>
  );
}

function counter(value?: number, max?: number) {
  return `${value ?? '—'} / ${max ?? '—'}`;
}

function formatSeconds(value?: number) {
  if (value === undefined || value === null) return '—';
  if (value < 3600) return `${Math.round(value / 60)}m`;
  return `${(value / 3600).toFixed(1)}h`;
}

function formatMb(value?: number) {
  if (value === undefined || value === null) return '—';
  return `${Math.round(value / 1024 / 1024)} MiB`;
}

function fleetSummary(stats?: RuntimeStats | null) {
  const byKind = stats?.sandbox_fleet?.live_by_kind;
  if (!byKind || Object.keys(byKind).length === 0) return 'none live';
  return Object.entries(byKind).map(([kind, count]) => `${kind} ${count}`).join(' · ');
}
