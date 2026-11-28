import type { SkillTier } from '../../api/client';

const TIER_STYLES: Record<SkillTier, { bg: string; color: string; label: string }> = {
  IMPORTED: { bg: 'rgba(100,116,139,0.2)', color: '#94a3b8', label: 'IMPORTED' },
  DOMAIN: { bg: 'rgba(59,130,246,0.18)', color: '#60a5fa', label: 'DOMAIN' },
  CORE: { bg: 'rgba(16,185,129,0.18)', color: '#34d399', label: 'CORE' },
  ALWAYS_ON: { bg: 'rgba(245,158,11,0.2)', color: '#fbbf24', label: 'ALWAYS_ON' },
};

export default function SkillTierBadge({ tier }: { tier?: SkillTier }) {
  const effective: SkillTier = tier || 'DOMAIN';
  const style = TIER_STYLES[effective] || TIER_STYLES.DOMAIN;
  return (
    <span
      data-testid="skill-tier-badge"
      className="px-2 py-0.5 rounded text-xs font-medium"
      style={{ background: style.bg, color: style.color }}
    >
      {style.label}
    </span>
  );
}
