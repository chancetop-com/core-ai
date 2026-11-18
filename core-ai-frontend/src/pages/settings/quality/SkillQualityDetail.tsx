import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import ToggleSwitch from '../../../components/ToggleSwitch';
import EnforcementBadge from './EnforcementBadge';

export default function SkillQualityDetail() {
  const { ruleKey } = useParams<{ ruleKey: string }>();
  const navigate = useNavigate();
  const [rules, setRules] = useState<SkillQualityRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    skillQualityApi.rules()
      .then(response => setRules(response.rules || []))
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load skill quality rules'))
      .finally(() => setLoading(false));
  }, []);

  const key = ruleKey ? decodeURIComponent(ruleKey) : '';
  const rule = rules.find(item => item.key === key) || null;

  return (
    <div className="p-6 max-w-4xl">
      <button onClick={() => navigate('/settings/quality?tab=skill')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Quality
      </button>

      {loading ? (
        <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : error ? (
        <div className="rounded-lg px-3 py-2 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>{error}</div>
      ) : !rule ? (
        <div className="rounded-xl border p-6 text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          Rule not found.
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
            <div>
              <h1 className="text-xl font-semibold">{rule.name}</h1>
              <div className="font-mono text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{rule.key}</div>
            </div>
            <span className="px-2 py-1 rounded text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              Code-defined
            </span>
          </div>

          <div className="rounded-xl border p-4 mb-4" style={{ borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between gap-3">
              <div>
                <div className="font-medium text-sm">Enabled</div>
                <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Code-defined rules are always enabled; this switch cannot be changed at runtime.
                </div>
              </div>
              <ToggleSwitch checked disabled label="Enabled (code-defined)" />
            </div>
          </div>

          <div className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
            <div className="font-medium mb-3">Definition</div>
            <dl className="grid gap-4 text-sm md:grid-cols-2">
              <div>
                <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Category</dt>
                <dd className="mt-0.5">{rule.category || '-'}</dd>
              </div>
              <div>
                <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Enforcement</dt>
                <dd className="mt-0.5"><EnforcementBadge enforcement={rule.enforcement} /></dd>
              </div>
              <div>
                <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Revision</dt>
                <dd className="mt-0.5">{rule.revision}</dd>
              </div>
              <div className="md:col-span-2">
                <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Requirement</dt>
                <dd className="mt-0.5">{rule.requirement || '-'}</dd>
              </div>
            </dl>
            <div className="mt-4 pt-3 border-t text-xs" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              Changing a rule, threshold or enforcement requires a release; there is no runtime editor.
            </div>
          </div>
        </>
      )}
    </div>
  );
}
