export default function EnforcementBadge({ enforcement }: { enforcement?: string }) {
  return (
    <span className="px-1.5 py-0.5 rounded" style={{
      background: enforcement === 'BLOCK' ? 'rgba(220,38,38,0.12)' : enforcement === 'WARN' ? 'rgba(217,119,6,0.12)' : 'var(--color-bg-tertiary)',
      color: enforcement === 'BLOCK' ? 'var(--color-error)' : 'var(--color-text-secondary)',
    }}>{enforcement || '-'}</span>
  );
}
