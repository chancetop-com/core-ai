import { useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type LegendPayload,
  type TooltipPayloadEntry,
  type TooltipValueType,
} from 'recharts';
interface RenderableReportData {
  status: string;
  columns_json: string;
  rows_json: string;
}

export interface ReportDisplaySeries {
  field: string;
  label?: string | null;
  format?: string | null;
}

export interface ReportDisplay {
  type?: string | null;
  orientation?: string | null;
  width?: string | null;
  precision?: number | null;
  series?: ReportDisplaySeries[] | null;
}

interface ReportRendererProps {
  data: RenderableReportData | null;
  displayType?: string | null;
  display?: ReportDisplay | null;
  height?: number;
}

interface ReportColumn {
  name: string;
  type: string;
  label?: string | null;
}

const COLORS = ['#6366f1', '#16a34a', '#f59e0b', '#dc2626', '#0891b2', '#8b5cf6', '#db2777', '#65a30d'];

function parseColumns(value: string | undefined | null): ReportColumn[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter(item => item && typeof item.name === 'string') : [];
  } catch {
    return [];
  }
}

function parseRows(value: string | undefined | null): (string | null)[][] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter(Array.isArray) : [];
  } catch {
    return [];
  }
}

function asNumber(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function axisLabel(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}

function displayValue(value: string | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value.length > 0 && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) return date.toLocaleString();
  }
  return value;
}

function formatValue(value: string | null | undefined, format?: string | null, precision?: number | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const number = Number(value);
  if (!Number.isFinite(number)) return displayValue(value);
  if (format === 'PERCENT') return `${(number * 100).toFixed(precision ?? 1)}%`;
  if (format === 'CURRENCY') {
    return `$${number.toLocaleString('en-US', { maximumFractionDigits: precision ?? 2 })}`;
  }
  return number.toLocaleString('zh-CN', { maximumFractionDigits: precision ?? 2 });
}

function selectedFields(display: ReportDisplay | null | undefined, columns: ReportColumn[], startIndex: number) {
  const configured = display?.series?.filter(series => series && series.field) ?? [];
  if (configured.length > 0) {
    return configured
      .map(series => {
        const index = columns.findIndex(column => column.name === series.field);
        return index < 0 ? null : { ...series, index, column: columns[index] };
      })
      .filter((item): item is NonNullable<typeof item> => item !== null);
  }
  return columns.slice(startIndex).map((column, offset) => ({
    field: column.name,
    label: column.label,
    format: null,
    index: startIndex + offset,
    column,
  }));
}

function seriesLabel(field: { label?: string | null; column: ReportColumn }) {
  return field.label || field.column.label || field.column.name;
}

function resolveIsolatedField(
  isolatedField: string | null,
  series: ReadonlyArray<Pick<ReportDisplaySeries, 'field'>>,
): string | null {
  if (!isolatedField || series.length <= 1) return null;
  return series.some(item => item.field === isolatedField) ? isolatedField : null;
}

function nextIsolatedField(
  current: string | null,
  clickedDataKey: unknown,
  series: ReadonlyArray<Pick<ReportDisplaySeries, 'field'>>,
): string | null {
  const active = resolveIsolatedField(current, series);
  if (series.length <= 1 || typeof clickedDataKey !== 'string') return active;
  if (!series.some(item => item.field === clickedDataKey)) return active;
  return active === clickedDataKey ? null : clickedDataKey;
}

function isSeriesHidden(
  field: string,
  isolatedField: string | null,
  series: ReadonlyArray<Pick<ReportDisplaySeries, 'field'>>,
): boolean {
  const active = resolveIsolatedField(isolatedField, series);
  return active !== null && active !== field;
}

function resolveAxisFormat(
  series: ReadonlyArray<Pick<ReportDisplaySeries, 'field' | 'format'>>,
  isolatedField: string | null = null,
): string | null {
  const active = resolveIsolatedField(isolatedField, series);
  const visible = active ? series.filter(item => item.field === active) : series;
  if (visible.length === 0) return null;
  const first = visible[0].format || null;
  if (!first) return null;
  return visible.every(item => (item.format || null) === first) ? first : null;
}

