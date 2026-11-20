import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, Plus, RefreshCw } from 'lucide-react';
import { skillQualityApi, type QualityPolicy, type SkillQualityRule } from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import { useListState } from '../../../hooks/useListState';
import EnforcementBadge from './EnforcementBadge';

export default function SkillQualityList() {
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [policy, setPolicy] = useState<QualityPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offset, setOffset] = useListState('skill-quality.offset', 0);
  const [limit, setLimit] = useListState('skill-quality.limit', 20);
  const navigate = useNavigate();

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [rulesRes, policiesRes] = await Promise.all([skillQualityApi.rules(), skillQualityApi.policies()]);
      setRules(rulesRes.rules || []);
      setPolicy(policiesRes.active);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load skill quality policy');
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

  return (
    <div>
      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
        <span className="px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          Active policy revision {policy?.revision ?? '-'} · generation {policy?.generation ?? '-'}
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          {policy?.bindings?.filter(b => b.enforcement === 'BLOCK').length ?? 0} blocking · {policy?.bindings?.filter(b => b.enforcement === 'SHADOW').length ?? 0} shadow
        </span>
        <div className="ml-auto flex items-center gap-2">
          <button onClick={load} disabled={loading} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button onClick={() => navigate('/quality/skill/new')} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm cursor-pointer" style={{ background: 'var(--color-primary)', color: 'white' }}>
            <Plus size={14} /> New decision rule
          </button>
        </div>
      </div>

      <div className="rounded-lg border overflow-x-auto" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
              <th className="px-3 py-3 font-medium">Rule</th>
              <th className="px-3 py-3 font-medium">Kind</th>
              <th className="px-3 py-3 font-medium">Lifecycle</th>
              <th className="px-3 py-3 font-medium">Enforcement</th>
              <th className="px-3 py-3 font-medium">Rev</th>
              <th className="px-3 py-3 font-medium">Tested</th>
              <th className="px-3 py-3 font-medium text-right">Open</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={7} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : pageRules.length === 0 ? (
              <tr><td colSpan={7} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No skill quality rules</td></tr>
            ) : pageRules.map(rule => (
              <tr key={rule.id || rule.key} onClick={() => navigate(`/quality/skill/${encodeURIComponent(rule.id || rule.key)}`)}
                className="border-t cursor-pointer" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-3">
                  <div className="font-mono text-xs">{rule.key}</div>
                  <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                </td>
                <td className="px-3 py-3 text-xs">{rule.kind || (rule.built_in ? 'CODE' : 'DECISION')}</td>
                <td className="px-3 py-3 text-xs">{rule.lifecycle || '-'}</td>
                <td className="px-3 py-3 text-xs"><EnforcementBadge enforcement={rule.enforcement} /></td>
                <td className="px-3 py-3 text-xs">{rule.revision}</td>
                <td className="px-3 py-3 text-xs">{rule.tested || rule.built_in ? 'Yes' : '-'}</td>
                <td className="px-3 py-3 text-right"><ChevronRight size={14} style={{ color: 'var(--color-text-secondary)' }} /></td>
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
