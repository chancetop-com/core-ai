import { useCallback, useEffect, useRef, useState } from 'react';
import { CircleAlert, ExternalLink, Save } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api, type RuntimeLimits } from '../../api/client';

/**
 * Runtime limits (configuration only). The watermarks themselves live on the dashboard: this page
 * decides what the guards enforce, the dashboard shows what actually happened over time.
 */
const NUMBER_FIELDS: { key: keyof RuntimeLimits; label: string; hint: string }[] = [
  { key: 'heap_watermark_percent', label: 'Heap watermark %', hint: 'reject when heap used reaches this share of the JVM max' },
  { key: 'max_in_flight_turns_per_pod', label: 'In-flight turns / pod', hint: 'turns executing on one pod' },
  { key: 'max_sessions_per_pod', label: 'Sessions / pod', hint: 'observed only — reported on the dashboard, not refused yet' },
  { key: 'max_sessions_per_user', label: 'Sessions / user', hint: 'live sessions per user (Redis counted, across replicas)' },
  { key: 'max_concurrent_turns_per_user', label: 'Turns / user', hint: 'a new chat message is refused above this' },
  { key: 'max_queued_messages_per_session', label: 'Queued messages / session', hint: 'messages waiting behind the running turn' },
  { key: 'turn_timeout_seconds', label: 'Turn timeout (s)', hint: 'wall clock per turn, 0 = no deadline' },
  { key: 'run_queue_capacity', label: 'Run queue capacity', hint: 'accepted runs waiting for a pool thread' },
  { key: 'max_sandboxes_per_pod', label: 'Sandboxes / pod', hint: 'created sandboxes held by one pod' },
  { key: 'max_sandboxes_per_user', label: 'Sandboxes / user', hint: '0 = unlimited' },
  { key: 'max_sandbox_memory_mb_per_user', label: 'Sandbox memory / user (Mi)', hint: 'sum of requested sandbox memory' },
  { key: 'sse_max_queue_depth', label: 'SSE queue depth', hint: 'events buffered for a client that stopped reading before the channel closes' },
  { key: 'sse_max_queue_bytes', label: 'SSE queue bytes', hint: 'same bound in bytes (default 8 MiB)' },
  { key: 'span_ingest_queue_capacity', label: 'Span ingest queue', hint: 'applied on restart — the queue is fixed when the pool is built' },
];

export default function RuntimePanel() {
  const [limits, setLimits] = useState<RuntimeLimits | null>(null);
  const [draft, setDraft] = useState<RuntimeLimits>({});
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
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadLimits();
  }, [loadLimits]);

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
    <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="p-5 border-b flex items-start justify-between gap-4" style={{ borderColor: 'var(--color-border)' }}>
        <div>
          <h2 className="font-semibold">Entry admission</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            While enforcement is off the guards only count and log what they would refuse — turn it on once the
            dashboard shows real headroom.
          </p>
          <Link to="/settings" className="inline-flex items-center gap-1 text-xs mt-2" style={{ color: 'var(--color-primary)' }}>
            <ExternalLink size={12} /> Runtime watermarks are on the Dashboard
          </Link>
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
  );
}
