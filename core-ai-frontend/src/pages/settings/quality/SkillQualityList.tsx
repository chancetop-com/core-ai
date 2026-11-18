import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, Lock, RefreshCw } from 'lucide-react';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import { useListState } from '../../../hooks/useListState';
import EnforcementBadge from './EnforcementBadge';

export default function SkillQualityList() {
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [policyRevision, setPolicyRevision] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offset, setOffset] = useListState('skill-quality.offset', 0);
  const [limit, setLimit] = useListState('skill-quality.limit', 20);
  const navigate = useNavigate();

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
    <div>
      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
        <span className="flex items-center gap-1 px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          <Lock size={12} /> Policy revision {policyRevision ?? '-'}
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          {rules.length} rules · {blocking} blocking · {warnings} warn
        </span>
        <button onClick={load} disabled={loading} className="ml-auto flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      <div className="rounded-lg border overflow-x-auto" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
              <th className="px-3 py-3 font-medium">Rule</th>
              <th className="px-3 py-3 font-medium">Category</th>
              <th className="px-3 py-3 font-medium">Enforcement</th>
              <th className="px-3 py-3 font-medium">Requirement</th>
              <th className="px-3 py-3 font-medium">Rev</th>
              <th className="px-3 py-3 font-medium text-right">Open</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={6} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : pageRules.length === 0 ? (
              <tr><td colSpan={6} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No skill quality rules</td></tr>
            ) : pageRules.map(rule => (
              <tr key={rule.key} onClick={() => navigate(`/settings/quality/skill/${encodeURIComponent(rule.key)}`)}
                className="border-t cursor-pointer" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-3">
                  <div className="font-mono text-xs">{rule.key}</div>
                  <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                </td>
                <td className="px-3 py-3 text-xs">{rule.category || '-'}</td>
                <td className="px-3 py-3 text-xs"><EnforcementBadge enforcement={rule.enforcement} /></td>
                <td className="px-3 py-3 text-xs">{rule.requirement || '-'}</td>
                <td className="px-3 py-3 text-xs">{rule.revision}</td>
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
