import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, GitCompare, Loader2, Plus, RefreshCw } from 'lucide-react';
import { comparisonApi } from '../../api/comparison';
import type { AgentComparison, ComparisonVariant, ComparisonVariantConfig } from '../../api/comparison';
import type { ToolRef } from '../../api/session';

const inputStyle: React.CSSProperties = {
  background: 'var(--color-bg-tertiary)',
  borderColor: 'var(--color-border)',
  color: 'var(--color-text)',
};

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', RUNNING: '进行中', ENDED: '已结束' };
const CHOICE_LABEL: Record<string, string> = { A: '版本 1', B: '版本 2', TIE: '都差不多', NEITHER: '都不好' };

function splitIds(value: string): string[] {
  return value.split(',').map(s => s.trim()).filter(Boolean);
}

function toolKey(ref: ToolRef): string {
  return ref.id;
}

interface VariantFormState {
  model: string;
  thinkingEffort: string;
  temperature: string;
  systemPrompt: string;
  enableMemory: boolean;
  skillIds: string;
  subAgentIds: string;
}

function formFromConfig(config?: ComparisonVariantConfig): VariantFormState {
  return {
    model: config?.model || '',
    thinkingEffort: config?.thinking_effort || '',
    temperature: config?.temperature != null ? String(config.temperature) : '',
    systemPrompt: config?.system_prompt || '',
    enableMemory: config?.enable_memory === true,
    skillIds: (config?.skill_ids || []).join(', '),
    subAgentIds: (config?.subagent_ids || []).join(', '),
  };
}

function checkedToolIds(variant?: ComparisonVariant): Set<string> {
  const source = variant?.effective_tools;
  // an explicit empty list means "no tools confirmed" and must stay empty; only a missing list
  // falls back to the builtin-only suggestion the server uses for a freshly created draft
  if (source) return new Set(source.map(toolKey));
  return new Set((variant?.config?.tools || []).filter(ref => ref.type === 'BUILTIN').map(toolKey));
}

