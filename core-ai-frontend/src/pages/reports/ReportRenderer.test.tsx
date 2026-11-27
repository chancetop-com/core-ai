import { fireEvent, render, screen } from '@testing-library/react';
import { createContext, useContext, type ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { LegendPayload, TooltipPayloadEntry, TooltipValueType } from 'recharts';

interface SeriesEntry {
  dataKey: string;
  name?: string;
  hide?: boolean;
}

interface MockChartContextValue {
  series: SeriesEntry[];
  register: (entry: SeriesEntry) => void;
}

const MockChartContext = createContext<MockChartContextValue | null>(null);

vi.mock('recharts', () => {
  const ChartWrapper = ({ children, testId }: { children?: ReactNode; testId: string }) => {
    const series: SeriesEntry[] = [];
    const register = (entry: SeriesEntry) => {
      series.push(entry);
    };
    return (
      <MockChartContext.Provider value={{ series, register }}>
        <div data-testid={testId}>{children}</div>
      </MockChartContext.Provider>
    );
  };

  const SeriesStub = ({ dataKey, name, hide, kind }: SeriesEntry & { kind: string }) => {
    const ctx = useContext(MockChartContext);
    ctx?.register({ dataKey, name, hide });
    return <div data-testid={`${kind}-${dataKey}`} data-hidden={hide ? 'true' : 'false'} />;
  };

  return {
    ResponsiveContainer: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    CartesianGrid: () => null,
    LineChart: ({ children }: { children?: ReactNode }) => <ChartWrapper testId="line-chart">{children}</ChartWrapper>,
    BarChart: ({ children }: { children?: ReactNode }) => <ChartWrapper testId="bar-chart">{children}</ChartWrapper>,
    Line: ({ dataKey, name, hide }: SeriesEntry) => <SeriesStub dataKey={dataKey} name={name} hide={hide} kind="line" />,
    Bar: ({ dataKey, name, hide }: SeriesEntry) => <SeriesStub dataKey={dataKey} name={name} hide={hide} kind="bar" />,
    XAxis: ({ type, tickFormatter }: { type?: string; tickFormatter?: (value: never) => string }) => (
      <div
        data-testid="x-axis"
        data-tick-sample={tickFormatter ? tickFormatter((type === 'category' ? 'A very long category label' : 6588.428571428571) as never) : ''}
      />
    ),
    YAxis: ({ type, tickFormatter }: { type?: string; tickFormatter?: (value: never) => string }) => (
      <div
        data-testid="y-axis"
        data-tick-sample={tickFormatter ? tickFormatter((type === 'category' ? 'A very long category label' : 6588.428571428571) as never) : ''}
      />
    ),
    Tooltip: ({ formatter }: {
      formatter?: (
        value: TooltipValueType | undefined,
        name: string | number | undefined,
        item: TooltipPayloadEntry,
      ) => ReactNode | [ReactNode, ReactNode];
    }) => {
      const ctx = useContext(MockChartContext);
      if (!formatter || !ctx) return null;
      const sample = formatter(6588.428571428571, '7 日均线', {
        dataKey: 'ma7',
        name: '7 日均线',
        graphicalItemId: 'line-ma7',
      });
      const text = Array.isArray(sample) ? `${sample[1]}: ${sample[0]}` : String(sample);
      return <div data-testid="tooltip-sample">{text}</div>;
    },
    Legend: ({ onClick }: { onClick?: (entry: LegendPayload) => void }) => (
      <div data-testid="legend" data-interactive={onClick ? 'true' : 'false'}>
        <button type="button" onClick={() => onClick?.({ value: '曝光合计', dataKey: 'impressions' })}>
          曝光合计
        </button>
        <button type="button" onClick={() => onClick?.({ value: '7 日均线', dataKey: 'ma7' })}>
          7 日均线
        </button>
      </div>
    ),
  };
});

import ReportRenderer, {
  formatChartValue,
  formatTooltipValue,
  isSeriesHidden,
  nextIsolatedField,
  resolveAxisFormat,
  resolveIsolatedField,
} from './ReportRenderer';

describe('ReportRenderer series isolation helpers', () => {
  const twoSeries = [{ field: 'impressions' }, { field: 'ma7' }];

  it('cycles isolation: none -> A -> all -> B -> A', () => {
    let isolated: string | null = null;
    expect(isSeriesHidden('impressions', isolated, twoSeries)).toBe(false);
    expect(isSeriesHidden('ma7', isolated, twoSeries)).toBe(false);

    isolated = nextIsolatedField(isolated, 'impressions', twoSeries);
    expect(isolated).toBe('impressions');
    expect(isSeriesHidden('impressions', isolated, twoSeries)).toBe(false);
    expect(isSeriesHidden('ma7', isolated, twoSeries)).toBe(true);

    isolated = nextIsolatedField(isolated, 'impressions', twoSeries);
    expect(isolated).toBeNull();
    expect(isSeriesHidden('impressions', isolated, twoSeries)).toBe(false);
    expect(isSeriesHidden('ma7', isolated, twoSeries)).toBe(false);

    isolated = nextIsolatedField(isolated, 'ma7', twoSeries);
    expect(isolated).toBe('ma7');
    expect(isSeriesHidden('impressions', isolated, twoSeries)).toBe(true);
    expect(isSeriesHidden('ma7', isolated, twoSeries)).toBe(false);

    isolated = nextIsolatedField(isolated, 'impressions', twoSeries);
    expect(isolated).toBe('impressions');
    expect(isSeriesHidden('impressions', isolated, twoSeries)).toBe(false);
    expect(isSeriesHidden('ma7', isolated, twoSeries)).toBe(true);
  });

  it('ignores non-string or unknown legend dataKeys and guards against stale series', () => {
    expect(nextIsolatedField('impressions', undefined, twoSeries)).toBe('impressions');
    expect(nextIsolatedField('impressions', 0, twoSeries)).toBe('impressions');
    expect(nextIsolatedField('impressions', 'unknown', twoSeries)).toBe('impressions');

    const newSeries = [{ field: 'clicks' }, { field: 'calls' }];
    expect(resolveIsolatedField('impressions', newSeries)).toBeNull();
    expect(isSeriesHidden('clicks', 'impressions', newSeries)).toBe(false);
    expect(isSeriesHidden('calls', 'impressions', newSeries)).toBe(false);
    expect(nextIsolatedField('impressions', 'clicks', newSeries)).toBe('clicks');
  });

  it('does not isolate single-series charts', () => {
    const single = [{ field: 'impressions' }];
    expect(nextIsolatedField(null, 'impressions', single)).toBeNull();
    expect(resolveIsolatedField('impressions', single)).toBeNull();
    expect(isSeriesHidden('impressions', 'impressions', single)).toBe(false);
  });
});

describe('ReportRenderer chart value formatting helpers', () => {
  it('formats numbers according to display precision', () => {
    expect(formatChartValue(6588.428571428571, null, 0)).toBe('6,588');
    expect(formatChartValue(12345.6, null, 0)).toBe('12,346');
    expect(formatChartValue(6588.428571428571, null, 2)).toBe('6,588.43');
    expect(formatChartValue(6588.428571428571, 'NUMBER', 0)).toBe('6,588');
  });

  it('formats PERCENT, CURRENCY, and nullish values matching formatValue', () => {
    expect(formatChartValue(0.1234, 'PERCENT', 1)).toBe('12.3%');
    expect(formatChartValue(0.126, 'PERCENT', 0)).toBe('13%');
    expect(formatChartValue(0.1234, 'PERCENT', null)).toBe('12.3%');
    expect(formatChartValue(1234.567, 'CURRENCY', 0)).toBe('$1,235');
    expect(formatChartValue(1234.567, 'CURRENCY', 2)).toBe('$1,234.57');
    expect(formatChartValue(null, 'NUMBER', 0)).toBe('—');
    expect(formatChartValue(undefined, 'CURRENCY', 2)).toBe('—');
    expect(formatChartValue('', 'PERCENT', 1)).toBe('—');
  });

  it('maps tooltip dataKey to the configured series format', () => {
    const series = [
      { field: 'impressions', format: 'NUMBER' },
      { field: 'ctr', format: 'PERCENT' },
      { field: 'revenue', format: 'CURRENCY' },
    ];
    expect(formatTooltipValue(6588.428571428571, 'impressions', series, 0)).toBe('6,588');
    expect(formatTooltipValue(0.156, 'ctr', series, 1)).toBe('15.6%');
    expect(formatTooltipValue(9876.54, 'revenue', series, 0)).toBe('$9,877');
    expect(formatTooltipValue(6588.428571428571, undefined, series, 0)).toBe('6,588');
  });

  it('resolves Y-axis format across shared, mixed, and isolated series', () => {
    expect(resolveAxisFormat([
      { field: 'a', format: 'PERCENT' },
      { field: 'b', format: 'PERCENT' },
    ])).toBe('PERCENT');

    expect(resolveAxisFormat([
      { field: 'a', format: 'NUMBER' },
      { field: 'b', format: 'PERCENT' },
    ])).toBeNull();

    expect(resolveAxisFormat([
      { field: 'a', format: 'CURRENCY' },
      { field: 'b', format: null },
    ])).toBeNull();

    expect(resolveAxisFormat([
      { field: 'a', format: 'NUMBER' },
      { field: 'b', format: 'PERCENT' },
    ], 'b')).toBe('PERCENT');

    expect(resolveAxisFormat([
      { field: 'a', format: 'NUMBER' },
      { field: 'b', format: 'PERCENT' },
    ], 'stale')).toBeNull();
  });
});

describe('ReportRenderer chart interactions', () => {
  const columnsJson = JSON.stringify([
    { name: 'day', type: 'DATE', label: '日期' },
    { name: 'impressions', type: 'INTEGER', label: '曝光合计' },
    { name: 'ma7', type: 'DOUBLE', label: '7 日均线' },
  ]);
  const rowsJson = JSON.stringify([
    ['2026-11-01', '7000', '6588.428571428571'],
    ['2026-11-02', '8200', '7123.75'],
  ]);

  it('isolates series on legend click and formats tooltip and Y-axis ticks on LINE charts', () => {
    render(
      <ReportRenderer
        data={{ status: 'OK', columns_json: columnsJson, rows_json: rowsJson }}
        display={{
          type: 'LINE',
          precision: 0,
          series: [
            { field: 'impressions', label: '曝光合计', format: 'NUMBER' },
            { field: 'ma7', label: '7 日均线', format: 'NUMBER' },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('line-impressions').getAttribute('data-hidden')).toBe('false');
    expect(screen.getByTestId('line-ma7').getAttribute('data-hidden')).toBe('false');
    expect(screen.getByTestId('y-axis').getAttribute('data-tick-sample')).toBe('6,588');
    expect(screen.getByTestId('tooltip-sample').textContent).toBe('7 日均线: 6,588');

    fireEvent.click(screen.getByRole('button', { name: '曝光合计' }));
    expect(screen.getByTestId('line-impressions').getAttribute('data-hidden')).toBe('false');
    expect(screen.getByTestId('line-ma7').getAttribute('data-hidden')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: '7 日均线' }));
    expect(screen.getByTestId('line-impressions').getAttribute('data-hidden')).toBe('true');
    expect(screen.getByTestId('line-ma7').getAttribute('data-hidden')).toBe('false');

    fireEvent.click(screen.getByRole('button', { name: '7 日均线' }));
    expect(screen.getByTestId('line-impressions').getAttribute('data-hidden')).toBe('false');
    expect(screen.getByTestId('line-ma7').getAttribute('data-hidden')).toBe('false');
  });

  it('isolates series on multi-series BAR charts and keeps single-series BAR without legend', () => {
    const { rerender } = render(
      <ReportRenderer
        data={{ status: 'OK', columns_json: columnsJson, rows_json: rowsJson }}
        display={{
          type: 'BAR',
          precision: 0,
          series: [
            { field: 'impressions', label: '曝光合计', format: 'CURRENCY' },
            { field: 'ma7', label: '7 日均线', format: 'CURRENCY' },
          ],
        }}
      />,
    );

    expect(screen.getByTestId('y-axis').getAttribute('data-tick-sample')).toBe('$6,588');
    fireEvent.click(screen.getByRole('button', { name: '曝光合计' }));
    expect(screen.getByTestId('bar-impressions').getAttribute('data-hidden')).toBe('false');
    expect(screen.getByTestId('bar-ma7').getAttribute('data-hidden')).toBe('true');

    rerender(
      <ReportRenderer
        data={{ status: 'OK', columns_json: columnsJson, rows_json: rowsJson }}
        display={{
          type: 'BAR',
          precision: 0,
          series: [{ field: 'impressions', label: '曝光合计', format: 'CURRENCY' }],
        }}
      />,
    );
    expect(screen.queryByTestId('legend')).toBeNull();
    expect(screen.getByTestId('bar-impressions').getAttribute('data-hidden')).toBe('false');
  });

  it('keeps single-series LINE legend non-interactive', () => {
    render(
      <ReportRenderer
        data={{ status: 'OK', columns_json: columnsJson, rows_json: rowsJson }}
        display={{
          type: 'LINE',
          precision: 0,
          series: [{ field: 'impressions', label: '曝光合计', format: 'NUMBER' }],
        }}
      />,
    );

    expect(screen.getByTestId('legend').getAttribute('data-interactive')).toBe('false');
    fireEvent.click(screen.getByRole('button', { name: '曝光合计' }));
    expect(screen.getByTestId('line-impressions').getAttribute('data-hidden')).toBe('false');
  });

  it('formats horizontal BAR numeric X-axis ticks while preserving category Y-axis truncation', () => {
    render(
      <ReportRenderer
        data={{ status: 'OK', columns_json: columnsJson, rows_json: rowsJson }}
        display={{
          type: 'BAR',
          orientation: 'HORIZONTAL',
          precision: 0,
          series: [{ field: 'impressions', label: '曝光合计', format: 'CURRENCY' }],
        }}
      />,
    );

    expect(screen.getByTestId('x-axis').getAttribute('data-tick-sample')).toBe('$6,588');
    expect(screen.getByTestId('y-axis').getAttribute('data-tick-sample')).toBe('A very long ca…');
  });
});
