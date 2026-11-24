import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Pin, PinOff, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportDataView, ReportView } from '../../api/client';
import { usePermission } from '../../api/permissions';
import ReportRenderer from './ReportRenderer';

const RANGES = [
  { label: '7 days', days: 7 },
  { label: '30 days', days: 30 },
  { label: '90 days', days: 90 },
];

interface SpecParameter {
  name: string;
  type?: string;
  multiple?: boolean;
  default?: unknown;
}

interface SpecView {
  parameters?: SpecParameter[];
}

function parseSpec(specJson?: string | null): SpecView {
  try {
    return specJson ? JSON.parse(specJson) as SpecView : {};
  } catch {
    return {};
  }
}

function formatDefault(parameter: SpecParameter): string {
  const value = parameter.default;
  if (value === undefined || value === null) return '';
  if (Array.isArray(value)) return value.map(item => String(item)).join(', ');
  return String(value);
}

function buildBindings(spec: SpecView, inputs: Record<string, string>): Record<string, unknown> {
  const bindings: Record<string, unknown> = {};
  for (const parameter of spec.parameters ?? []) {
    if (parameter.type === 'DATE_RANGE') continue;
    const raw = inputs[parameter.name];
    if (raw === undefined) continue;
    if (parameter.multiple) {
      bindings[parameter.name] = raw.split(',').map(value => value.trim()).filter(Boolean);
    } else if (parameter.type === 'NUMBER') {
      const number = Number(raw);
      if (Number.isFinite(number)) bindings[parameter.name] = number;
    } else {
      bindings[parameter.name] = raw;
    }
  }
  return bindings;
}

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const canManage = usePermission('report.manage');
  const [report, setReport] = useState<ReportView | null>(null);
  const [data, setData] = useState<ReportDataView | null>(null);
  const [days, setDays] = useState(30);
  const [loading, setLoading] = useState(true);
  const [dataLoading, setDataLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const specRef = useRef<SpecView>({});
  const parameterInputs = useRef<Record<string, string>>({});
  const loadedReportId = useRef<string | null>(null);

  const reportId = id ?? '';
  const parameterList = useMemo(() => specRef.current.parameters ?? [], [report?.spec_json]);

  const loadLayout = useCallback(async () => {
    return api.reports.pins.layout();
  }, []);

  const loadData = useCallback(async (refresh = false) => {
    if (!reportId) return;
    setDataLoading(true);
    try {
      const bindings = buildBindings(specRef.current, parameterInputs.current);
      const parameters_json = Object.keys(bindings).length ? JSON.stringify(bindings) : undefined;
      setData(await api.reports.data(reportId, {
        parameters_json,
        range_kind: 'LAST_N_COMPLETE_DAYS',
        days,
        refresh,
      }));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load report data');
    } finally {
      setDataLoading(false);
    }
  }, [days, reportId]);

  const load = useCallback(async () => {
    if (!reportId) return;
    setLoading(true);
    setError(null);
    try {
      const [view] = await Promise.all([api.reports.get(reportId), loadLayout()]);
      setReport(view);
      const spec = parseSpec(view.spec_json);
      specRef.current = spec;
      if (loadedReportId.current !== view.id) {
        const inputs: Record<string, string> = {};
        for (const parameter of spec.parameters ?? []) {
          if (parameter.type !== 'DATE_RANGE') inputs[parameter.name] = formatDefault(parameter);
        }
        parameterInputs.current = inputs;
        loadedReportId.current = view.id;
      }
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load report');
    } finally {
      setLoading(false);
    }
  }, [loadData, loadLayout, reportId]);

  useEffect(() => { load(); }, [load]);

  const pin = async () => {
    if (!report) return;
    try {
      const layout = await api.reports.pins.layout();
      await api.reports.pins.pin({
        report_id: report.id,
        width: 'HALF',
        expected_layout_revision: layout.layout_revision ?? 0,
      });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to pin report');
    }
  };

  const unpin = async () => {
    if (!report?.pin_id) return;
    try {
      const layout = await api.reports.pins.layout();
      await api.reports.pins.unpin(report.pin_id, layout.layout_revision ?? 0);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to unpin report');
    }
  };

  const remove = async () => {
    if (!report || !window.confirm('Delete this report? Pinned cards will be removed too.')) return;
    try {
      const layout = await api.reports.pins.layout();
      await api.reports.delete(report.id, report.revision, report.pin_id ? layout.layout_revision : undefined);
      navigate('/datasets/reports');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete report');
    }
  };

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <button onClick={() => navigate('/datasets/reports')}
        className="inline-flex items-center gap-1.5 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        <ArrowLeft size={15} /> All reports
      </button>

      {loading && (
        <div className="rounded-xl border p-8 animate-pulse" style={{ borderColor: 'var(--color-border)' }}>
          <div className="h-5 w-48 rounded mb-3" style={{ background: 'var(--color-bg-tertiary)' }} />
          <div className="h-3 w-72 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
        </div>
      )}

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          {error}
        </div>
      )}

      {report && !loading && (
        <>
          <div className="flex items-start justify-between gap-4 mb-5">
            <div className="min-w-0">
              <h1 className="text-2xl font-semibold truncate">{report.title}</h1>
              <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                {report.dataset_name || report.dataset_id} · {report.display_type || 'TABLE'} · revision {report.revision}
                {data?.source_time ? ` · source ${new Date(data.source_time).toLocaleString()}` : ''}
              </div>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              {canManage && (report.pin_id ? (
                <button onClick={unpin}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                  <PinOff size={14} /> Unpin
                </button>
              ) : (
                <button onClick={pin}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                  <Pin size={14} /> Pin to home
                </button>
              ))}
              <button onClick={() => navigate('/chat')}
                className="px-3 py-2 rounded-lg text-sm cursor-pointer"
                style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
                Modify in chat
              </button>
              {canManage && (
                <button onClick={remove}
                  className="w-9 h-9 rounded-lg flex items-center justify-center cursor-pointer"
                  style={{ color: '#dc2626', background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
                  <Trash2 size={15} />
                </button>
              )}
            </div>
          </div>

          <div className="flex flex-wrap items-end gap-3 mb-4">
            {RANGES.map(range => (
              <button key={range.days} onClick={() => setDays(range.days)}
                className="px-3 py-1.5 rounded-full text-xs cursor-pointer"
                style={{
                  background: days === range.days ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                  color: days === range.days ? '#fff' : 'var(--color-text)',
                  border: '1px solid var(--color-border)',
                }}>
                Last {range.label}
              </button>
            ))}
            {parameterList.filter(parameter => parameter.type !== 'DATE_RANGE').map(parameter => (
              <label key={parameter.name} className="flex flex-col gap-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                <span>{parameter.name}{parameter.multiple ? ' (comma separated)' : ''}</span>
                <input
                  defaultValue={parameterInputs.current[parameter.name] ?? ''}
                  onChange={event => { parameterInputs.current[parameter.name] = event.target.value; }}
                  className="px-3 py-1.5 rounded-lg text-sm"
                  style={{ border: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
                />
              </label>
            ))}
            {parameterList.some(parameter => parameter.type !== 'DATE_RANGE') && (
              <button onClick={() => loadData()} disabled={dataLoading}
                className="px-3 py-1.5 rounded-full text-xs cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)', color: '#fff', border: '1px solid var(--color-primary)' }}>
                Apply
              </button>
            )}
            <button onClick={() => loadData(true)} disabled={dataLoading}
              className="ml-auto inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
              <RefreshCw size={13} /> Refresh
            </button>
          </div>

          {dataLoading ? (
            <div className="rounded-xl border p-4 h-64 flex items-center justify-center text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              Loading…
            </div>
          ) : (
            <div className="grid gap-4">
              {(data?.components?.length
                ? data.components
                : data ? [{ id: 'main', title: null, display_type: report.display_type, ...data }] : []
              ).map(component => (
                <div key={component.id} className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
                  {component.title && <div className="text-sm font-medium mb-2">{component.title}</div>}
                  <ReportRenderer data={component} displayType={component.display_type} />
                </div>
              ))}
            </div>
          )}

          {data && (
            <div className="flex flex-wrap gap-3 mt-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <span>{data.status}</span>
              <span>{data.matched_record_count ?? 0} matched records</span>
              <span>{data.timezone}</span>
              <span>{data.freshness_status}</span>
              {data.invalid_value_count ? <span style={{ color: '#d97706' }}>{data.invalid_value_count} invalid values</span> : null}
            </div>
          )}
        </>
      )}
    </div>
  );
}
