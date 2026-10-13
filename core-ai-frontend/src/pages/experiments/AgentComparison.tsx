import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, FlaskConical, GitCompare, Loader2, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import { agentVersionApi, comparisonApi } from '../../api/comparison';
import type { AgentComparison, AgentVersion, ComparisonVariant } from '../../api/comparison';
import type { ToolRef } from '../../api/session';

const inputStyle: React.CSSProperties = {
  background: 'var(--color-bg-tertiary)',
  borderColor: 'var(--color-border)',
  color: 'var(--color-text)',
};

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', RUNNING: '进行中', ENDED: '已结束' };
const CHOICE_LABEL: Record<string, string> = { A: '版本 1', B: '版本 2', TIE: '都差不多', NEITHER: '都不好' };
const LIVE_REF = '__live__';
const SAME_AS_A_REF = '__same_as_a__';

function splitIds(value: string): string[] {
  return value.split(',').map(s => s.trim()).filter(Boolean);
}

function checkedToolIds(variant?: ComparisonVariant): Set<string> {
  if (variant?.effective_tools) return new Set(variant.effective_tools.map(ref => ref.id));
  return new Set((variant?.config?.tools || []).filter(ref => ref.type === 'BUILTIN').map(ref => ref.id));
}

export default function AgentComparison() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [agentId, setAgentId] = useState<string>(() => searchParams.get('agent') || localStorage.getItem('comparison_agentId') || '');
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [comparisons, setComparisons] = useState<AgentComparison[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AgentComparison | null>(null);
  const [participants, setParticipants] = useState('');
  const [budget, setBudget] = useState('');
  const [effA, setEffA] = useState<Set<string>>(new Set());
  const [effB, setEffB] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.agents.list(true).then(res => {
      const chatAgents = (res.agents || []).filter(a => a.type !== 'LLM_CALL');
      setAgents(chatAgents);
      setAgentId(prev => prev || chatAgents[0]?.id || '');
    }).catch(err => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  useEffect(() => {
    if (!agentId) return;
    localStorage.setItem('comparison_agentId', agentId);
    if (searchParams.get('agent') !== agentId) setSearchParams({ agent: agentId }, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  const refresh = useCallback(async (keepSelection = true) => {
    if (!agentId) return;
    setBusy(true);
    setError(null);
    try {
      const [versionRes, comparisonRes] = await Promise.all([
        agentVersionApi.list(agentId),
        comparisonApi.list(agentId),
      ]);
      setVersions(versionRes.versions || []);
      setComparisons(comparisonRes.comparisons || []);
      if (!keepSelection) setSelectedId(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [agentId]);

  useEffect(() => { void refresh(false); }, [refresh]);

  useEffect(() => {
    if (!selectedId) { setDetail(null); return; }
    let alive = true;
    comparisonApi.get(selectedId)
      .then(res => {
        if (!alive) return;
        setDetail(res);
        setParticipants((res.participant_user_ids || []).join(', '));
        setBudget(res.budget_usd != null ? String(res.budget_usd) : '');
        setEffA(checkedToolIds(res.variant_a));
        setEffB(checkedToolIds(res.variant_b));
      })
      .catch(err => { if (alive) setError(err instanceof Error ? err.message : String(err)); });
    return () => { alive = false; };
  }, [selectedId]);

  const run = useCallback(async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await refresh();
      if (selectedId) {
        const res = await comparisonApi.get(selectedId);
        setDetail(res);
        setEffA(checkedToolIds(res.variant_a));
        setEffB(checkedToolIds(res.variant_b));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [refresh, selectedId]);

  const handleNewVersion = useCallback(() => run(async () => {
    const version = await agentVersionApi.create({ agent_id: agentId });
    navigate(`/experiments/agent-comparison/versions/${version.id}`);
  }), [agentId, navigate, run]);

  const handleDeleteVersion = useCallback(async (version: AgentVersion) => {
    if (!window.confirm(`删除版本 ${version.label || version.id.slice(0, 8)}？`)) return;
    setBusy(true);
    setError(null);
    try {
      await agentVersionApi.remove(version.id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const handleNewExperiment = useCallback(() => run(async () => {
    if (versions.length === 0) throw new Error('先创建一个版本，再建实验');
    const created = await comparisonApi.create(agentId, { versionAId: null, versionBId: versions[0].id });
    setSelectedId(created.id);
  }), [agentId, run, versions]);

  const detailDraft = detail?.status === 'DRAFT';
  const detailRunning = detail?.status === 'RUNNING';

  const buildToolRefs = useCallback((variant: ComparisonVariant | undefined, checked: Set<string>): ToolRef[] =>
    (variant?.config?.tools || [])
      .filter(ref => checked.has(ref.id))
      .map(ref => ({ id: ref.id, type: ref.type, source: ref.source })), []);

  const handleSaveDraft = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, {
      participant_user_ids: splitIds(participants),
      budget_usd: budget.trim() ? Number(budget) : undefined,
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    });
  }), [buildToolRefs, budget, detail, effA, effB, participants, run]);

  const handleStart = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, {
      participant_user_ids: splitIds(participants),
      budget_usd: budget.trim() ? Number(budget) : undefined,
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    });
    await comparisonApi.start(detail.id, {
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    });
  }), [buildToolRefs, budget, detail, effA, effB, participants, run]);

  const handleEnd = useCallback((adopt: 'a' | 'b' | null) => run(async () => {
    if (!detail) return;
    try {
      await comparisonApi.end(detail.id, { adopt });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      if (message.includes('modified after')) {
        if (!window.confirm('线上 agent 在测试开始后被修改过，采用变体会覆盖这些改动。继续？')) throw err;
        await comparisonApi.end(detail.id, { adopt, force: true });
        return;
      }
      throw err;
    }
  }), [detail, run]);

  const renderToolList = (variant: ComparisonVariant | undefined, checked: Set<string>, setChecked: (next: Set<string>) => void, disabled: boolean) => {
    const tools = variant?.config?.tools || [];
    if (tools.length === 0) {
      return <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>该版本未配置工具。</p>;
    }
    return (
      <div className="flex flex-col gap-1">
        {tools.map(ref => (
          <label key={ref.id} className="flex items-center gap-2 text-xs cursor-pointer">
            <input
              type="checkbox"
              checked={checked.has(ref.id)}
              disabled={disabled}
              onChange={e => {
                const next = new Set(checked);
                if (e.target.checked) next.add(ref.id);
                else next.delete(ref.id);
                setChecked(next);
              }}
            />
            <code className="font-mono" style={{ color: 'var(--color-text)' }}>{ref.id}</code>
            <span className="rounded px-1.5 py-0.5 text-[10px]"
              style={{ background: 'var(--color-bg-tertiary)', color: ref.type === 'BUILTIN' ? 'var(--color-success)' : 'var(--color-warning)' }}>
              {ref.type || '?'}
            </span>
            {ref.type !== 'BUILTIN' && !checked.has(ref.id) && (
              <span className="text-[10px]" style={{ color: 'var(--color-text-muted)' }}>外部写工具默认不勾选</span>
            )}
          </label>
        ))}
      </div>
    );
  };

  const sourceOptions = useMemo(() => versions.map(v => ({ id: v.id, label: `${v.label || v.id.slice(0, 8)} · ${v.model || ''}` })), [versions]);

  const counts = detail?.counts;

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <FlaskConical size={20} style={{ color: 'var(--color-primary)' }} />
        <h1 className="text-lg font-semibold" style={{ color: 'var(--color-text)' }}>Agent Comparison</h1>
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          用真实用户对同一个 agent 的两个版本做 A/B：每轮双跑、逐轮投票、胜者走发布流程上线
        </span>
        <div className="ml-auto flex items-center gap-2">
          <select value={agentId} onChange={e => setAgentId(e.target.value)}
            className="rounded-lg border px-3 py-1.5 text-sm outline-none" style={inputStyle}>
            <option value="">选择 agent…</option>
            {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
          </select>
          <button type="button" onClick={() => void refresh()} disabled={busy || !agentId}
            className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs cursor-pointer disabled:opacity-40"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            <RefreshCw size={12} className={busy ? 'animate-spin' : ''} /> 刷新
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
      )}

      <div className="grid grid-cols-2 gap-6">
        <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>版本（可编辑、可作为实验的一方）</h2>
            <button type="button" onClick={() => void handleNewVersion()} disabled={busy || !agentId}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              <Plus size={12} /> New Version
            </button>
          </div>
          {versions.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              还没有版本。新建版本会复制当前线上配置，然后你可以完整地改配置并保存（不影响线上，不含发布）。
            </p>
          )}
          <div className="flex flex-col gap-2">
            {versions.map(version => (
              <div key={version.id} className="flex items-center gap-3 rounded-lg border px-3 py-2" style={{ borderColor: 'var(--color-border)' }}>
                <div className="min-w-0 flex-1">
                  <div className="text-sm" style={{ color: 'var(--color-text)' }}>{version.label || version.id.slice(0, 8)}</div>
                  <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    model: <code className="font-mono">{version.model || '(default)'}</code> · {version.updated_at ? new Date(version.updated_at).toLocaleString() : ''}
                  </div>
                </div>
                <button type="button" onClick={() => navigate(`/experiments/agent-comparison/versions/${version.id}`)}
                  className="rounded-lg border px-2.5 py-1 text-xs cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  编辑
                </button>
                <button type="button" onClick={() => void handleDeleteVersion(version)} disabled={busy}
                  className="rounded-lg border px-2.5 py-1 text-xs cursor-pointer disabled:opacity-40"
                  style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
                  <Trash2 size={12} />
                </button>
              </div>
            ))}
          </div>
        </section>

        <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>实验（对比记录与数据）</h2>
            <button type="button" onClick={() => void handleNewExperiment()} disabled={busy || !agentId}
              className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-success)' }}>
              <Plus size={12} /> New Experiment
            </button>
          </div>
          {comparisons.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              还没有实验。选择一个版本作为候选（版本 2），配置参与者与工具集后启动。
            </p>
          )}
          <div className="flex flex-col gap-2">
            {comparisons.map(item => (
              <button key={item.id} type="button" onClick={() => setSelectedId(selectedId === item.id ? null : item.id)}
                className="flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left cursor-pointer"
                style={{
                  borderColor: selectedId === item.id ? 'var(--color-primary)' : 'var(--color-border)',
                  background: selectedId === item.id ? 'var(--color-bg-tertiary)' : 'transparent',
                }}>
                {selectedId === item.id ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                <span className="rounded-full px-2 py-0.5 text-[11px]"
                  style={{
                    background: item.status === 'RUNNING' ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                    color: item.status === 'RUNNING' ? 'white' : 'var(--color-text-secondary)',
                  }}>
                  {STATUS_LABEL[item.status] || item.status}
                </span>
                <span className="min-w-0 flex-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {item.variant_a?.model || '?'} vs {item.variant_b?.model || '?'} · {item.created_at ? new Date(item.created_at).toLocaleString() : ''}
                  {item.adopted_variant ? ` · 已采用 ${item.adopted_variant.toUpperCase()}` : ''}
                </span>
                <GitCompare size={13} style={{ color: 'var(--color-text-muted)' }} />
              </button>
            ))}
          </div>
        </section>
      </div>

      {detail && (
        <section className="flex flex-col gap-4 rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>实验配置与数据</h2>
            <span className="rounded-full px-2 py-0.5 text-[11px]"
              style={{ background: detailRunning ? 'var(--color-primary)' : 'var(--color-bg-tertiary)', color: detailRunning ? 'white' : 'var(--color-text-secondary)' }}>
              {STATUS_LABEL[detail.status] || detail.status}
            </span>
            {detail.agent_changed && detail.status !== 'ENDED' && (
              <span className="text-xs" style={{ color: 'var(--color-warning)' }}>线上 agent 已被修改过，采用前会要求确认</span>
            )}
            {busy && <Loader2 size={14} className="animate-spin" />}
          </div>

          {detailDraft && (
            <div className="grid grid-cols-2 gap-4">
              <label className="flex flex-col gap-1 text-xs">
                <span style={{ color: 'var(--color-text-secondary)' }}>版本 1（A）来源</span>
                <select value={detail.version_a_id || LIVE_REF} disabled={busy}
                  onChange={e => {
                    const value = e.target.value;
                    void run(async () => {
                      await comparisonApi.update(detail.id, { version_a_id: value === LIVE_REF ? '' : value });
                    });
                  }}
                  className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
                  <option value={LIVE_REF}>当前线上配置</option>
                  {sourceOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-xs">
                <span style={{ color: 'var(--color-text-secondary)' }}>版本 2（B）来源</span>
                <select value={detail.version_b_id || SAME_AS_A_REF} disabled={busy}
                  onChange={e => {
                    const value = e.target.value;
                    void run(async () => {
                      await comparisonApi.update(detail.id, { version_b_id: value === SAME_AS_A_REF ? '' : value });
                    });
                  }}
                  className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
                  <option value={SAME_AS_A_REF}>与版本 A 相同（副本）</option>
                  {sourceOptions.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
              </label>
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <label className="flex flex-col gap-1 text-xs">
              <span style={{ color: 'var(--color-text-secondary)' }}>参与者（逗号分隔的用户邮箱/ID，创建者自动在内）</span>
              <input value={participants} onChange={e => setParticipants(e.target.value)} disabled={!detailDraft && !detailRunning}
                className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span style={{ color: 'var(--color-text-secondary)' }}>预算 USD（软提醒）</span>
              <input value={budget} onChange={e => setBudget(e.target.value)} disabled={detail.status === 'ENDED'}
                className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
            </label>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {(['a', 'b'] as const).map(which => {
              const variant = which === 'a' ? detail.variant_a : detail.variant_b;
              return (
                <div key={which} className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="mb-1 text-xs font-semibold" style={{ color: 'var(--color-text)' }}>
                    {variant?.label || which.toUpperCase()}
                    {which === 'a' ? (detail.version_a_id ? '（版本）' : '（当前线上）') : (detail.version_b_id ? '（版本）' : '（A 副本）')}
                  </div>
                  <div className="mb-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    model: <code className="font-mono">{variant?.model || '(default)'}</code>
                    {variant?.summary && <div className="mt-1">差异: {variant.summary}</div>}
                  </div>
                  <div className="mb-1 text-[11px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                    生效工具集（白名单外的工具不会被装配）
                  </div>
                  {renderToolList(variant, which === 'a' ? effA : effB, which === 'a' ? setEffA : setEffB, !detailDraft)}
                </div>
              );
            })}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {detailDraft && (
              <>
                <button type="button" onClick={() => void handleSaveDraft()} disabled={busy}
                  className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  保存配置
                </button>
                <button type="button" onClick={() => void handleStart()} disabled={busy}
                  className="rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer" style={{ background: 'var(--color-success)' }}>
                  启动测试
                </button>
              </>
            )}
            {detailRunning && (
              <>
                <button type="button" onClick={() => void handleSaveDraft()} disabled={busy}
                  className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  保存参与者/预算
                </button>
                <button type="button" onClick={() => void handleEnd('a')} disabled={busy}
                  className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  结束并采用版本 A
                </button>
                <button type="button" onClick={() => void handleEnd('b')} disabled={busy}
                  className="rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer" style={{ background: 'var(--color-primary)' }}>
                  结束并采用版本 B
                </button>
                <button type="button" onClick={() => void handleEnd(null)} disabled={busy}
                  className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                  结束（不采用）
                </button>
              </>
            )}
            {detail.status === 'ENDED' && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {detail.adopted_variant ? `已采用版本 ${detail.adopted_variant.toUpperCase()}` : '已结束（未采用任何版本）'}
              </span>
            )}
          </div>

          {counts && (
            <div className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
              <div className="mb-2 text-xs font-medium" style={{ color: 'var(--color-text)' }}>
                统计：版本1 {counts.votes_a ?? 0} · 版本2 {counts.votes_b ?? 0} · 平 {counts.ties ?? 0} · 差 {counts.neithers ?? 0} · 共 {counts.votes ?? 0} 票 / {counts.turns ?? 0} 轮
              </div>
              {(detail.votes || []).length === 0 && (
                <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>还没有投票。</p>
              )}
              {(detail.votes || []).map(vote => (
                <div key={vote.id} className="flex items-center gap-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  <span>第 {vote.turn} 轮</span>
                  <span style={{ color: 'var(--color-text)' }}>{CHOICE_LABEL[vote.chosen] || vote.chosen}</span>
                  <span>{vote.user_id}</span>
                  {vote.comment && <span className="truncate">“{vote.comment}”</span>}
                </div>
              ))}
              {(detail.pairs || []).length > 0 && (
                <div className="mt-2 text-[11px]" style={{ color: 'var(--color-text-muted)' }}>
                  {detail.pairs!.map(pair => (
                    <div key={pair.pair_id}>
                      会话对 {pair.user_id}：{pair.turn_count ?? 0} 轮 / {pair.vote_count ?? 0} 票
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
