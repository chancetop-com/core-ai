import { useEffect, useState, useMemo } from 'react';
import ModeTimeSelector from './components/ModeTimeSelector';
import GlobalSummaryCards from './components/GlobalSummaryCards';
import GlobalTrendChart from './components/GlobalTrendChart';
import DimensionTabs from './components/DimensionTabs';
import DimensionTable from './components/DimensionTable';
import DimensionTrendChart from './components/DimensionTrendChart';
import { api } from '../../api/client';
import type {
  AnalyticsGlobal,
  TrendPoint,
  DimensionAnalytics,
  AnalyticsMode,
  AnalyticsDimension,
  AnalyticsParams,
} from '../../api/client';

type Range = '7d' | '30d';

interface DashboardState {
  globalSummary: AnalyticsGlobal | null;
  globalTrend: TrendPoint[];
  dimensionData: DimensionAnalytics | null;
  sparklines: Map<string, TrendPoint[]>;
  dimensionTrend: Map<string, TrendPoint[]>;
  loading: boolean;
  error: string | null;
}

const EMPTY_GLOBAL: AnalyticsGlobal = {
  totalInputTokens: 0, totalOutputTokens: 0, totalTokens: 0,
  totalCachedTokens: 0, totalCostUsd: 0, totalCalls: 0,
  avgTokensPerCall: 0, avgCostPerCall: 0,
  maxTokensPerCall: 0, maxCostPerCall: 0, p90TokensPerCall: 0,
  prevTotalTokens: null, prevTotalCostUsd: null,
};

