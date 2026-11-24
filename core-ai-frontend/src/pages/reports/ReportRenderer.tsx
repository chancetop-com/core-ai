import { useMemo } from 'react';
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
} from 'recharts';
interface RenderableReportData {
  status: string;
  columns_json: string;
  rows_json: string;
}

interface ReportRendererProps {
  data: RenderableReportData | null;
  displayType?: string | null;
  height?: number;
}

interface ReportColumn {
  name: string;
  type: string;
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

export default function ReportRenderer({ data, displayType, height = 260 }: ReportRendererProps) {
  const columns = useMemo(() => parseColumns(data?.columns_json), [data?.columns_json]);
  const rows = useMemo(() => parseRows(data?.rows_json), [data?.rows_json]);

  if (!data) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>选择报表查看数据</div>;
  }
  if (data.status === 'NO_DATA') {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>此范围暂无数据</div>;
  }
  if (columns.length === 0) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>报表暂无列</div>;
  }

  if (displayType === 'METRIC') {
    const value = rows[0]?.[0];
    return (
      <div className="flex flex-col items-center justify-center py-10">
        <div className="text-4xl font-semibold">{displayValue(value)}</div>
        <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>{columns[0]?.name}</div>
      </div>
    );
  }

  if (displayType === 'TABLE' || displayType == null) {
    return (
      <div className="overflow-auto rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr>
              {columns.map(column => (
                <th key={column.name} className="text-left px-3 py-2 font-medium whitespace-nowrap">{column.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                {columns.map((column, columnIndex) => (
                  <td key={column.name} className="px-3 py-2 whitespace-nowrap">{displayValue(row[columnIndex])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  const seriesColumns = columns.slice(1);
  const chartRows = rows.map(row => {
    const item: Record<string, unknown> = { __axis: axisLabel(row[0]) };
    for (let index = 1; index < columns.length; index++) {
      item[columns[index].name] = asNumber(row[index]);
    }
    return item;
  });

  if (seriesColumns.length === 0) {
    return <div className="text-sm py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>报表没有可绘制指标</div>;
  }

  const chart = displayType === 'BAR' ? (
    <BarChart data={chartRows}>
      <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
      <XAxis dataKey="__axis" tick={{ fontSize: 11 }} />
      <YAxis tick={{ fontSize: 11 }} />
      <Tooltip />
      <Legend />
      {seriesColumns.map((column, index) => (
        <Bar key={column.name} dataKey={column.name} fill={COLORS[index % COLORS.length]} radius={[3, 3, 0, 0]} />
      ))}
    </BarChart>
  ) : (
    <LineChart data={chartRows}>
      <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
      <XAxis dataKey="__axis" tick={{ fontSize: 11 }} />
      <YAxis tick={{ fontSize: 11 }} />
      <Tooltip />
      <Legend />
      {seriesColumns.map((column, index) => (
        <Line key={column.name} type="monotone" dataKey={column.name} stroke={COLORS[index % COLORS.length]}
          strokeWidth={2} dot={false} connectNulls={false} />
      ))}
    </LineChart>
  );

  return (
    <div style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        {chart}
      </ResponsiveContainer>
    </div>
  );
}
