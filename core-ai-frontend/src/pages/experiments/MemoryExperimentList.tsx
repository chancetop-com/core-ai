import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Search, ChevronLeft, ChevronRight, FlaskConical, Sliders, Trash2, Pencil, Check, X, Zap } from 'lucide-react';
import { api, type ExperimentRun, type ExperimentConfigItem, type AgentDefinition } from '../../api/client';

type Tab = 'runs' | 'configs';

const LAYER_LABELS: Record<string, string> = {
  knowledge: 'Knowledge',
  methods: 'Methods',
  trajectories: 'Trajectories',
};

const RANKING_LABELS: Record<string, string> = {
  SEMANTIC: 'Semantic',
  BM25: 'BM25',
  RECENCY: 'Recency',
  IMPORTANCE: 'Importance',
  HYBRID: 'Hybrid',
  RANDOM: 'Random',
};

const DEFAULT_LIMIT = 10;

function formatTime(d: string | undefined) {
  if (!d) return '-';
  const date = new Date(d);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return date.toLocaleDateString();
}

export default function MemoryExperimentList() {
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>('runs');
  const [runs, setRuns] = useState<ExperimentRun[]>([]);
  const [configs, setConfigs] = useState<ExperimentConfigItem[]>([]);
  const [runTotal, setRunTotal] = useState(0);
  const [configTotal, setConfigTotal] = useState(0);
  const [loading, setLoading] = useState(true);

  const [runOffset, setRunOffset] = useState(0);
  const [configOffset, setConfigOffset] = useState(0);
  const [limit, setLimit] = useState(DEFAULT_LIMIT);
  const [agentFilter, setAgentFilter] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState('');

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [searchText, setSearchText] = useState('');

  const loadRuns = useCallback(async (offset: number, agentId: string) => {
    setLoading(true);
    try {
      const res = await api.experiments.listRuns(agentId || undefined, offset, limit);
      setRuns(res.runs);
      setRunTotal(res.total);
    } finally {
      setLoading(false);
    }
  }, [limit]);

  const loadConfigs = useCallback(async (offset: number) => {
    setLoading(true);
    try {
      const res = await api.experiments.listConfigs(offset, limit);
      setConfigs(res.configs);
      setConfigTotal(res.total);
    } finally {
      setLoading(false);
    }
  }, [limit]);

  useEffect(() => {
    if (tab === 'runs') loadRuns(runOffset, agentFilter);
    else loadConfigs(configOffset);
  }, [tab]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (tab === 'runs') loadRuns(runOffset, agentFilter);
  }, [runOffset, limit, agentFilter, loadRuns, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (tab === 'configs') loadConfigs(configOffset);
  }, [configOffset, limit, loadConfigs, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = (value: string) => {
    setSearchText(value);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setAgentFilter(value);
      setRunOffset(0);
    }, 300);
  };

  const switchTab = (t: Tab) => {
    setTab(t);
    setLoading(true);
  };

  const handleDeleteConfig = async (agentId: string) => {
    if (!confirm('Delete experiment config for this agent?')) return;
    await api.experiments.deleteConfig(agentId);
    loadConfigs(configOffset);
  };

  const handleShowCreate = async () => {
    setShowCreate(true);
    if (agents.length === 0) {
      try {
        const res = await api.agents.list(false, undefined, 200);
        setAgents(res.agents);
      } catch {
        // keep dropdown empty
      }
    }
  };

  const currentTotal = tab === 'runs' ? runTotal : configTotal;
  const currentOffset = tab === 'runs' ? runOffset : configOffset;
  const setCurrentOffset = tab === 'runs' ? setRunOffset : setConfigOffset;

  return (
    <>
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Agent Memory</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Track memory injection outcomes and configure experiment policies per agent
          </p>
        </div>
        {tab === 'configs' && (
          <button onClick={handleShowCreate}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} /> New Config
          </button>
        )}
      </div>

      {/* Tabs */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--color-bg-secondary)' }}>
          <button
            onClick={() => switchTab('runs')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: tab === 'runs' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: tab === 'runs' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <FlaskConical size={14} />
            Runs
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {runTotal}
            </span>
          </button>
          <button
            onClick={() => switchTab('configs')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: tab === 'configs' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: tab === 'configs' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Sliders size={14} />
            Configs
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {configTotal}
            </span>
          </button>
        </div>
        <div className="flex items-center gap-2">
          {tab === 'runs' && (
            <div className="relative w-64">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--color-text-secondary)' }} />
              <input type="text" value={searchText}
                onChange={e => handleSearch(e.target.value)}
                placeholder="Filter by agent ID..."
                className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            </div>
          )}
        </div>
      </div>


      {/* Cards */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : tab === 'runs' && runs.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No experiment runs found.
          </div>
        ) : tab === 'configs' && configs.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No configs yet. Click "New Config" to add one.
          </div>
        ) : tab === 'runs' ? (
          runs.map(r => (
            <div key={r.id}
              onClick={() => navigate(`/experiments/memory/runs/${r.id}`)}
              className="rounded-xl border p-4 cursor-pointer transition-colors"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
              onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
              onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  {r.injection_decision
                    ? <Check size={16} style={{ color: 'var(--color-primary)' }} />
                    : <X size={16} style={{ color: 'var(--color-text-secondary)' }} />
                  }
                  <span className="font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {r.agent_id?.slice(-16)}
                  </span>
                  <span className="px-2 py-0.5 rounded text-xs"
                    style={{
                      background: r.injection_decision ? 'rgba(34,197,94,0.1)' : 'var(--color-bg)',
                      color: r.injection_decision ? '#22c55e' : 'var(--color-text-secondary)',
                    }}>
                    {r.injection_decision ? `Injected (${r.injected_memory_count ?? 0})` : 'Skipped'}
                  </span>
                  {r.enabled_layers && (
                    <span className="px-2 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
                      {r.enabled_layers.map(l => LAYER_LABELS[l] ?? l).join(', ')}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {r.prompt_tokens != null && <span>{r.prompt_tokens} tokens</span>}
                  {r.user_rating != null && <span>{'⭐'.repeat(r.user_rating)}</span>}
                  {r.outcome && <span>{r.outcome}</span>}
                  <span>{formatTime(r.created_at)}</span>
                </div>
              </div>
              <div className="flex items-center gap-4 mt-2 ml-8 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {r.ranking_strategy && <span>Ranking: {RANKING_LABELS[r.ranking_strategy] ?? r.ranking_strategy}</span>}
                <span className="font-mono">Session: {r.session_id?.slice(-12) ?? '-'}</span>
                {r.layer_breakdown && Object.keys(r.layer_breakdown).length > 0 && (
                  <span>
                    {Object.entries(r.layer_breakdown).map(([l, c]) => `${LAYER_LABELS[l] ?? l}:${c}`).join(' / ')}
                  </span>
                )}
              </div>
            </div>
          ))
        ) : (
          configs.map(c => (
            <div key={c.id}
              className="rounded-xl border p-4 cursor-pointer transition-colors"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
              onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
              onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}
              onClick={() => navigate(`/experiments/memory/configs/${c.agent_id}`)}>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  {c.enabled
                    ? <Zap size={16} style={{ color: 'var(--color-primary)' }} />
                    : <Sliders size={16} style={{ color: 'var(--color-text-secondary)' }} />
                  }
                  <span className="font-mono text-xs" style={{ color: 'var(--color-text)' }}>{c.agent_id}</span>
                  <span className="px-2 py-0.5 rounded text-xs"
                    style={{
                      background: c.enabled ? 'rgba(34,197,94,0.1)' : 'var(--color-bg)',
                      color: c.enabled ? '#22c55e' : 'var(--color-text-secondary)',
                    }}>
                    {c.enabled ? 'On' : 'Off'}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <button onClick={e => { e.stopPropagation(); navigate(`/experiments/memory/configs/${c.agent_id}`); }}
                    className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs border cursor-pointer"
                    style={{ borderColor: 'var(--color-primary)', color: 'var(--color-primary)' }}>
                    <Pencil size={13} /> Edit
                  </button>
                  <button onClick={e => { e.stopPropagation(); handleDeleteConfig(c.agent_id); }}
                    className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs border cursor-pointer"
                    style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                    <Trash2 size={13} /> Delete
                  </button>
                </div>
              </div>
              <div className="flex items-center gap-4 mt-2 ml-8 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {c.enabled_layers && c.enabled_layers.length > 0 && (
                  <span>Layers: {c.enabled_layers.map(l => LAYER_LABELS[l] ?? l).join(', ')}</span>
                )}
                {c.ranking_strategy && <span>Ranking: {RANKING_LABELS[c.ranking_strategy] ?? c.ranking_strategy}</span>}
                {c.top_k != null && <span>Top-K: {c.top_k}</span>}
                {c.injection_probability != null && <span>Probability: {Math.round(c.injection_probability * 100)}%</span>}
              </div>
            </div>
          ))
        )}
      </div>

      {/* Pagination */}
      {currentTotal > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {currentOffset + 1}–{Math.min(currentOffset + limit, currentTotal)} of {currentTotal}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setRunOffset(0); setConfigOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setCurrentOffset(Math.max(0, currentOffset - limit))} disabled={currentOffset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setCurrentOffset(currentOffset + limit)} disabled={currentOffset + limit >= currentTotal}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>

    {showCreate && (
      <div className="fixed inset-0 z-50 flex items-center justify-center"
        style={{ background: 'rgba(0,0,0,0.5)' }}
        onClick={() => setShowCreate(false)}>
        <div className="rounded-2xl shadow-2xl w-[480px] p-6"
          style={{ background: 'var(--color-bg)' }}
          onClick={e => e.stopPropagation()}>
          <h2 className="text-xl font-semibold mb-2">Create Config</h2>
          <p className="text-sm mb-6" style={{ color: 'var(--color-text-secondary)' }}>
            Select an agent to configure memory experiment settings
          </p>
          <div className="mb-6">
            <label className="block text-sm font-medium mb-2">Agent</label>
            <select
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none cursor-pointer"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
              value={selectedAgentId}
              onChange={e => setSelectedAgentId(e.target.value)}
            >
              <option value="">Select an agent...</option>
              {agents.map(a => (
                <option key={a.id} value={a.id}>{a.name} ({a.id})</option>
              ))}
            </select>
          </div>
          <div className="flex gap-2 justify-end">
            <button
              onClick={() => setShowCreate(false)}
              className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              Cancel
            </button>
            <button
              disabled={!selectedAgentId}
              onClick={() => { setShowCreate(false); navigate(`/experiments/memory/configs/${selectedAgentId}`); }}
              className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              Create
            </button>
          </div>
        </div>
      </div>
    )}
    </>
  );
}
