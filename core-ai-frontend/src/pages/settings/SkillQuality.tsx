import { useEffect, useState } from 'react';
import { ClipboardCheck, Info, Lock, RefreshCw } from 'lucide-react';
import { skillQualityApi, type SkillQualityRule } from '../../api/quality';
import Pagination from '../../components/Pagination';
import { useListState } from '../../hooks/useListState';

export default function SkillQuality() {
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [policyRevision, setPolicyRevision] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offset, setOffset] = useListState('skill-quality.offset', 0);
  const [limit, setLimit] = useListState('skill-quality.limit', 20);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await skillQualityApi.rules();
      setRules(response.rules || []);
      setPolicyRevision(response.policy_revision ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load skill quality rules');
      setRules([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  useEffect(() => {
    if (rules.length === 0) {
      if (offset !== 0) setOffset(0);
    } else if (offset >= rules.length) {
      setOffset(Math.floor((rules.length - 1) / limit) * limit);
    }
  }, [rules.length, offset, limit, setOffset]);

  const pageRules = rules.slice(offset, offset + limit);
  const blocking = rules.filter(rule => rule.enforcement === 'BLOCK').length;
  const warnings = rules.filter(rule => rule.enforcement === 'WARN').length;

  return (
    <div className="p-6 max-w-7xl">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-xl font-semibold">Skill Quality</h1>
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Skill 发布检查执行的内置规则及其 enforcement。
          </p>
        </div>
        <button onClick={load} disabled={loading} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      <div className="rounded-lg px-3 py-2 mb-4 text-sm flex items-start gap-2" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
        <Info size={14} className="mt-0.5 shrink-0" />
        <div>
          <div style={{ color: 'var(--color-text-primary)' }}>规则由代码定义，修改需发版。</div>
          <div>本页只读：规则、阈值和 enforcement 都随代码发布，不能在此新增或编辑。</div>
        </div>
      </div>

      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      <div className="flex items-center gap-3 mb-4 text-sm">
        <span className="flex items-center gap-1 px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          <Lock size={12} /> Policy revision {policyRevision ?? '-'}
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          {rules.length} rules · {blocking} blocking · {warnings} warn
        </span>
      </div>

      <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-tertiary)' }}>
            <tr>
              <th className="text-left px-3 py-2">Rule</th>
              <th className="text-left px-3 py-2">Category</th>
              <th className="text-left px-3 py-2">Enforcement</th>
              <th className="text-left px-3 py-2">Requirement</th>
              <th className="text-left px-3 py-2">Note</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={5} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td>
              </tr>
            ) : pageRules.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                  {error ? 'Rules are unavailable.' : 'No rules defined.'}
                </td>
              </tr>
            ) : pageRules.map(rule => (
              <tr key={rule.key} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-2">
                  <div className="font-mono text-xs">{rule.key}</div>
                  <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                </td>
                <td className="px-3 py-2 text-xs">{rule.category || '-'}</td>
                <td className="px-3 py-2 text-xs">
                  <span className="px-1.5 py-0.5 rounded" style={{
                    background: rule.enforcement === 'BLOCK' ? 'rgba(220,38,38,0.12)' : rule.enforcement === 'WARN' ? 'rgba(217,119,6,0.12)' : 'var(--color-bg-tertiary)',
                    color: rule.enforcement === 'BLOCK' ? 'var(--color-error)' : 'var(--color-text-secondary)',
                  }}>{rule.enforcement || '-'}</span>
                </td>
                <td className="px-3 py-2 text-xs">{rule.requirement || '-'}</td>
                <td className="px-3 py-2 text-xs whitespace-nowrap" style={{ color: 'var(--color-text-secondary)' }}>
                  <ClipboardCheck size={12} className="inline mr-1" />Code · rev {rule.revision}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {rules.length > 0 && (
        <Pagination
          offset={offset}
          limit={limit}
          total={rules.length}
          shown={pageRules.length}
          onOffsetChange={setOffset}
          onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
        />
      )}
    </div>
  );
}
