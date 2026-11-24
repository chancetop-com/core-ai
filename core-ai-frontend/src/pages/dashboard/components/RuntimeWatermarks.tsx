import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api, type RuntimePods, type RuntimeSeries } from '../../../api/client';

/**
 * Runtime watermarks for the admin dashboard: one card per replica (every pod reports its own sample)
 * and the trend behind them. Everything here reads the persisted samples — the collector writes, this
 * reads — so the numbers survive the pod that produced them and can be looked at after the fact.
 */
const RANGES = [
  { hours: 1, bucket: 5, label: '1h' },
  { hours: 6, bucket: 15, label: '6h' },
  { hours: 24, bucket: 60, label: '24h' },
  { hours: 168, bucket: 360, label: '7d' },
];

const REFRESH_MS = 60000;

export default function RuntimeWatermarks() {
  const [range, setRange] = useState(RANGES[1]);
  const [pods, setPods] = useState<RuntimePods | null>(null);
  const [series, setSeries] = useState<RuntimeSeries | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const load = useCallback(async () => {
    try {
      const [podsResult, seriesResult] = await Promise.all([
        api.runtime.pods(),
        api.runtime.series(range.hours, range.bucket),
      ]);
      setPods(podsResult);
      setSeries(seriesResult);
      setUpdatedAt(new Date());
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load runtime watermarks');
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const watermark = pods?.limits?.heap_watermark_percent ?? 85;
  const points = series?.points ?? [];
  const chartData = useMemo(
    () => points.map(point => ({ ...point, label: formatTime(point.bucket_start) })),
    [points],
  );

  if (loading) {
    return <p className="text-sm mt-6" style={{ color: 'var(--color-text-secondary)' }}>Loading runtime watermarks…</p>;
  }

  return (
    <>
      {error && (
        <div className="text-sm px-3 py-2 rounded-lg mb-4" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-danger, #dc2626)' }}>
          {error}
        </div>
      )}

      <div className="flex items-center justify-between gap-3 flex-wrap mb-4">
        <div className="flex items-center gap-2">
          {RANGES.map(candidate => (
            <button
              key={candidate.label}
              onClick={() => setRange(candidate)}
              className="px-3 py-1.5 rounded-lg text-sm"
              style={{
                background: range.label === candidate.label ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                color: range.label === candidate.label ? '#fff' : 'var(--color-text)',
              }}
            >
              {candidate.label}
            </button>
          ))}
          <span className="text-xs ml-1" style={{ color: 'var(--color-text-tertiary)' }}>
            bucket {range.bucket}m · auto refresh 60s {updatedAt ? `· ${formatTime(updatedAt.toISOString())}` : ''}
          </span>
        </div>
        <button
          onClick={() => void load()}
          className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
        >
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {(pods?.pods ?? []).map(pod => (
          <PodCard key={pod.pod} pod={pod} watermark={watermark} />
        ))}
        {(pods?.pods ?? []).length === 0 && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No replica reported in the last 10 minutes. Samples are written once a minute.
          </p>
        )}
      </section>

      <ChartCard
        title="Retained heap (max pod)"
        hint={`highest per-pod value in each bucket; a Young GC no longer counts as pressure. Watermark ${watermark}% applies to Old Gen / after-GC.`}
      >
        <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} />
          <YAxis unit="%" tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <ReferenceLine y={watermark} stroke="var(--color-danger, #dc2626)" strokeDasharray="4 4" label={{ value: 'watermark', fontSize: 10 }} />
          <Line type="monotone" dataKey="old_gen_peak_percent" name="old gen % (max pod)" stroke="#3b82f6" dot={false} />
          <Line type="monotone" dataKey="heap_after_gc_peak_percent" name="after GC % (max pod)" stroke="#a855f7" dot={false} />
          <Line type="monotone" dataKey="container_peak_percent" name="container % (max pod)" stroke="#f59e0b" dot={false} />
          <Line type="monotone" dataKey="heap_peak_percent" name="heap used % (instant, max pod)" stroke="#ef4444" strokeDasharray="3 3" dot={false} />
        </LineChart>
      </ChartCard>

      <ChartCard
        title="Work (max pod vs fleet sum)"
        hint="max pod: the worst single replica; fleet sum: per-minute values summed across replicas, then the bucket maximum"
      >
        <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line type="monotone" dataKey="in_flight_turns_peak" name="turns (max pod)" stroke="#3b82f6" dot={false} />
          <Line type="monotone" dataKey="in_flight_turns_sum_peak" name="turns (fleet sum)" stroke="#3b82f6" strokeDasharray="4 3" dot={false} />
          <Line type="monotone" dataKey="sessions_peak" name="sessions (max pod)" stroke="#8b5cf6" dot={false} />
          <Line type="monotone" dataKey="sessions_sum_peak" name="sessions (fleet sum)" stroke="#8b5cf6" strokeDasharray="4 3" dot={false} />
          <Line type="monotone" dataKey="runs_queued_peak" name="runs queued (max pod)" stroke="#f59e0b" dot={false} />
          <Line type="monotone" dataKey="sandboxes_peak" name="sandboxes (max pod)" stroke="#14b8a6" dot={false} />
        </LineChart>
      </ChartCard>

      <ChartCard title="Refusals" hint="increments inside each bucket: saturated = would have been refused while enforcement is off">
        <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line type="monotone" dataKey="turns_saturated_delta" name="turns saturated" stroke="#f97316" dot={false} />
          <Line type="monotone" dataKey="turns_rejected_delta" name="turns rejected" stroke="#dc2626" dot={false} />
          <Line type="monotone" dataKey="runs_rejected_delta" name="runs rejected" stroke="#7c3aed" dot={false} />
          <Line type="monotone" dataKey="sandboxes_rejected_delta" name="sandboxes refused" stroke="#0ea5e9" dot={false} />
          <Line type="monotone" dataKey="span_ingest_dropped_delta" name="spans dropped" stroke="#64748b" dot={false} />
        </LineChart>
      </ChartCard>
    </>
  );
}

function PodCard({ pod, watermark }: { pod: NonNullable<RuntimePods['pods']>[number]; watermark: number }) {
  const heap = pod.heap_used_percent ?? 0;
  const stale = (pod.age_seconds ?? 0) > 180;
  return (
    <div className="rounded-lg border p-3 text-sm" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium truncate" title={pod.pod}>{pod.pod}</span>
        <span className="text-[10px]" style={{ color: stale ? 'var(--color-danger, #dc2626)' : 'var(--color-text-tertiary)' }}>
          {stale ? `stale ${formatAge(pod.age_seconds)}` : `sampled ${formatAge(pod.age_seconds)} ago`}
        </span>
      </div>
      <div className="mt-2 space-y-1">
        <Row label="Heap used (instant)" value={`${heap}% of ${formatMb(pod.heap_max)}`} warn={heap >= 95} />
        <Row
          label="Old Gen / after GC"
          value={`${formatPercent(pod.old_gen_percent)} / ${formatPercent(pod.heap_after_gc_percent)}`}
          warn={(pod.old_gen_percent ?? 0) >= watermark}
        />
        <Row label="Container" value={formatPercent(pod.container_memory_percent)} warn={(pod.container_memory_percent ?? 0) >= 90} />
        <Row label="GC" value={pod.gc_kind ? `${pod.gc_kind}${pod.gc_reclaimed_bytes ? ` · ${formatMb(pod.gc_reclaimed_bytes)} reclaimed` : ''}` : '—'} />
        <Row label="Sessions" value={String(pod.sessions_active ?? 0)} />
        <Row
          label="Session est / peak"
          value={`${formatMb(pod.sessions_estimated_bytes_total)} / ${formatMb(pod.sessions_estimated_bytes_peak_max)}`}
        />
        <Row
          label="Inline img / refs"
          value={`${formatMb(pod.sessions_inline_image_bytes_total)} / ${formatMb(pod.sessions_media_ref_bytes_total)}`}
        />
        <Row label="In-flight turns" value={String(pod.in_flight_turns ?? 0)} />
        <Row label="Runs" value={`${pod.runs_active ?? 0} active / ${pod.runs_queued ?? 0} queued`} />
        <Row label="Sandboxes" value={`${pod.sandboxes_live ?? 0} live (claim ${pod.claims_live ?? 0} / cr ${pod.direct_crs_live ?? 0}, failed ${pod.creates_failed_total ?? 0})`} />
        <Row label="Span ingest" value={`${pod.span_ingest_depth ?? 0} queued / ${pod.span_ingest_dropped_total ?? 0} dropped`} />
        <Row label="Saturated / rejected" value={`${pod.turns_saturated_total ?? 0} / ${pod.turns_rejected_total ?? 0} turns · ${pod.runs_rejected_total ?? 0} runs · ${pod.sandboxes_rejected_total ?? 0} sandboxes`} />
      </div>
    </div>
  );
}

function Row({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <span className="text-xs text-right" style={{ color: warn ? 'var(--color-danger, #dc2626)' : 'var(--color-text)' }}>{value}</span>
    </div>
  );
}

function ChartCard({ title, hint, children }: { title: string; hint: string; children: React.ReactElement }) {
  return (
    <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <h3 className="font-semibold text-sm">{title}</h3>
        <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{hint}</p>
      </div>
      <div className="p-4" style={{ height: 240 }}>
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </div>
    </section>
  );
}

function formatMb(bytes?: number) {
  if (bytes === undefined || bytes === null) return '—';
  return `${Math.round(bytes / 1024 / 1024)} MiB`;
}

function formatAge(seconds?: number) {
  if (seconds === undefined || seconds === null) return '—';
  if (seconds < 90) return `${seconds}s`;
  return `${Math.round(seconds / 60)}m`;
}

function formatPercent(value?: number) {
  if (value === undefined || value === null) return '—';
  return `${value}%`;
}

function formatTime(iso?: string) {
  if (!iso) return '';
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