export default function Dashboard() {
  const [mode, setMode] = useState<AnalyticsMode>('history');
  const [range, setRange] = useState<Range>('7d');
  const [activeTab, setActiveTab] = useState<AnalyticsDimension>('source');
  const [sort, setSort] = useState<string>('tokens');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [state, setState] = useState<DashboardState>({
    globalSummary: null, globalTrend: [], dimensionData: null,
    sparklines: new Map(), dimensionTrend: new Map(),
    loading: true, error: null,
  });

  const params = useMemo((): AnalyticsParams => ({ mode, range, sort }), [mode, range, sort]);

  // main fetch: global + trend + current tab dimension
  useEffect(() => {
    let cancelled = false;

    function setError(err: unknown) {
      if (cancelled) return;
      setState(prev => ({
        ...prev, loading: false,
        error: err instanceof Error ? err.message : 'Failed to load analytics',
      }));
    }

    async function load() {
      setState(prev => ({ ...prev, loading: true, error: null }));

      try {
        const [globalData, trendData, dimData] = await Promise.all([
          api.adminAnalytics.global(params),
          api.adminAnalytics.trend(params),
          fetchDimension(activeTab, params),
        ]);
        if (cancelled) return;

        // fetch sparklines for top-5
        const topKeys = dimData.items.slice(0, 5).map(i => i.key);
        const sparklineMap = await fetchSparklines(activeTab, params, topKeys);
        if (cancelled) return;

        // fetch dimension trend for top-5
        const trendMap = await fetchSparklines(activeTab, params, topKeys);
        if (cancelled) return;

        setState({
          globalSummary: globalData,
          globalTrend: trendData,
          dimensionData: dimData,
          sparklines: sparklineMap,
          dimensionTrend: trendMap,
          loading: false,
          error: null,
        });
      } catch (err) {
        setError(err);
      }
    }

    load();
    return () => { cancelled = true; };
  }, [mode, range, activeTab, params]); // eslint-disable-line react-hooks/exhaustive-deps

  // fetch selected key trend
  useEffect(() => {
    if (!selectedKey) return;
    const key = selectedKey; // narrow type for async safety
    let cancelled = false;

    async function load() {
      try {
        const data = await api.adminAnalytics.dimensionTrend(activeTab, {
          ...params, keys: key,
        });
        if (cancelled) return;
        setState(prev => {
          const trend = new Map(prev.dimensionTrend);
          trend.set(key, data);
          return { ...prev, dimensionTrend: trend };
        });
      } catch {
        // silently ignore trend fetch errors for detail view
      }
    }

    load();
    return () => { cancelled = true; };
  }, [selectedKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const summary = state.globalSummary || EMPTY_GLOBAL;

  const top5Trends = useMemo(() => {
    const top5 = (state.dimensionData?.items || []).slice(0, 5).map(i => i.key);
    const filtered = new Map<string, TrendPoint[]>();
    for (const k of top5) {
      if (state.dimensionTrend.has(k)) filtered.set(k, state.dimensionTrend.get(k)!);
    }
    return filtered;
  }, [state.dimensionData, state.dimensionTrend]);

  return (
    <div className="p-6">
      <div className="mb-5 flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Analytics Dashboard</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {mode === 'realtime'
              ? 'Live token consumption · refreshed on load'
              : `Last ${range === '7d' ? '7' : '30'} days · pre-aggregated stats`}
          </p>
        </div>
        <ModeTimeSelector mode={mode} range={range}
          onModeChange={m => { setMode(m); setSelectedKey(null); }}
          onRangeChange={r => { setRange(r as Range); setSelectedKey(null); }} />
      </div>

      {state.error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm"
          style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          Could not load analytics: {state.error}
        </div>
      )}

      <GlobalSummaryCards summary={summary} loading={state.loading && !state.globalSummary} />

      <div className="mt-4">
        <GlobalTrendChart data={state.globalTrend} loading={state.loading} />
      </div>

      <section className="mt-4 rounded-lg border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <DimensionTabs active={activeTab} onChange={tab => { setActiveTab(tab); setSelectedKey(null); }} />

        <DimensionTable
          items={state.dimensionData?.items || []}
          dimension={activeTab}
          sparklines={state.sparklines}
          sort={sort}
          onSort={setSort}
          onSelect={setSelectedKey}
          selectedKey={selectedKey}
          loading={state.loading}
        />

        {top5Trends.size > 0 && (
          <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
            <div className="px-4 py-2 border-b" style={{ borderColor: 'var(--color-border)' }}>
              <h3 className="text-sm font-semibold">Top 5 Trend</h3>
            </div>
            <DimensionTrendChart dimension={activeTab} trends={top5Trends} loading={state.loading} />
          </div>
        )}

        {selectedKey && (
          <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
            <div className="px-4 py-2 flex items-center justify-between gap-3"
              style={{ borderColor: 'var(--color-border)' }}>
              <h3 className="text-sm font-semibold">{selectedKey} — Detail</h3>
              <button onClick={() => setSelectedKey(null)}
                className="text-xs font-medium cursor-pointer"
                style={{ color: 'var(--color-primary)' }}>
                Close
              </button>
            </div>
            {state.dimensionTrend.has(selectedKey) ? (
              <DimensionTrendChart
                dimension={activeTab}
                trends={new Map([[selectedKey, state.dimensionTrend.get(selectedKey)!]])}
                loading={false}
              />
            ) : (
              <div className="py-8 text-center text-sm animate-pulse"
                style={{ color: 'var(--color-text-secondary)' }}>
                Loading...
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

async function fetchDimension(dim: AnalyticsDimension, params: AnalyticsParams): Promise<DimensionAnalytics> {
  switch (dim) {
    case 'source': return api.adminAnalytics.bySource(params);
    case 'agent': return api.adminAnalytics.byAgent(params);
    case 'user': return api.adminAnalytics.byUser(params);
    case 'provider': return api.adminAnalytics.byProvider(params);
    case 'model': return api.adminAnalytics.byModel(params);
    default: return api.adminAnalytics.bySource(params);
  }
}

async function fetchSparklines(dim: AnalyticsDimension, params: AnalyticsParams, keys: string[]): Promise<Map<string, TrendPoint[]>> {
  if (keys.length === 0) return new Map();
  try {
    const data = await api.adminAnalytics.dimensionTrend(dim, { ...params, keys: keys.join(',') });
    // group by key — the backend returns flat trend points, we need to split by dimension key
    // For now, return all as single series if the API returns flat
    const map = new Map<string, TrendPoint[]>();
    keys.forEach(k => map.set(k, data));
    return map;
  } catch {
    return new Map();
  }
}
