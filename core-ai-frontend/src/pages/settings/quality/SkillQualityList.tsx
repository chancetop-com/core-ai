import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, History, Plus, RefreshCw, Save } from 'lucide-react';
import {
  SKILL_TIER_SCOPES,
  skillQualityApi,
  type QualityPolicy,
  type SkillQualityRule,
  type SkillTierScope,
} from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import { useListState } from '../../../hooks/useListState';
import EnforcementBadge from './EnforcementBadge';

type EnforcementOption = 'BLOCK' | 'WARN' | 'SHADOW';

export default function SkillQualityList() {
  const [scope, setScope] = useListState<SkillTierScope>('skill-quality.scope', 'skill:tier:domain');
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [policy, setPolicy] = useState<QualityPolicy | null>(null);
  const [history, setHistory] = useState<QualityPolicy[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [draftEnforcements, setDraftEnforcements] = useState<Record<string, EnforcementOption>>({});
  const [savingRuleId, setSavingRuleId] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offset, setOffset] = useListState('skill-quality.offset', 0);
  const [limit, setLimit] = useListState('skill-quality.limit', 20);
  const navigate = useNavigate();

  const load = useCallback(async (targetScope: SkillTierScope = scope) => {
    setLoading(true);
    setError('');
    setStatusMessage('');
    try {
      const [rulesRes, policiesRes] = await Promise.all([
        skillQualityApi.rules(targetScope),
        skillQualityApi.policies(targetScope),
      ]);
      const loadedRules = rulesRes.rules || [];
      setRules(loadedRules);
      setPolicy(policiesRes.active);
      setHistory(policiesRes.history || []);
      const initial: Record<string, EnforcementOption> = {};
      for (const r of loadedRules) {
        if (r.enforcement === 'BLOCK' || r.enforcement === 'WARN' || r.enforcement === 'SHADOW') {
          initial[r.id] = r.enforcement;
        }
      }
      setDraftEnforcements(initial);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load skill quality policy');
      setRules([]);
      setPolicy(null);
      setHistory([]);
    } finally {
      setLoading(false);
    }
  }, [scope]);

  useEffect(() => {
    void load(scope);
  }, [scope, load]);

  useEffect(() => {
    if (rules.length === 0) {
      if (offset !== 0) setOffset(0);
    } else if (offset >= rules.length) {
      setOffset(Math.floor((rules.length - 1) / limit) * limit);
    }
  }, [rules.length, offset, limit, setOffset]);

  const handleScopeChange = (nextScope: SkillTierScope) => {
    if (nextScope === scope) return;
    setScope(nextScope);
    setOffset(0);
    setShowHistory(false);
  };

  const handleSaveEnforcement = async (rule: SkillQualityRule, e: React.MouseEvent) => {
    e.stopPropagation();
    const next = draftEnforcements[rule.id];
    if (!next || next === rule.enforcement || !policy) return;
    setSavingRuleId(rule.id);
    setError('');
    setStatusMessage('');
    try {
      await skillQualityApi.activate({
        scope,
        rule_id: rule.id,
        rule_revision: rule.revision ?? 1,
        enforcement: next,
        expected_generation: policy.generation,
        change_reason: `Set ${rule.key} to ${next} for ${scope}`,
      });
      setStatusMessage(`Saved ${rule.key} as ${next} on ${scope}.`);
      await load(scope);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update rule enforcement');
    } finally {
      setSavingRuleId(null);
    }
  };

  const activeScopeMeta = SKILL_TIER_SCOPES.find(item => item.scope === scope) ?? SKILL_TIER_SCOPES[0];
  const pageRules = rules.slice(offset, offset + limit);

  return (
    <div>
      <div className="flex flex-wrap items-center gap-2 mb-4" role="tablist" aria-label="Skill tier scopes">
        {SKILL_TIER_SCOPES.map(item => {
          const active = item.scope === scope;
          return (
            <button
              key={item.scope}
              role="tab"
              aria-selected={active}
              onClick={() => handleScopeChange(item.scope)}
              className="px-3 py-1.5 rounded-lg text-sm font-medium border cursor-pointer transition-colors"
              style={{
                borderColor: active ? 'var(--color-primary)' : 'var(--color-border)',
                background: active ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                color: active ? '#fff' : 'var(--color-text)',
              }}
            >
              {item.label}
            </button>
          );
        })}
        <span className="text-xs ml-2" style={{ color: 'var(--color-text-secondary)' }}>
          {activeScopeMeta.scope} — {activeScopeMeta.description}
        </span>
      </div>

      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}
      {statusMessage && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(16,185,129,0.12)', color: '#34d399' }}>
          {statusMessage}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
        <span className="px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          Active policy revision {policy?.revision ?? '-'} · generation {policy?.generation ?? '-'}
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          {policy?.bindings?.filter(b => b.enforcement === 'BLOCK').length ?? 0} blocking ·{' '}
          {policy?.bindings?.filter(b => b.enforcement === 'WARN').length ?? 0} warn ·{' '}
          {policy?.bindings?.filter(b => b.enforcement === 'SHADOW').length ?? 0} shadow
        </span>
        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={() => setShowHistory(v => !v)}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}
          >
            <History size={14} /> History ({history.length})
          </button>
          <button
            onClick={() => load(scope)}
            disabled={loading}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
            style={{ borderColor: 'var(--color-border)' }}
          >
            <RefreshCw size={14} /> Refresh
          </button>
          <button
            onClick={() => navigate(`/quality/skill/new?scope=${encodeURIComponent(scope)}`)}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm cursor-pointer"
            style={{ background: 'var(--color-primary)', color: 'white' }}
          >
            <Plus size={14} /> New decision rule
          </button>
        </div>
      </div>

      {showHistory && (
        <div className="rounded-lg border p-3 mb-4 text-xs" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="font-medium mb-2">Revision History ({scope})</div>
          {history.length === 0 ? (
            <div style={{ color: 'var(--color-text-secondary)' }}>No revisions recorded.</div>
          ) : (
            <ul className="space-y-1">
              {history.map(item => (
                <li key={item.revision} className="flex items-center justify-between gap-2">
                  <span>
                    rev {item.revision} · gen {item.generation} {item.active ? '(active)' : ''} — {item.change_reason || '-'}
                  </span>
                  <span style={{ color: 'var(--color-text-secondary)' }}>
                    {item.activated_by || 'system'}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

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
            ) : pageRules.map(rule => {
              const selected = draftEnforcements[rule.id] || (rule.enforcement as EnforcementOption) || 'SHADOW';
              const dirty = Boolean(rule.enforcement && selected !== rule.enforcement);
              return (
                <tr
                  key={rule.id || rule.key}
                  onClick={() => navigate(`/quality/skill/${encodeURIComponent(rule.id || rule.key)}?scope=${encodeURIComponent(scope)}`)}
                  className="border-t cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                >
                  <td className="px-3 py-3">
                    <div className="font-mono text-xs">{rule.key}</div>
                    <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                  </td>
                  <td className="px-3 py-3 text-xs">{rule.kind || (rule.built_in ? 'CODE' : 'DECISION')}</td>
                  <td className="px-3 py-3 text-xs">{rule.lifecycle || '-'}</td>
                  <td className="px-3 py-3 text-xs" onClick={e => e.stopPropagation()}>
                    <div className="flex items-center gap-2">
                      <EnforcementBadge enforcement={rule.enforcement} />
                      <select
                        aria-label={`Enforcement for ${rule.key}`}
                        value={selected}
                        onChange={e => setDraftEnforcements(prev => ({ ...prev, [rule.id]: e.target.value as EnforcementOption }))}
                        className="px-2 py-1 rounded border text-xs"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
                      >
                        <option value="BLOCK">BLOCK</option>
                        <option value="WARN">WARN</option>
                        <option value="SHADOW">SHADOW</option>
                      </select>
                      {dirty && (
                        <button
                          onClick={e => handleSaveEnforcement(rule, e)}
                          disabled={savingRuleId === rule.id}
                          className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs text-white cursor-pointer disabled:opacity-50"
                          style={{ background: 'var(--color-primary)' }}
                        >
                          <Save size={12} /> {savingRuleId === rule.id ? 'Saving...' : 'Save'}
                        </button>
                      )}
                    </div>
                  </td>
                  <td className="px-3 py-3 text-xs">{rule.revision}</td>
                  <td className="px-3 py-3 text-xs">{rule.tested || rule.built_in ? 'Yes' : '-'}</td>
                  <td className="px-3 py-3 text-right"><ChevronRight size={14} style={{ color: 'var(--color-text-secondary)' }} /></td>
                </tr>
              );
            })}
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
