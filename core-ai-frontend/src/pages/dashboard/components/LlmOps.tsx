import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api, type LlmMetrics, type LlmModelMetrics } from '../../../api/client';
import { formatCompact } from '../../traces/traceViewModel';

/**
 * LLM operations view: request/token throughput per minute and per-model latency metrics.
 * The numbers come from the raw LLM spans (trace retention window), not the billing rollups, so this
 * is live operational health rather than history.
 */
const RANGES = [
  { minutes: 60, bucket: 1, label: '1h' },
  { minutes: 360, bucket: 5, label: '6h' },
  { minutes: 1440, bucket: 15, label: '24h' },
  { minutes: 10080, bucket: 60, label: '7d' },
];

const REFRESH_MS = 60000;
const EMPTY_POINTS: LlmMetrics['points'] = [];
const EMPTY_MODELS: LlmMetrics['models'] = [];

export default function LlmOps() {
  const [range, setRange] = useState(RANGES[1]);
  const [metrics, setMetrics] = useState<LlmMetrics | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  const load = useCallback(async () => {
    try {
      const result = await api.adminAnalytics.llmMetrics({ minutes: range.minutes, bucketMinutes: range.bucket });
      setMetrics(result);
      setUpdatedAt(new Date());
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load LLM metrics');
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  const bucket = metrics?.bucketMinutes ?? range.bucket;
  const points = metrics?.points ?? EMPTY_POINTS;
  const models = metrics?.models ?? EMPTY_MODELS;

  const chartData = useMemo(
    () => points.map(point => ({
      label: formatTime(point.bucketStart),
      rpm: round(point.calls / bucket),
      errors: round(point.errors / bucket),
      inputTpm: round(point.inputTokens / bucket),
      outputTpm: round(point.outputTokens / bucket),
    })),
    [points, bucket],
  );

  const totals = useMemo(() => {
    let calls = 0;
    let errors = 0;
    let inputTokens = 0;
    let outputTokens = 0;
    let ttftWeighted = 0;
    let ttftCalls = 0;
    for (const model of models) {
      calls += model.calls;
      errors += model.errors;
      inputTokens += model.inputTokens;
      outputTokens += model.outputTokens;
      if (model.avgTtftMs != null && model.callsWithTtft > 0) {
        ttftWeighted += model.avgTtftMs * model.callsWithTtft;
        ttftCalls += model.callsWithTtft;
      }
    }
    return {
      calls,
      errors,
      inputTokens,
      outputTokens,
      avgTtftMs: ttftCalls > 0 ? ttftWeighted / ttftCalls : null,
      ttftCalls,
    };
  }, [models]);

  const windowMinutes = metrics?.windowMinutes ?? range.minutes;

  if (loading) {
    return <p className="text-sm mt-6" style={{ color: 'var(--color-text-secondary)' }}>Loading LLM metrics…</p>;
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
            bucket {bucket}m · auto refresh 60s {updatedAt ? `· ${formatTime(updatedAt.toISOString())}` : ''}
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

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Requests" value={formatCompact(totals.calls)}
          hint={`${formatRate(totals.calls / windowMinutes)} req/min avg`} />
        <StatCard label="Tokens" value={formatCompact(totals.inputTokens + totals.outputTokens)}
          hint={`${formatRate((totals.inputTokens + totals.outputTokens) / windowMinutes)} tok/min avg`} />
        <StatCard label="Error rate" value={totals.calls > 0 ? `${((totals.errors / totals.calls) * 100).toFixed(1)}%` : '—'}
          hint={`${formatCompact(totals.errors)} failed calls`} />
        <StatCard label="Avg TTFT" value={totals.avgTtftMs != null ? formatMs(totals.avgTtftMs) : '—'}
          hint={totals.ttftCalls > 0 ? `${formatCompact(totals.ttftCalls)} measured calls` : 'not measured in this window yet'} />
      </section>

      {points.length === 0 ? (
        <p className="text-sm mt-6" style={{ color: 'var(--color-text-secondary)' }}>
          No LLM calls recorded in this window.
        </p>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          <ChartCard title="Requests per minute" hint="LLM calls per minute, with failed calls split out">
            <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
              <XAxis dataKey="label" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} tickFormatter={formatAxis} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="rpm" name="requests/min" stroke="#3b82f6" dot={false} />
              <Line type="monotone" dataKey="errors" name="errors/min" stroke="#dc2626" dot={false} />
            </LineChart>
          </ChartCard>

          <ChartCard title="Tokens per minute" hint="prompt and completion tokens per minute">
            <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
              <XAxis dataKey="label" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} tickFormatter={formatAxis} />
              <Tooltip />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="inputTpm" name="input tok/min" stroke="#8b5cf6" dot={false} />
              <Line type="monotone" dataKey="outputTpm" name="output tok/min" stroke="#22c55e" dot={false} />
            </LineChart>
          </ChartCard>
        </div>
      )}

      <section className="rounded-xl border mt-6 overflow-hidden" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h3 className="font-semibold text-sm">Per-model metrics</h3>
          <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
            TTFT is measured from the provider request to the first content/reasoning token; Out tok/s is the median answer
            speed (reasoning tokens and thinking time excluded, answer windows under 200 ms ignored). Hover a value for the
            weighted average and p95.
          </p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                <Th label="Model" align="left" />
                <Th label="Calls" />
                <Th label="Errors" />
                <Th label="TTFT avg" />
                <Th label="TTFT p95" />
                <Th label="Latency p50" />
                <Th label="Latency p95" />
                <Th label="Out tok/s" />
                <Th label="In / Out tokens" />
                <Th label="Cost" />
              </tr>
            </thead>
            <tbody>
              {models.map(model => (
                <tr key={model.model} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <Td align="left">
                    <span className="font-medium" title={model.model}>{model.model}</span>
                  </Td>
                  <Td>{formatCompact(model.calls)}</Td>
                  <Td>{model.errors > 0
                    ? <span style={{ color: 'var(--color-danger, #dc2626)' }}>{formatCompact(model.errors)} ({((model.errors / model.calls) * 100).toFixed(1)}%)</span>
                    : '0'}</Td>
                  <Td>{formatModelTtft(model, model.avgTtftMs)}</Td>
                  <Td>{formatModelTtft(model, model.p95TtftMs)}</Td>
                  <Td>{formatMsOrDash(model.p50DurationMs)}</Td>
                  <Td>{formatMsOrDash(model.p95DurationMs)}</Td>
                  <Td><span title={outputSpeedTitle(model)}>{formatTps(model.p50OutputTokensPerSecond)}</span></Td>
                  <Td>{formatCompact(model.inputTokens)} / {formatCompact(model.outputTokens)}</Td>
                  <Td>${model.costUsd.toFixed(2)}</Td>
                </tr>
              ))}
              {models.length === 0 && (
                <tr>
                  <td colSpan={10} className="px-4 py-6 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                    No LLM calls recorded in this window.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

function StatCard({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-lg border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-2xl font-bold mt-2">{value}</div>
      <div className="text-xs mt-1" style={{ color: 'var(--color-text-tertiary)' }}>{hint}</div>
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

function Th({ label, align = 'right' }: { label: string; align?: 'left' | 'right' }) {
  return (
    <th className="px-3 py-2 font-medium" style={{ textAlign: align, whiteSpace: 'nowrap' }}>{label}</th>
  );
}

function Td({ children, align = 'right' }: { children: React.ReactNode; align?: 'left' | 'right' }) {
  return (
    <td className="px-3 py-2 whitespace-nowrap" style={{ textAlign: align }}>{children}</td>
  );
}

// a model that recorded no TTFT at all (older traffic) shows "—" instead of a lone dash per bucket
function formatModelTtft(model: LlmModelMetrics, value: number | null) {
  if (model.callsWithTtft === 0) return '—';
  return formatMsOrDash(value);
}

function formatMsOrDash(value: number | null) {
  return value != null ? formatMs(value) : '—';
}

function formatTps(value: number | null) {
  return value != null ? value.toFixed(1) : '—';
}

function outputSpeedTitle(model: LlmModelMetrics) {
  if (model.p50OutputTokensPerSecond == null) {
    return 'no call in this window streamed an answer for at least 200 ms';
  }
  return `answer tokens/s · p50 ${formatTps(model.p50OutputTokensPerSecond)}`
    + ` · weighted avg ${formatTps(model.avgOutputTokensPerSecond)}`
    + ` · p95 ${formatTps(model.p95OutputTokensPerSecond)}`;
}

function formatMs(value: number) {
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

function formatRate(value: number) {
  if (!Number.isFinite(value)) return '0';
  if (value >= 100) return formatCompact(Math.round(value));
  return value.toFixed(1);
}

function formatAxis(value: number) {
  return formatCompact(Math.round(value));
}

function round(value: number) {
  return Math.round(value * 10) / 10;
}

function formatTime(iso?: string) {
  if (!iso) return '';
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
