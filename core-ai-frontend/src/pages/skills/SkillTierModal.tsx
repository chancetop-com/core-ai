import { useState } from 'react';
import { AlertTriangle, Shield, X } from 'lucide-react';
import type { SkillDefinition, SkillTier } from '../../api/client';
import { ApiError, skillQualityApi, type QualityIssue } from '../../api/quality';

const TIER_ORDER: Record<SkillTier, number> = {
  IMPORTED: 0,
  DOMAIN: 1,
  CORE: 2,
  ALWAYS_ON: 3,
};

const TIER_OPTIONS: SkillTier[] = ['IMPORTED', 'DOMAIN', 'CORE', 'ALWAYS_ON'];

interface Props {
  skill: SkillDefinition;
  onClose: () => void;
  onUpdated: (updated: SkillDefinition) => void;
}

export default function SkillTierModal({ skill, onClose, onUpdated }: Props) {
  const currentTier: SkillTier = skill.tier || (skill.source_type === 'REPO' ? 'IMPORTED' : 'DOMAIN');
  const [targetTier, setTargetTier] = useState<SkillTier>(currentTier);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [quotaExceeded, setQuotaExceeded] = useState(false);
  const [rejectedIssues, setRejectedIssues] = useState<QualityIssue[]>([]);

  const isDemotion = TIER_ORDER[targetTier] < TIER_ORDER[currentTier];
  const isBlockedDirectJump = currentTier === 'IMPORTED' && targetTier === 'ALWAYS_ON';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage('');
    setQuotaExceeded(false);
    setRejectedIssues([]);

    if (targetTier === currentTier) {
      onClose();
      return;
    }
    if (isBlockedDirectJump) {
      setErrorMessage('IMPORTED skills cannot be promoted directly to ALWAYS_ON; promote to CORE first.');
      return;
    }
    if (isDemotion && !reason.trim()) {
      setErrorMessage('A reason is required when demoting a skill.');
      return;
    }

    setSubmitting(true);
    try {
      const updated = await skillQualityApi.updateTier(skill.id, targetTier, reason);
      onUpdated(updated);
      onClose();
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
        if (err.status === 409 && err.errorCode === 'ALWAYS_ON_QUOTA_EXCEEDED') {
          setQuotaExceeded(true);
        }
        if (err.status === 422 && err.qualityReport?.issues) {
          setRejectedIssues(err.qualityReport.issues.filter(issue => issue.status === 'FAIL' || issue.enforcement === 'BLOCK'));
        }
      } else {
        setErrorMessage(err instanceof Error ? err.message : 'Failed to update skill tier');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.6)' }}
      role="dialog"
      aria-modal="true"
      aria-label="Manage Skill Tier"
    >
      <div
        className="w-full max-w-lg rounded-xl border p-5"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
      >
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Shield size={18} style={{ color: 'var(--color-primary)' }} />
            <h2 className="text-base font-semibold">Change Governance Tier</h2>
          </div>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" aria-label="Close modal">
            <X size={16} />
          </button>
        </div>

        <p className="text-xs mb-4" style={{ color: 'var(--color-text-secondary)' }}>
          Skill: <span className="font-mono">{skill.qualified_name}</span> (current: {currentTier})
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="target-tier-select" className="block text-xs font-medium mb-1">
              Target Tier
            </label>
            <select
              id="target-tier-select"
              value={targetTier}
              onChange={e => {
                setTargetTier(e.target.value as SkillTier);
                setErrorMessage('');
                setQuotaExceeded(false);
                setRejectedIssues([]);
              }}
              className="w-full px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
            >
              {TIER_OPTIONS.map(option => (
                <option
                  key={option}
                  value={option}
                  disabled={currentTier === 'IMPORTED' && option === 'ALWAYS_ON'}
                >
                  {option}
                </option>
              ))}
            </select>
            {isBlockedDirectJump && (
              <p className="text-xs mt-1" style={{ color: '#f59e0b' }}>
                IMPORTED skills must be promoted to CORE before ALWAYS_ON.
              </p>
            )}
          </div>

          <div>
            <label htmlFor="tier-change-reason" className="block text-xs font-medium mb-1">
              Reason {isDemotion ? '(required for demotion)' : '(optional)'}
            </label>
            <textarea
              id="tier-change-reason"
              rows={3}
              value={reason}
              onChange={e => setReason(e.target.value)}
              placeholder={isDemotion ? 'Explain why this skill is being demoted...' : 'Optional audit note...'}
              className="w-full px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
            />
          </div>

          {quotaExceeded && (
            <div
              role="alert"
              className="rounded-lg p-3 text-xs border"
              style={{ background: 'rgba(245,158,11,0.12)', borderColor: '#f59e0b', color: '#fbbf24' }}
            >
              <div className="font-medium mb-1">ALWAYS_ON Quota Exceeded (409)</div>
              <div>{errorMessage}</div>
            </div>
          )}

          {!quotaExceeded && errorMessage && (
            <div
              role="alert"
              className="rounded-lg p-3 text-xs border"
              style={{ background: 'rgba(220,38,38,0.12)', borderColor: '#ef4444', color: '#fca5a5' }}
            >
              <div className="flex items-center gap-1.5 font-medium mb-1">
                <AlertTriangle size={14} /> {errorMessage}
              </div>
              {rejectedIssues.length > 0 && (
                <ul className="mt-2 space-y-1.5 list-disc list-inside">
                  {rejectedIssues.map((issue, idx) => (
                    <li key={`${issue.rule_id}-${idx}`}>
                      <span className="font-mono">{issue.rule_id}</span>: {issue.message}
                      {issue.suggestion && (
                        <div className="ml-4 opacity-80">{issue.suggestion}</div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-border)' }}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || targetTier === currentTier || isBlockedDirectJump}
              className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-primary)' }}
            >
              {submitting ? 'Updating...' : 'Confirm Tier Change'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
