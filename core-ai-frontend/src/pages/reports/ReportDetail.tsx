import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Pin, PinOff, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportDataView, ReportView } from '../../api/client';
import { usePermission } from '../../api/permissions';
import ReportRenderer, { type ReportDisplay } from './ReportRenderer';

interface SpecParameter {
  name: string;
  type?: string;
  multiple?: boolean;
  default?: unknown;
  options_field?: string | null;
}

interface SpecSeries {
  field: string;
  label?: string | null;
  format?: string | null;
}

interface SpecDisplay extends ReportDisplay {
  series?: SpecSeries[] | null;
}

interface SpecComponent {
  id: string;
  title?: string | null;
  display?: SpecDisplay | null;
}

interface SpecView {
  parameters?: SpecParameter[];
  components?: SpecComponent[];
}

interface RangeState {
  kind: string;
  relativeKind: string;
  days: number;
  from: string;
  to: string;
}

const NONE_VALUE = '__none__';

function parseSpec(specJson?: string | null): SpecView {
  try {
    return specJson ? JSON.parse(specJson) as SpecView : {};
  } catch {
    return {};
  }
}

function parseOptions(json?: string | null): Record<string, string[]> {
  try {
    const parsed = json ? JSON.parse(json) as Record<string, unknown> : {};
    const result: Record<string, string[]> = {};
    for (const [key, value] of Object.entries(parsed)) {
      if (Array.isArray(value)) result[key] = value.map(item => String(item));
    }
    return result;
  } catch {
    return {};
  }
}

