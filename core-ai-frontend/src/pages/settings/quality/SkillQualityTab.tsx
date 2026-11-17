import { useEffect, useState } from 'react';
import { Info, Lock, RefreshCw } from 'lucide-react';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import ToggleSwitch from '../../../components/ToggleSwitch';
import { useListState } from '../../../hooks/useListState';
import EnforcementBadge from './EnforcementBadge';

export default function SkillQualityTab() {
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [policyRevision, setPolicyRevision] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
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
  const selected = rules.find(rule => rule.key === selectedKey) || null;
  const blocking = rules.filter(rule => rule.enforcement === 'BLOCK').length;
  const warnings = rules.filter(rule => rule.enforcement === 'WARN').length;

  return (
    <div>
      <div className="rounded-lg px-3 py-2 mb-4 text-sm flex items-start gap-2" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
        <Info size={14} className="mt-0.5 shrink-0" />
        <div>
          <div style={{ color: 'var(--color-text-primary)' }}>规则由代码定义，修改需发版。</div>
          <div>规则、阈值和 enforcement 都随代码发布，不能在此新增或编辑。</div>
        </div>
      </div>

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

      <div className="grid grid-cols-1 gap-4 min-[1400px]:grid-cols-[minmax(0,1fr)_460px]">
        <div>
          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-tertiary)' }}>
                <tr>
                  <th className="text-left px-3 py-2">Rule</th>
                  <th className="text-left px-3 py-2">Category</th>
                  <th className="text-left px-3 py-2">Enforcement</th>
                  <th className="text-left px-3 py-2">Requirement</th>
                  <th className="text-left px-3 py-2">Rev</th>
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
                  <tr key={rule.key} onClick={() => setSelectedKey(rule.key)} className="border-t cursor-pointer"
                    style={{ borderColor: 'var(--color-border)', background: selected?.key === rule.key ? 'var(--color-primary-bg)' : undefined }}>
                    <td className="px-3 py-2">
                      <div className="font-mono text-xs">{rule.key}</div>
                      <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                    </td>
                    <td className="px-3 py-2 text-xs">{rule.category || '-'}</td>
                    <td className="px-3 py-2 text-xs"><EnforcementBadge enforcement={rule.enforcement} /></td>
                    <td className="px-3 py-2 text-xs">{rule.requirement || '-'}</td>
                    <td className="px-3 py-2 text-xs">{rule.revision}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
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

        <div className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
          {selected ? (
            <>
              <div className="flex items-start justify-between gap-3 mb-3">
                <div>
                  <h3 className="font-medium">{selected.name}</h3>
                  <div className="font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>{selected.key}</div>
                </div>
                <span className="px-2 py-0.5 rounded text-xs shrink-0" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  Code-defined
                </span>
              </div>

              <div className="rounded-lg p-3 mb-3" style={{ background: 'var(--color-bg-tertiary)' }}>
                <div className="flex items-center justify-between">
                  <span className="text-xs" style={{ color: 'var(--color-text-primary)' }}>Enabled</span>
                  <ToggleSwitch checked disabled label="Enabled (code-defined)" />
                </div>
                <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  规则由代码定义并始终启用；此开关不可在运行时修改。
                </div>
              </div>

              <dl className="space-y-2 text-xs">
                <div>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Category</dt>
                  <dd>{selected.category || '-'}</dd>
                </div>
                <div>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Enforcement</dt>
                  <dd><EnforcementBadge enforcement={selected.enforcement} /></dd>
                </div>
                <div>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Revision</dt>
                  <dd>{selected.revision}</dd>
                </div>
                <div>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Requirement</dt>
                  <dd>{selected.requirement || '-'}</dd>
                </div>
              </dl>

              <div className="mt-3 pt-3 border-t text-xs" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                修改规则、阈值或 enforcement 需要发版，没有运行时编辑入口。
              </div>
            </>
          ) : (
            <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Select a rule to view its details.</div>
          )}
        </div>
      </div>
    </div>
  );
}