function formatChartValue(
  value: TooltipValueType | null | undefined,
  format?: string | null,
  precision?: number | null,
): string {
  if (value === null || value === undefined || value === '') return '—';
  if (Array.isArray(value)) {
    return value.map(item => formatValue(String(item), format, precision)).join(' ~ ');
  }
  return formatValue(String(value), format, precision);
}

function formatTooltipValue(
  value: TooltipValueType | null | undefined,
  dataKey: unknown,
  series: ReadonlyArray<Pick<ReportDisplaySeries, 'field' | 'format'>>,
  precision?: number | null,
): string {
  const format = typeof dataKey === 'string'
    ? series.find(item => item.field === dataKey)?.format
    : undefined;
  return formatChartValue(value, format, precision);
}

export default function ReportRenderer({ data, displayType, display, height = 260 }: ReportRendererProps) {
  const columns = useMemo(() => parseColumns(data?.columns_json), [data?.columns_json]);
  const rows = useMemo(() => parseRows(data?.rows_json), [data?.rows_json]);
  const [isolatedField, setIsolatedField] = useState<string | null>(null);
  const type = display?.type || displayType;

  if (!data) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>选择报表查看数据</div>;
  }
  if (data.status === 'NO_DATA') {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>此范围暂无数据</div>;
  }
  if (columns.length === 0) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>报表暂无列</div>;
  }

  if (type === 'METRIC') {
    const field = selectedFields(display, columns, 0)[0];
    const value = rows[0]?.[field ? field.index : 0];
    return (
      <div className="flex flex-col items-center justify-center py-10">
        <div className="text-4xl font-semibold">{formatValue(value, field?.format, display?.precision)}</div>
        <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
          {field ? seriesLabel(field) : columns[0]?.label || columns[0]?.name}
        </div>
      </div>
    );
  }

  if (type === 'METRIC_GROUP') {
    const fields = selectedFields(display, columns, 0);
    if (fields.length === 0) {
      return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>报表没有可展示指标</div>;
    }
    return (
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-3">
        {fields.map((field, index) => (
          <div key={field.field} className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: 'var(--color-text-secondary)' }}>
              {seriesLabel(field)}
            </div>
            <div className="text-2xl font-semibold mt-1 tabular-nums" style={{ color: COLORS[index % COLORS.length] }}>
              {formatValue(rows[0]?.[field.index], field.format, display?.precision)}
            </div>
          </div>
        ))}
      </div>
    );
  }

  if (type === 'MEASURE_BAR') {
    const fields = selectedFields(display, columns, 0);
    const items = fields
      .map(field => ({ name: seriesLabel(field), value: asNumber(rows[0]?.[field.index]), format: field.format }))
      .filter((item): item is { name: string; value: number; format: string | null | undefined } => item.value !== null && item.value > 0)
      .sort((a, b) => b.value - a.value);
    if (items.length === 0) {
      return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>此范围暂无数据</div>;
    }
    const max = Math.max(...items.map(item => item.value));
    return (
      <div className="grid gap-2.5 py-2">
        {items.map(item => (
          <div key={item.name} className="grid grid-cols-[96px_1fr_72px] items-center gap-2 text-xs">
            <div className="truncate text-right" style={{ color: 'var(--color-text-secondary)' }}>{item.name}</div>
            <div className="h-3 rounded" style={{ background: 'var(--color-bg-secondary)' }}>
              <div className="h-3 rounded" style={{ width: `${Math.max(2, item.value / max * 100)}%`, background: '#f97316' }} />
            </div>
            <div className="tabular-nums">{formatValue(String(item.value), item.format, display?.precision)}</div>
          </div>
        ))}
      </div>
    );
  }

  if (type === 'TABLE' || type == null) {
    const formats = new Map((display?.series ?? []).map(series => [series.field, series.format]));
    return (
      <div className="overflow-auto rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr>
              {columns.map(column => (
                <th key={column.name} className="text-left px-3 py-2 font-medium whitespace-nowrap">
                  {column.label || column.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                {columns.map((column, columnIndex) => (
                  <td key={column.name} className="px-3 py-2 whitespace-nowrap">
                    {formatValue(row[columnIndex], formats.get(column.name), display?.precision)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  const fields = selectedFields(display, columns, 1);
  if (fields.length === 0) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>报表没有可绘制指标</div>;
  }
  const chartRows = rows.map(row => {
    const item: Record<string, unknown> = { __axis: axisLabel(row[0]) };
    for (const field of fields) {
      item[field.field] = asNumber(row[field.index]);
    }
    return item;
  });

  const activeIsolatedField = resolveIsolatedField(isolatedField, fields);
  const axisFormat = resolveAxisFormat(fields, activeIsolatedField);
  const canIsolate = fields.length > 1;
  const formatTick = (value: number | string) => formatChartValue(value, axisFormat, display?.precision);
  const formatTooltip = (
    value: TooltipValueType | undefined,
    name: string | number | undefined,
    item: TooltipPayloadEntry,
  ) => {
    const formatted = formatTooltipValue(value, item.dataKey, fields, display?.precision);
    return name === undefined ? formatted : ([formatted, name] as [string, string | number]);
  };
  const handleLegendClick = (entry: LegendPayload) => {
    setIsolatedField(current => nextIsolatedField(current, entry.dataKey, fields));
  };

  if (type === 'BAR') {
    const horizontal = display?.orientation === 'HORIZONTAL';
    const chart = (
      <BarChart data={chartRows} layout={horizontal ? 'vertical' : 'horizontal'}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
        {horizontal ? (
          <>
            <XAxis type="number" tick={{ fontSize: 11 }} tickFormatter={formatTick} />
            <YAxis type="category" dataKey="__axis" width={120} interval={0} tick={{ fontSize: 11 }}
              tickFormatter={(value: string) => value.length > 14 ? `${value.slice(0, 14)}…` : value} />
          </>
        ) : (
          <>
            <XAxis dataKey="__axis" tick={{ fontSize: 11 }} />
            <YAxis tick={{ fontSize: 11 }} tickFormatter={formatTick} />
          </>
        )}
        <Tooltip formatter={formatTooltip} />
        {canIsolate && <Legend onClick={handleLegendClick} labelStyle={{ cursor: 'pointer' }} />}
        {fields.map((field, index) => (
          <Bar key={field.field} dataKey={field.field} name={seriesLabel(field)} fill={COLORS[index % COLORS.length]}
            hide={isSeriesHidden(field.field, activeIsolatedField, fields)}
            radius={horizontal ? [0, 3, 3, 0] : [3, 3, 0, 0]} />
        ))}
      </BarChart>
    );
    return (
      <div style={{ width: '100%', height }}>
        <ResponsiveContainer width="100%" height="100%">{chart}</ResponsiveContainer>
      </div>
    );
  }

  const chart = (
    <LineChart data={chartRows}>
      <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
      <XAxis dataKey="__axis" tick={{ fontSize: 11 }} />
      <YAxis tick={{ fontSize: 11 }} tickFormatter={formatTick} />
      <Tooltip formatter={formatTooltip} />
      <Legend
        onClick={canIsolate ? handleLegendClick : undefined}
        labelStyle={canIsolate ? { cursor: 'pointer' } : undefined}
      />
      {fields.map((field, index) => (
        <Line key={field.field} type="monotone" dataKey={field.field} name={seriesLabel(field)}
          stroke={COLORS[index % COLORS.length]} strokeWidth={2} dot={false} connectNulls={false}
          hide={isSeriesHidden(field.field, activeIsolatedField, fields)} />
      ))}
    </LineChart>
  );

  return (
    <div style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">{chart}</ResponsiveContainer>
    </div>
  );
}

/* eslint-disable react-refresh/only-export-components */
export {
  formatChartValue,
  formatTooltipValue,
  isSeriesHidden,
  nextIsolatedField,
  resolveAxisFormat,
  resolveIsolatedField,
};
/* eslint-enable react-refresh/only-export-components */
