import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FlaskConical, Loader2, Trash2 } from 'lucide-react';
import {
  agentVersionApi,
  comparisonApi,
  type AgentComparison,
  type AgentVersion,
  type ComparisonVariant,
} from '../../api/comparison';
import type { ToolRef } from '../../api/session';

const inputStyle: React.CSSProperties = {
  borderColor: 'var(--color-border)',
  background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)',
};

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', RUNNING: '进行中', ENDED: '已结束' };
const CHOICE_LABEL: Record<string, string> = { A: '版本 1', B: '版本 2', TIE: '都差不多', NEITHER: '都不好' };
const LIVE_REF = '__live__';
const SAME_AS_A_REF = '__same_as_a__';

function splitIds(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}

function checkedToolIds(variant?: ComparisonVariant): Set<string> {
  return new Set((variant?.effective_tools || []).map(ref => ref.id));
}

/** Detail of one comparison record: configuration (draft/running) plus votes and session pairs. */
export default function AgentComparisonDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<AgentComparison | null>(null);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [participants, setParticipants] = useState('');
  const [budget, setBudget] = useState('');
  const [effA, setEffA] = useState<Set<string>>(new Set());
  const [effB, setEffB] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const applyDetail = useCallback((res: AgentComparison) => {
    setDetail(res);
    setParticipants((res.participant_user_ids || []).join(', '));
    setBudget(res.budget_usd != null ? String(res.budget_usd) : '');
    setEffA(checkedToolIds(res.variant_a));
    setEffB(checkedToolIds(res.variant_b));
  }, []);

  const load = useCallback(async (quiet = false) => {
    if (!id) return;
    if (!quiet) setLoading(true);
    setError(null);
    try {
      const res = await comparisonApi.get(id);
      applyDetail(res);
      if (res.agent_id) {
        agentVersionApi.list(res.agent_id).then(list => setVersions(list.versions || []))
          .catch(err => console.error('load versions failed', err));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [applyDetail, id]);

  useEffect(() => { void load(); }, [load]);

  const run = useCallback(async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [load]);

  const buildToolRefs = useCallback((variant: ComparisonVariant | undefined, checked: Set<string>): ToolRef[] =>
    (variant?.config?.tools || [])
      .filter(ref => checked.has(ref.id))
      .map(ref => ({ id: ref.id, type: ref.type, source: ref.source })), []);

  const savePayload = useCallback(() => ({
    participant_user_ids: splitIds(participants),
    budget_usd: budget.trim() ? Number(budget) : undefined,
    effective_tools_a: detail ? buildToolRefs(detail.variant_a, effA) : undefined,
    effective_tools_b: detail ? buildToolRefs(detail.variant_b, effB) : undefined,
  }), [budget, buildToolRefs, detail, effA, effB, participants]);

  const handleSaveDraft = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, savePayload());
  }), [detail, run, savePayload]);

  const handleStart = useCallback(() => run(async () => {
    if (!detail) return;
    await comparisonApi.update(detail.id, savePayload());
    await comparisonApi.start(detail.id, {
      effective_tools_a: buildToolRefs(detail.variant_a, effA),
      effective_tools_b: buildToolRefs(detail.variant_b, effB),
    });
  }), [buildToolRefs, detail, effA, effB, run, savePayload]);

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

  const handleDelete = useCallback(async () => {
    if (!detail) return;
    if (!window.confirm('删除这条草稿实验记录？')) return;
    setBusy(true);
    setError(null);
    try {
      await comparisonApi.remove(detail.id);
      navigate('/experiments/agent-comparison');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  }, [detail, navigate]);

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

  const sourceOptions = useMemo(() => versions.map(version => ({
    id: version.id,
    label: `${version.label || version.id.slice(0, 8)} · ${version.model || '(default)'}`,
  })), [versions]);

  const detailDraft = detail?.status === 'DRAFT';
  const detailRunning = detail?.status === 'RUNNING';
  const counts = detail?.counts;

  if (loading) {
    return (
      <div className="mx-auto max-w-5xl p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={14} className="animate-spin" style={{ display: 'inline' }} /> 加载中…
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="mx-auto max-w-5xl p-6">
        <button type="button" onClick={() => navigate('/experiments/agent-comparison')}
          className="inline-flex items-center gap-1 text-xs cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
          <ArrowLeft size={13} /> Agent Comparison
        </button>
        <div className="mt-4 rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>
          {error || '实验不存在'}
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-5 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" onClick={() => navigate('/experiments/agent-comparison')}
          className="inline-flex items-center gap-1 text-xs cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
          <ArrowLeft size={13} /> Agent Comparison
        </button>
        <FlaskConical size={16} style={{ color: 'var(--color-primary)' }} />
        <h1 className="text-base font-semibold" style={{ color: 'var(--color-text)' }}>
          {detail.agent_name || detail.agent_id.slice(0, 8)}
        </h1>
        <span className="rounded-full px-2 py-0.5 text-[11px]"
          style={detailRunning
            ? { background: 'var(--color-primary)', color: '#fff' }
            : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          {STATUS_LABEL[detail.status] || detail.status}
        </span>
        {detail.adopted_variant && (
          <span className="text-xs" style={{ color: 'var(--color-success)' }}>已采用版本 {detail.adopted_variant.toUpperCase()}</span>
        )}
        {detail.agent_changed && detail.status !== 'ENDED' && (
          <span className="text-xs" style={{ color: 'var(--color-warning)' }}>线上 agent 已被修改过，采用前会要求确认</span>
        )}
        {busy && <Loader2 size={14} className="animate-spin" />}
        <div className="ml-auto flex items-center gap-2">
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {detail.created_at ? new Date(detail.created_at).toLocaleString() : ''}
          </span>
          {detailDraft && (
            <button type="button" onClick={() => void handleDelete()} disabled={busy}
              className="inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-xs cursor-pointer disabled:opacity-40"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
              <Trash2 size={12} /> 删除实验
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
      )}

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
    </div>
  );
}