function shiftDays(iso: string, days: number): string {
  if (!iso) return '';
  const date = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return iso.slice(0, 10);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function initialRange(spec: SpecView): RangeState {
  const parameter = spec.parameters?.find(item => item.type === 'DATE_RANGE');
  const value = parameter?.default as { kind?: string; days?: number; from?: string; to?: string } | undefined;
  const kind = value?.kind || (parameter ? 'LAST_N_DAYS_OF_DATA' : 'LAST_N_COMPLETE_DAYS');
  const relativeKind = kind === 'LAST_N_COMPLETE_DAYS' ? 'LAST_N_COMPLETE_DAYS' : 'LAST_N_DAYS_OF_DATA';
  if (kind === 'FIXED') {
    const from = value?.from ? value.from.slice(0, 10) : '';
    const to = value?.to ? shiftDays(value.to.slice(0, 10), -1) : '';
    return { kind, relativeKind, days: 30, from, to };
  }
  return {
    kind,
    relativeKind,
    days: value?.days && value.days > 0 ? value.days : 30,
    from: '',
    to: '',
  };
}

function initialNumberOrString(parameter: SpecParameter): string {
  return parameter.default === undefined || parameter.default === null ? '' : String(parameter.default);
}

function initialMultiple(parameter: SpecParameter): string[] {
  return Array.isArray(parameter.default) ? parameter.default.map(item => String(item)) : [];
}

function rangeBinding(range: RangeState) {
  if (range.kind === 'FIXED') {
    return { kind: range.kind, from: range.from, to: shiftDays(range.to, 1) };
  }
  if (range.kind === 'SOURCE_RANGE') return { kind: range.kind };
  return { kind: range.kind, days: range.days };
}

function buildBindings(spec: SpecView, inputs: Record<string, string | string[]>, range: RangeState): Record<string, unknown> {
  const bindings: Record<string, unknown> = {};
  for (const parameter of spec.parameters ?? []) {
    if (parameter.type === 'DATE_RANGE') {
      if (parameter === spec.parameters?.find(item => item.type === 'DATE_RANGE')) {
        bindings[parameter.name] = rangeBinding(range);
      }
      continue;
    }
    const raw = inputs[parameter.name];
    if (parameter.multiple) {
      bindings[parameter.name] = Array.isArray(raw) ? raw : [];
    } else if (raw !== undefined && raw !== '') {
      if (parameter.type === 'NUMBER') {
        const number = Number(raw);
        if (Number.isFinite(number)) bindings[parameter.name] = number;
      } else {
        bindings[parameter.name] = raw;
      }
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
  const [spec, setSpec] = useState<SpecView>({});
  const [options, setOptions] = useState<Record<string, string[]>>({});
  const [range, setRange] = useState<RangeState>(() => initialRange({}));
  const [inputs, setInputs] = useState<Record<string, string | string[]>>({});
  const [loading, setLoading] = useState(true);
  const [dataLoading, setDataLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rangeRef = useRef<RangeState>(range);
  const inputsRef = useRef<Record<string, string | string[]>>({});
  const specRef = useRef<SpecView>({});
  const loadedReportId = useRef<string | null>(null);

  const reportId = id ?? '';
  const parameterList = spec.parameters ?? [];
  const displayByComponent = useMemo(() => new Map((spec.components ?? []).map(component => [component.id, component.display ?? null])), [spec]);

  const loadLayout = useCallback(async () => {
    return api.reports.pins.layout();
  }, []);

  const loadData = useCallback(async (refresh = false) => {
    if (!reportId) return;
    setDataLoading(true);
    setError(null);
    try {
      const bindings = buildBindings(specRef.current, inputsRef.current, rangeRef.current);
      const parameters_json = Object.keys(bindings).length ? JSON.stringify(bindings) : undefined;
      const hasRangeParameter = (specRef.current.parameters ?? []).some(parameter => parameter.type === 'DATE_RANGE');
      if (hasRangeParameter) {
        setData(await api.reports.data(reportId, {
          parameters_json,
          refresh,
        }));
      } else {
        const legacyDays = rangeRef.current.kind === 'SOURCE_RANGE' ? 30 : rangeRef.current.days;
        setData(await api.reports.data(reportId, {
          parameters_json,
          range_kind: rangeRef.current.kind === 'SOURCE_RANGE' ? 'SOURCE_RANGE' : 'LAST_N_COMPLETE_DAYS',
          days: legacyDays,
          refresh,
        }));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load report data');
    } finally {
      setDataLoading(false);
    }
  }, [reportId]);

  const load = useCallback(async () => {
    if (!reportId) return;
    setLoading(true);
    setError(null);
    try {
      const [view] = await Promise.all([api.reports.get(reportId), loadLayout()]);
      setReport(view);
      const parsed = parseSpec(view.spec_json);
      specRef.current = parsed;
      setSpec(parsed);
      setOptions(parseOptions(view.parameter_options_json));
      if (loadedReportId.current !== view.id) {
        const nextInputs: Record<string, string | string[]> = {};
        for (const parameter of parsed.parameters ?? []) {
          if (parameter.type === 'DATE_RANGE') continue;
          nextInputs[parameter.name] = parameter.multiple ? initialMultiple(parameter) : initialNumberOrString(parameter);
        }
        inputsRef.current = nextInputs;
        setInputs(nextInputs);
        const nextRange = initialRange(parsed);
        rangeRef.current = nextRange;
        setRange(nextRange);
        loadedReportId.current = view.id;
      }
      await loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load report');
    } finally {
      setLoading(false);
    }
  }, [loadData, loadLayout, reportId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (range.kind !== 'FIXED' || range.from || range.to || !data?.source_time) return;
    const maxDate = data.source_time.slice(0, 10);
    const next = { ...range, from: shiftDays(maxDate, -29), to: maxDate };
    rangeRef.current = next;
    setRange(next);
  }, [data?.source_time, range]);

  const updateRange = (next: RangeState, apply = false) => {
    rangeRef.current = next;
    setRange(next);
    if (apply) void loadData();
  };

  const updateInput = (name: string, value: string | string[]) => {
    inputsRef.current = { ...inputsRef.current, [name]: value };
    setInputs(inputsRef.current);
  };

  const toggleOption = (parameter: SpecParameter, option: string) => {
    const all = options[parameter.name] ?? [];
    const values = inputsRef.current[parameter.name];
    const raw = Array.isArray(values) ? values : [];
    const selected = raw.length === 0 ? all : raw.includes(NONE_VALUE) ? [] : raw;
    const next = selected.includes(option) ? selected.filter(item => item !== option) : [...selected, option];
    updateInput(parameter.name, next.length === 0 ? [NONE_VALUE] : next.length === all.length ? [] : next);
  };

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

  const isFixedIncomplete = range.kind === 'FIXED' && (!range.from || !range.to);

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
          <div className="flex items-start justify-between gap-4 mb-3">
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

          {report.description && (
            <div className="mb-4 rounded-lg border px-3 py-2 text-xs whitespace-pre-line"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              {report.description}
            </div>
          )}

          <div className="flex flex-wrap items-end gap-3 mb-4">
            {[
              { label: '7 days', days: 7 },
              { label: '30 days', days: 30 },
              { label: '90 days', days: 90 },
            ].map(item => (
              <button key={item.days}
                onClick={() => updateRange({ ...range, kind: range.relativeKind, days: item.days }, true)}
                className="px-3 py-1.5 rounded-full text-xs cursor-pointer"
                style={{
                  background: range.kind === range.relativeKind && range.days === item.days ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                  color: range.kind === range.relativeKind && range.days === item.days ? '#fff' : 'var(--color-text)',
                  border: '1px solid var(--color-border)',
                }}>
                Last {item.label}
              </button>
            ))}
            <button onClick={() => updateRange({ ...range, kind: 'SOURCE_RANGE' }, true)}
              className="px-3 py-1.5 rounded-full text-xs cursor-pointer"
              style={{
                background: range.kind === 'SOURCE_RANGE' ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                color: range.kind === 'SOURCE_RANGE' ? '#fff' : 'var(--color-text)',
                border: '1px solid var(--color-border)',
              }}>
              All data
            </button>
            <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <span>From</span>
              <input type="date" value={range.from}
                onChange={event => updateRange({ ...range, kind: 'FIXED', from: event.target.value })}
                className="px-2 py-1.5 rounded-lg text-sm"
                style={{ border: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </label>
            <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <span>To</span>
              <input type="date" value={range.to}
                onChange={event => updateRange({ ...range, kind: 'FIXED', to: event.target.value })}
                className="px-2 py-1.5 rounded-lg text-sm"
                style={{ border: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </label>

            {parameterList.filter(parameter => parameter.type !== 'DATE_RANGE').map(parameter => {
              const values = options[parameter.name] ?? [];
              const configured = inputs[parameter.name];
              if (parameter.multiple && values.length > 0) {
                const raw = Array.isArray(configured) ? configured : [];
                const selected = raw.length === 0 ? values : raw.includes(NONE_VALUE) ? [] : raw;
                return (
                  <details key={parameter.name} className="relative">
                    <summary className="list-none px-3 py-1.5 rounded-full text-xs cursor-pointer select-none"
                      style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
                      {parameter.name}: {selected.length === values.length ? 'All' : `${selected.length} / ${values.length}`} ▾
                    </summary>
                    <div className="absolute z-30 mt-1 w-72 max-h-72 overflow-auto rounded-lg border p-2 shadow-lg"
                      style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
                      <div className="flex gap-3 px-2 py-1">
                        <button className="text-xs cursor-pointer" onClick={() => updateInput(parameter.name, [])}
                          style={{ color: 'var(--color-primary)' }}>
                          Select all
                        </button>
                        <button className="text-xs cursor-pointer" onClick={() => updateInput(parameter.name, [NONE_VALUE])}
                          style={{ color: 'var(--color-text-secondary)' }}>
                          Clear
                        </button>
                      </div>
                      {values.map(value => (
                        <div key={value} className="flex items-center gap-1 rounded hover:bg-[var(--color-bg-secondary)]">
                          <label className="flex items-center gap-2 px-2 py-1 text-xs cursor-pointer min-w-0 flex-1">
                            <input type="checkbox" checked={selected.includes(value)} onChange={() => toggleOption(parameter, value)} />
                            <span className="truncate">{value}</span>
                          </label>
                          <button className="text-[10px] px-1.5 py-0.5 mr-1 cursor-pointer shrink-0"
                            onClick={() => updateInput(parameter.name, [value])}
                            style={{ color: 'var(--color-primary)' }}>
                            only
                          </button>
                        </div>
                      ))}
                    </div>
                  </details>
                );
              }
              return (
                <label key={parameter.name} className="flex flex-col gap-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  <span>{parameter.name}{parameter.multiple ? ' (comma separated)' : ''}</span>
                  <input
                    value={Array.isArray(configured) ? configured.join(', ') : (configured ?? '')}
                    onChange={event => updateInput(parameter.name,
                      parameter.multiple ? event.target.value.split(',').map(item => item.trim()).filter(Boolean) : event.target.value)}
                    className="px-3 py-1.5 rounded-lg text-sm"
                    style={{ border: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
                  />
                </label>
              );
            })}

            {parameterList.length > 0 && (
              <button onClick={() => void loadData()} disabled={dataLoading || isFixedIncomplete}
                className="px-3 py-1.5 rounded-full text-xs cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)', color: '#fff', border: '1px solid var(--color-primary)' }}>
                Apply
              </button>
            )}
            <button onClick={() => void loadData(true)} disabled={dataLoading}
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
            <div className="grid gap-4 md:grid-cols-2">
              {(data?.components?.length
                ? data.components
                : data ? [{ id: 'main', title: null, display_type: report.display_type, ...data }] : []
              ).map(component => {
                const display = displayByComponent.get(component.id) ?? null;
                const wideChart = display?.orientation === 'HORIZONTAL' || display?.type === 'MEASURE_BAR';
                const full = wideChart || !display?.width || display.width === 'FULL' || display?.type === 'METRIC_GROUP';
                return (
                  <div key={component.id} className={full ? 'md:col-span-2' : ''}>
                    <div className="rounded-xl border p-4 h-full" style={{ borderColor: 'var(--color-border)' }}>
                      {component.title && <div className="text-sm font-medium mb-2">{component.title}</div>}
                      <ReportRenderer data={component} displayType={component.display_type} display={display} />
                    </div>
                  </div>
                );
              })}
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