export default function AgentComparisonPanel({ agentId }: { agentId: string }) {
  const [comparisons, setComparisons] = useState<AgentComparison[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AgentComparison | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(true);

  // draft edits
  const [participants, setParticipants] = useState('');
  const [budget, setBudget] = useState('');
  const [form, setForm] = useState<VariantFormState>(formFromConfig(undefined));
  const [effA, setEffA] = useState<Set<string>>(new Set());
  const [effB, setEffB] = useState<Set<string>>(new Set());

  const refreshList = useCallback(async () => {
    if (!agentId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await comparisonApi.list(agentId);
      setComparisons(res.comparisons || []);
      setSelectedId(prev => prev ?? res.comparisons?.[0]?.id ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => { void refreshList(); }, [refreshList]);

  useEffect(() => {
    if (!selectedId) { setDetail(null); return; }
    let alive = true;
    comparisonApi.get(selectedId)
      .then(res => {
        if (!alive) return;
        setDetail(res);
        setParticipants((res.participant_user_ids || []).join(', '));
        setBudget(res.budget_usd != null ? String(res.budget_usd) : '');
        setForm(formFromConfig(res.variant_b?.config));
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
      if (selectedId) {
        const res = await comparisonApi.get(selectedId);
        setDetail(res);
        setEffA(checkedToolIds(res.variant_a));
        setEffB(checkedToolIds(res.variant_b));
      }
      await refreshList();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [refreshList, selectedId]);

  const handleCreate = useCallback(() => run(async () => {
    const created = await comparisonApi.create(agentId);
    setSelectedId(created.id);
  }), [agentId, run]);

  const buildToolRefs = useCallback((variant: ComparisonVariant | undefined, checked: Set<string>): ToolRef[] => {
    return (variant?.config?.tools || [])
      .filter(ref => checked.has(toolKey(ref)))
      .map(ref => ({ id: ref.id, type: ref.type, source: ref.source }));
  }, []);

  // RUNNING comparisons only accept participants/budget: variants and the tool whitelist are frozen
  const buildPatch = useCallback((includeVariants: boolean) => ({
    participant_user_ids: splitIds(participants),
    budget_usd: budget.trim() ? Number(budget) : undefined,
    ...(includeVariants && detail ? {
      variant_b: {
        model: form.model || undefined,
        thinking_effort: form.thinkingEffort || undefined,
        temperature: form.temperature.trim() ? Number(form.temperature) : undefined,
        system_prompt: form.systemPrompt,
        enable_memory: form.enableMemory,
        skill_ids: splitIds(form.skillIds),
        subagent_ids: splitIds(form.subAgentIds),
      },
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    } : {}),
  }), [budget, buildToolRefs, detail, effA, effB, form, participants]);

  const handleSaveDraft = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, buildPatch(detail.status === 'DRAFT'));
  }), [buildPatch, detail, run]);

  const handleStart = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, buildPatch(true));
    await comparisonApi.start(detail.id, {
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    });
  }), [buildPatch, buildToolRefs, detail, effA, effB, run]);

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
      return <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>该变体未配置工具。</p>;
    }
    return (
      <div className="flex flex-col gap-1">
        {tools.map(ref => (
          <label key={toolKey(ref)} className="flex items-center gap-2 text-xs cursor-pointer">
            <input
              type="checkbox"
              checked={checked.has(toolKey(ref))}
              disabled={disabled}
              onChange={e => {
                const next = new Set(checked);
                if (e.target.checked) next.add(toolKey(ref));
                else next.delete(toolKey(ref));
                setChecked(next);
              }}
            />
            <code className="font-mono" style={{ color: 'var(--color-text)' }}>{ref.id}</code>
            <span className="rounded px-1.5 py-0.5 text-[10px]"
              style={{ background: 'var(--color-bg-tertiary)', color: ref.type === 'BUILTIN' ? 'var(--color-success)' : 'var(--color-warning)' }}>
              {ref.type || '?'}
            </span>
            {ref.type !== 'BUILTIN' && !checked.has(toolKey(ref)) && (
              <span className="text-[10px]" style={{ color: 'var(--color-text-muted)' }}>外部写工具默认不勾选</span>
            )}
          </label>
        ))}
      </div>
    );
  };

  const draft = detail?.status === 'DRAFT';
  const running = detail?.status === 'RUNNING';
  const counts = detail?.counts;

  return (
    <div className="rounded-xl border p-4"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between mb-3">
        <button type="button" onClick={() => setExpanded(v => !v)} className="flex items-center gap-2 cursor-pointer" style={{ color: 'var(--color-text)' }}>
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          <GitCompare size={14} />
          <h3 className="font-medium text-sm">版本对比测试</h3>
        </button>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => void refreshList()} disabled={busy || loading}
            className="inline-flex items-center gap-1 text-xs px-2 py-1 rounded cursor-pointer"
            style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
            <RefreshCw size={11} className={loading ? 'animate-spin' : ''} /> Refresh
          </button>
          <button type="button" onClick={() => void handleCreate()} disabled={busy}
            className="inline-flex items-center gap-1 text-xs px-2 py-1 rounded cursor-pointer text-white"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={11} /> 新建对比
          </button>
        </div>
      </div>

      {!expanded ? null : (
        <>
          {error && (
            <div className="mb-3 rounded-lg border px-3 py-2 text-xs" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>
              {error}
            </div>
          )}
          <div className="mb-3 flex flex-wrap gap-2">
            {comparisons.length === 0 && !loading && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                还没有对比测试。新建后编辑候选版本（B）、确认参与者与工具集，再启动。
              </span>
            )}
            {comparisons.map(item => (
              <button key={item.id} type="button" onClick={() => setSelectedId(item.id)}
                className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer"
                style={{
                  borderColor: selectedId === item.id ? 'var(--color-primary)' : 'var(--color-border)',
                  color: 'var(--color-text)',
                  background: selectedId === item.id ? 'var(--color-bg-tertiary)' : 'transparent',
                }}>
                {STATUS_LABEL[item.status] || item.status} · {item.created_at ? new Date(item.created_at).toLocaleString() : item.id.slice(0, 8)}
              </button>
            ))}
          </div>

          {!detail && (
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>选择或新建一个对比测试。</p>
          )}

          {detail && (
            <div className="flex flex-col gap-4">
              <div className="grid grid-cols-2 gap-4">
                {(['a', 'b'] as const).map(which => {
                  const variant = which === 'a' ? detail.variant_a : detail.variant_b;
                  return (
                    <div key={which} className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
                      <div className="mb-2 flex items-center gap-2">
                        <span className="text-xs font-semibold" style={{ color: 'var(--color-text)' }}>
                          {variant?.label || which.toUpperCase()}
                        </span>
                        {draft && which === 'b' && <span className="text-[10px]" style={{ color: 'var(--color-text-muted)' }}>可编辑</span>}
                      </div>
                      <div className="mb-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        <div>model: <code className="font-mono">{variant?.model || '(default)'}</code></div>
                        {variant?.summary && <div className="mt-1">差异: {variant.summary}</div>}
                      </div>

                      {draft && which === 'b' ? (
                        <div className="flex flex-col gap-2">
                          <input value={form.model} onChange={e => setForm(f => ({ ...f, model: e.target.value }))}
                            placeholder="model（留空 = 不变）" className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                          <div className="flex gap-2">
                            <input value={form.temperature} onChange={e => setForm(f => ({ ...f, temperature: e.target.value }))}
                              placeholder="temperature" className="w-24 rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                            <select value={form.thinkingEffort} onChange={e => setForm(f => ({ ...f, thinkingEffort: e.target.value }))}
                              className="flex-1 rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
                              <option value="">thinking 不变</option>
                              <option value="none">none</option>
                              <option value="low">low</option>
                              <option value="high">high</option>
                              <option value="max">max</option>
                            </select>
                          </div>
                          <textarea value={form.systemPrompt} onChange={e => setForm(f => ({ ...f, systemPrompt: e.target.value }))}
                            rows={3} placeholder="system prompt（留空 = 不变）"
                            className="rounded-md border px-2 py-1 text-xs outline-none resize-y" style={inputStyle} />
                          <input value={form.skillIds} onChange={e => setForm(f => ({ ...f, skillIds: e.target.value }))}
                            placeholder="skill ids（逗号分隔）" className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                          <input value={form.subAgentIds} onChange={e => setForm(f => ({ ...f, subAgentIds: e.target.value }))}
                            placeholder="sub-agent ids（逗号分隔）" className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                          <label className="flex items-center gap-2 text-xs cursor-pointer">
                            <input type="checkbox" checked={form.enableMemory} onChange={e => setForm(f => ({ ...f, enableMemory: e.target.checked }))} />
                            enable memory（对比会话实际总是关闭记忆读写）
                          </label>
                        </div>
                      ) : (
                        <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                          <div>tools: {(variant?.effective_tools || []).length} 个已确认</div>
                          <div>skills: {(variant?.config?.skill_ids || []).length} 个</div>
                        </div>
                      )}

                      <div className="mt-3">
                        <div className="mb-1 text-[11px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                          生效工具集（白名单外的工具不会被装配）
                        </div>
                        {renderToolList(variant, which === 'a' ? effA : effB, which === 'a' ? setEffA : setEffB, !draft)}
                      </div>
                    </div>
                  );
                })}
              </div>

              <div className="grid grid-cols-2 gap-4">
                <label className="flex flex-col gap-1 text-xs">
                  <span style={{ color: 'var(--color-text-secondary)' }}>参与者（逗号分隔的用户邮箱/ID，创建者自动在内）</span>
                  <input value={participants} onChange={e => setParticipants(e.target.value)} disabled={!draft && !running}
                    className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                </label>
                <label className="flex flex-col gap-1 text-xs">
                  <span style={{ color: 'var(--color-text-secondary)' }}>预算 USD（软提醒）</span>
                  <input value={budget} onChange={e => setBudget(e.target.value)} disabled={detail.status === 'ENDED'}
                    className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
                </label>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {draft && (
                  <>
                    <button type="button" onClick={() => void handleSaveDraft()} disabled={busy}
                      className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                      保存草稿
                    </button>
                    <button type="button" onClick={() => void handleStart()} disabled={busy}
                      className="inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer" style={{ background: 'var(--color-success)' }}>
                      {busy && <Loader2 size={12} className="animate-spin" />} 启动测试
                    </button>
                  </>
                )}
                {running && (
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
                {detail.agent_changed && detail.status !== 'ENDED' && (
                  <span className="text-xs" style={{ color: 'var(--color-warning)' }}>线上 agent 已被修改过，采用前会要求确认</span>
                )}
              </div>

              {counts && (
                <div className="rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="mb-2 text-xs font-medium" style={{ color: 'var(--color-text)' }}>
                    统计：版本1 {counts.votes_a ?? 0} · 版本2 {counts.votes_b ?? 0} · 平 {counts.ties ?? 0} · 差 {counts.neithers ?? 0} · 共 {counts.votes ?? 0} 票 / {counts.turns ?? 0} 轮
                  </div>
                  <div className="max-h-48 overflow-y-auto">
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
                  </div>
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
            </div>
          )}
        </>
      )}
    </div>
  );
}
