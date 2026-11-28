import { Lock, ShieldAlert } from 'lucide-react';
import type { SkillTier } from '../../../api/client';
import SkillTierBadge from '../../skills/SkillTierBadge';
import type { SkillAttachNoticeSegment } from '../types';

interface Props {
  segment: SkillAttachNoticeSegment;
}

const VALID_TIERS = new Set<string>(['IMPORTED', 'DOMAIN', 'CORE', 'ALWAYS_ON']);

export default function SkillAttachNoticeCard({ segment }: Props) {
  const tier = segment.tier && VALID_TIERS.has(segment.tier) ? (segment.tier as SkillTier) : undefined;

  return (
    <div
      data-testid="skill-attach-notice-card"
      role="status"
      className="rounded-xl border px-4 py-3 text-xs"
      style={{
        background: 'rgba(245,158,11,0.10)',
        borderColor: '#f59e0b',
        color: 'var(--color-text)',
      }}
    >
      <div className="flex items-center justify-between gap-2 mb-1.5">
        <div className="flex items-center gap-2 font-medium text-sm">
          <ShieldAlert size={15} style={{ color: '#fbbf24' }} />
          <span className="font-mono">{segment.qualifiedName}</span>
          {tier && <SkillTierBadge tier={tier} />}
        </div>
        <span
          data-testid="skill-unattached-badge"
          className="inline-flex items-center gap-1 px-2 py-0.5 rounded font-medium"
          style={{ background: 'rgba(245,158,11,0.2)', color: '#fde68a' }}
        >
          <Lock size={11} /> Not Attached
        </span>
      </div>

      {segment.description && (
        <p className="mb-2" style={{ color: 'var(--color-text-secondary)' }}>
          {segment.description}
        </p>
      )}

      {segment.requiredCapabilities && segment.requiredCapabilities.length > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-1.5">
          <span style={{ color: 'var(--color-text-secondary)' }}>Required capabilities:</span>
          {segment.requiredCapabilities.map(cap => (
            <span
              key={cap}
              className="px-1.5 py-0.5 rounded font-mono text-[11px]"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
            >
              {cap}
            </span>
          ))}
        </div>
      )}

      <div style={{ color: '#fde68a' }}>
        This skill is not attached to the current session. Review its details and attach it manually in Session Settings &rarr; Skills before calling <span className="font-mono">use_skill</span>.
      </div>
    </div>
  );
}
