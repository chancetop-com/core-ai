import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronRight, FlaskConical, Loader2, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api, type AgentDefinition } from '../../api/client';
import {
  agentVersionApi,
  comparisonApi,
  type AgentComparison as ComparisonRecord,
  type AgentVersion,
} from '../../api/comparison';
import { formatRelativeTime } from '../traces/traceViewModel';

const inputStyle: React.CSSProperties = {
  borderColor: 'var(--color-border)',
  background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)',
};

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', RUNNING: '进行中', ENDED: '已结束' };
const STATUS_STYLE: Record<string, React.CSSProperties> = {
  DRAFT: { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' },
  RUNNING: { background: 'var(--color-primary)', color: '#fff' },
  ENDED: { background: 'rgba(34,197,94,0.15)', color: 'var(--color-success)' },
};

/** List page for version comparisons: one experiment = one record; click a row to open its detail. */
export default function AgentComparison() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') === 'versions' ? 'versions' : 'experiments';

  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [comparisons, setComparisons] = useState<ComparisonRecord[]>([]);
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [agentFilter, setAgentFilter] = useState('');

  const [versionAgentId, setVersionAgentId] = useState<string>(() =>
    searchParams.get('agent') || localStorage.getItem('comparison_agentId') || '');
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);

  const [showNew, setShowNew] = useState(false);
  const [newAgentId, setNewAgentId] = useState('');
  const [newVersionA, setNewVersionA] = useState('');
  const [newVersionB, setNewVersionB] = useState('');
  const [newVersions, setNewVersions] = useState<AgentVersion[]>([]);
  const [creating, setCreating] = useState(false);

  const agentName = useCallback((agentId?: string) => {
    if (!agentId) return '-';
    return agents.find(agent => agent.id === agentId)?.name || agentId.slice(0, 8);
  }, [agents]);

  const loadComparisons = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await comparisonApi.list();
      setComparisons(res.comparisons || []);
      setEnabled(true);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (message.includes('403')) setEnabled(false);
      else setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadVersions = useCallback(async (agentId: string) => {
    if (!agentId) { setVersions([]); return; }
    setVersionsLoading(true);
    try {
      const res = await agentVersionApi.list(agentId);
      setVersions(res.versions || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setVersionsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadComparisons();
    api.agents.list(true).then(res => {
      const chatAgents = (res.agents || []).filter(agent => agent.type !== 'LLM_CALL');
      setAgents(chatAgents);
    }).catch(err => console.error('load agents failed', err));
  }, [loadComparisons]);

  useEffect(() => {
    if (tab === 'versions' && versionAgentId) {
      localStorage.setItem('comparison_agentId', versionAgentId);
      void loadVersions(versionAgentId);
    }
  }, [tab, versionAgentId, loadVersions]);

  useEffect(() => {
    if (!showNew || !newAgentId) return;
    agentVersionApi.list(newAgentId).then(res => setNewVersions(res.versions || []))
      .catch(err => console.error('load versions failed', err));
  }, [showNew, newAgentId]);

  const switchTab = (next: 'experiments' | 'versions') => {
    setSearchParams(next === 'versions' ? { tab: 'versions' } : {}, { replace: true });
  };

  const refresh = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await loadComparisons();
      if (tab === 'versions' && versionAgentId) await loadVersions(versionAgentId);
    } finally {
      setBusy(false);
    }
  }, [loadComparisons, loadVersions, tab, versionAgentId]);

  const handleDeleteExperiment = useCallback(async (item: ComparisonRecord) => {
    if (!window.confirm('删除这条草稿实验记录？')) return;
    setBusy(true);
    setError(null);
    try {
      await comparisonApi.remove(item.id);
      await loadComparisons();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [loadComparisons]);

  const handleDeleteVersion = useCallback(async (version: AgentVersion) => {
    if (!window.confirm(`删除版本 ${version.label || version.id.slice(0, 8)}？`)) return;
    setBusy(true);
    setError(null);
    try {
      await agentVersionApi.remove(version.id);
      await loadVersions(versionAgentId);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [loadVersions, versionAgentId]);

  const openNew = () => {
    const agentId = agentFilter || agents[0]?.id || '';
    setNewAgentId(agentId);
    setNewVersionA('');
    setNewVersionB('');
    setNewVersions([]);
    setError(null);
    setShowNew(true);
  };

  const handleCreate = async () => {
    if (!newAgentId || creating) return;
    setCreating(true);
    setError(null);
    try {
      const created = await comparisonApi.create(newAgentId, {
        versionAId: newVersionA || null,
        versionBId: newVersionB || null,
      });
      navigate(`/experiments/agent-comparison/${created.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setCreating(false);
    }
  };

  const filtered = useMemo(() => comparisons.filter(item =>
    (statusFilter === 'ALL' || item.status === statusFilter)
    && (!agentFilter || item.agent_id === agentFilter)
  ), [comparisons, statusFilter, agentFilter]);

  const newSourceOptions = useMemo(() => newVersions.map(version => ({
    id: version.id,
    label: `${version.label || version.id.slice(0, 8)} · ${version.model || '(default)'}`,
  })), [newVersions]);

  if (!enabled && !loading) {
    return (
      <div className="mx-auto max-w-5xl p-6">
        <h1 className="flex items-center gap-2 text-lg font-semibold">
          <FlaskConical size={18} style={{ color: 'var(--color-primary)' }} /> Agent Comparison
        </h1>
        <p className="mt-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          需要 agent.compare 权限才能管理版本对比实验。
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-lg font-semibold" style={{ color: 'var(--color-text)' }}>
            <FlaskConical size={18} style={{ color: 'var(--color-primary)' }} /> Agent Comparison
          </h1>
          <div className="mt-0.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            用真实用户对同一个 agent 的两个版本做 A/B：每轮双跑、逐轮投票、胜者走发布流程上线
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => void refresh()} disabled={busy}
            className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs cursor-pointer disabled:opacity-40"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            <RefreshCw size={12} className={busy ? 'animate-spin' : ''} /> 刷新
          </button>
          {tab === 'experiments' ? (
            <button type="button" onClick={openNew} disabled={agents.length === 0}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              <Plus size={12} /> New Experiment
            </button>
          ) : (
            <button type="button" onClick={() => void (async () => {
              if (!versionAgentId) return;
              setBusy(true);
              setError(null);
              try {
                const version = await agentVersionApi.create({ agent_id: versionAgentId });
                setBusy(false);
                navigate(`/experiments/agent-comparison/versions/${version.id}`);
              } catch (err) {
                setError(err instanceof Error ? err.message : String(err));
                setBusy(false);
              }
            })()} disabled={busy || !versionAgentId}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              <Plus size={12} /> New Version
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="mt-4 rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
      )}

      <div className="mt-5 flex items-center gap-1 border-b" style={{ borderColor: 'var(--color-border)' }}>
        {([['experiments', `实验（${comparisons.length}）`], ['versions', '版本']] as const).map(([key, label]) => (
          <button key={key} type="button" onClick={() => switchTab(key)}
            className="-mb-px border-b-2 px-3 py-2 text-sm cursor-pointer"
            style={{
              borderColor: tab === key ? 'var(--color-primary)' : 'transparent',
              color: tab === key ? 'var(--color-text)' : 'var(--color-text-secondary)',
              fontWeight: tab === key ? 600 : 400,
            }}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'experiments' && (
        <>
          <div className="mt-4 flex items-center gap-2">
            <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
              className="rounded-lg border px-3 py-1.5 text-xs outline-none" style={inputStyle}>
              <option value="ALL">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="RUNNING">进行中</option>
              <option value="ENDED">已结束</option>
            </select>
            <select value={agentFilter} onChange={e => setAgentFilter(e.target.value)}
              className="rounded-lg border px-3 py-1.5 text-xs outline-none" style={inputStyle}>
              <option value="">全部 Agent</option>
              {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
            </select>
          </div>

          <div className="mt-3 overflow-hidden rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs" style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-secondary)' }}>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium">Agent</th>
                  <th className="px-3 py-2 font-medium">版本 1</th>
                  <th className="px-3 py-2 font-medium">版本 2</th>
                  <th className="px-3 py-2 font-medium">参与者</th>
                  <th className="px-3 py-2 font-medium">创建时间</th>
                  <th className="w-16 px-3 py-2 font-medium"></th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => (
                  <tr key={item.id} className="cursor-pointer border-t" style={{ borderColor: 'var(--color-border)' }}
                    onClick={() => navigate(`/experiments/agent-comparison/${item.id}`)}>
                    <td className="px-3 py-2.5">
                      <span className="rounded-full px-2 py-0.5 text-[11px]"
                        style={STATUS_STYLE[item.status] || STATUS_STYLE.DRAFT}>
                        {STATUS_LABEL[item.status] || item.status}
                      </span>
                      {item.adopted_variant && (
                        <span className="ml-2 text-[11px]" style={{ color: 'var(--color-success)' }}>
                          已采用 {item.adopted_variant.toUpperCase()}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 font-medium">{agentName(item.agent_id)}</td>
                    <td className="px-3 py-2.5 font-mono text-xs">
                      {item.variant_a?.model || '?'}
                      <span className="ml-1.5 font-sans text-[10px]" style={{ color: 'var(--color-text-muted)' }}>
                        {item.version_a_id ? '版本' : '线上'}
                      </span>
                    </td>
                    <td className="px-3 py-2.5 font-mono text-xs">
                      {item.variant_b?.model || '?'}
                      <span className="ml-1.5 font-sans text-[10px]" style={{ color: 'var(--color-text-muted)' }}>
                        {item.version_b_id ? '版本' : 'A 副本'}
                      </span>
                    </td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {(item.participant_user_ids || []).length}
                    </td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatRelativeTime(item.created_at)}
                    </td>
                    <td className="px-3 py-2.5">
                      <div className="flex items-center justify-end gap-1">
                        {item.status === 'DRAFT' && (
                          <button type="button" title="删除草稿实验" disabled={busy}
                            onClick={event => { event.stopPropagation(); void handleDeleteExperiment(item); }}
                            className="rounded-md p-1.5 hover:opacity-80 cursor-pointer disabled:opacity-40"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            <Trash2 size={13} />
                          </button>
                        )}
                        <ChevronRight size={14} style={{ color: 'var(--color-text-muted)' }} />
                      </div>
                    </td>
                  </tr>
                ))}
                {!loading && filtered.length === 0 && (
                  <tr>
                    <td colSpan={7} className="px-3 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                      还没有实验记录。点击右上角 “New Experiment” 创建一条。
                    </td>
                  </tr>
                )}
                {loading && (
                  <tr>
                    <td colSpan={7} className="px-3 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                      <Loader2 size={14} className="animate-spin" style={{ display: 'inline' }} /> 加载中…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === 'versions' && (
        <>
          <div className="mt-4 flex items-center gap-2">
            <select value={versionAgentId} onChange={e => setVersionAgentId(e.target.value)}
              className="rounded-lg border px-3 py-1.5 text-xs outline-none" style={inputStyle}>
              <option value="">选择 agent…</option>
              {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
            </select>
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              新建版本会复制当前线上配置，可完整编辑并保存（不影响线上）
            </span>
          </div>

          <div className="mt-3 overflow-hidden rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs" style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-secondary)' }}>
                  <th className="px-3 py-2 font-medium">版本名称</th>
                  <th className="px-3 py-2 font-medium">Model</th>
                  <th className="px-3 py-2 font-medium">更新时间</th>
                  <th className="w-24 px-3 py-2 font-medium"></th>
                </tr>
              </thead>
              <tbody>
                {versions.map(version => (
                  <tr key={version.id} className="cursor-pointer border-t" style={{ borderColor: 'var(--color-border)' }}
                    onClick={() => navigate(`/experiments/agent-comparison/versions/${version.id}`)}>
                    <td className="px-3 py-2.5 font-medium">{version.label || version.id.slice(0, 8)}</td>
                    <td className="px-3 py-2.5 font-mono text-xs">{version.model || '(default)'}</td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatRelativeTime(version.updated_at)}
                    </td>
                    <td className="px-3 py-2.5">
                      <div className="flex items-center justify-end gap-1">
                        <button type="button" title="删除版本" disabled={busy}
                          onClick={event => { event.stopPropagation(); void handleDeleteVersion(version); }}
                          className="rounded-md p-1.5 hover:opacity-80 cursor-pointer disabled:opacity-40"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          <Trash2 size={13} />
                        </button>
                        <ChevronRight size={14} style={{ color: 'var(--color-text-muted)' }} />
                      </div>
                    </td>
                  </tr>
                ))}
                {!versionsLoading && versions.length === 0 && (
                  <tr>
                    <td colSpan={4} className="px-3 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                      {versionAgentId ? '这个 agent 还没有版本。点击右上角 “New Version” 创建。' : '先选择一个 agent。'}
                    </td>
                  </tr>
                )}
                {versionsLoading && (
                  <tr>
                    <td colSpan={4} className="px-3 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                      <Loader2 size={14} className="animate-spin" style={{ display: 'inline' }} /> 加载中…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {showNew && (
        <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto"
          style={{ background: 'rgba(0,0,0,0.45)' }}
          onClick={() => { if (!creating) setShowNew(false); }}>
          <div className="my-8 w-[480px] max-w-[92vw] rounded-xl border p-5"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)' }}
            onClick={event => event.stopPropagation()}>
            <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>New Experiment</h2>
            <p className="mt-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              创建一条草稿记录，随后可配置参与者、工具集并启动。
            </p>
            <div className="mt-4 flex flex-col gap-3">
              <label className="flex flex-col gap-1 text-xs">
                <span style={{ color: 'var(--color-text-secondary)' }}>Agent</span>
                <select value={newAgentId} onChange={e => { setNewAgentId(e.target.value); setNewVersionA(''); setNewVersionB(''); }}
                  className="rounded-md border px-2 py-1.5 text-xs outline-none" style={inputStyle}>
                  <option value="">选择 agent…</option>
                  {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-xs">
                <span style={{ color: 'var(--color-text-secondary)' }}>版本 1（A）</span>
                <select value={newVersionA} onChange={e => setNewVersionA(e.target.value)} disabled={!newAgentId}
                  className="rounded-md border px-2 py-1.5 text-xs outline-none disabled:opacity-40" style={inputStyle}>
                  <option value="">当前线上配置</option>
                  {newSourceOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-xs">
                <span style={{ color: 'var(--color-text-secondary)' }}>版本 2（B）</span>
                <select value={newVersionB} onChange={e => setNewVersionB(e.target.value)} disabled={!newAgentId}
                  className="rounded-md border px-2 py-1.5 text-xs outline-none disabled:opacity-40" style={inputStyle}>
                  <option value="">与版本 A 相同（稍后编辑副本）</option>
                  {newSourceOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              </label>
            </div>
            <div className="mt-5 flex items-center justify-end gap-2">
              <button type="button" onClick={() => setShowNew(false)} disabled={creating}
                className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer disabled:opacity-40"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                取消
              </button>
              <button type="button" onClick={() => void handleCreate()} disabled={creating || !newAgentId}
                className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                {creating && <Loader2 size={12} className="animate-spin" />} 创建实验
              </button>
            </div>
            {error && (
              <div className="mt-3 text-xs" style={{ color: 'var(--color-error)' }}>{error}</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
