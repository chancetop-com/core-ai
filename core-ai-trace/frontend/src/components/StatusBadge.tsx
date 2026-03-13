interface Props {
  status: string;
}

const colorMap: Record<string, { bg: string; text: string }> = {
  COMPLETED: { bg: '#dcfce7', text: '#16a34a' },
  OK: { bg: '#dcfce7', text: '#16a34a' },
  PUBLISHED: { bg: '#dcfce7', text: '#16a34a' },
  RUNNING: { bg: '#dbeafe', text: '#2563eb' },
  DRAFT: { bg: '#f1f5f9', text: '#64748b' },
  ERROR: { bg: '#fee2e2', text: '#dc2626' },
  ARCHIVED: { bg: '#f1f5f9', text: '#94a3b8' },
};

export default function StatusBadge({ status }: Props) {
  const colors = colorMap[status] || colorMap.DRAFT;
  return (
    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
      style={{ background: colors.bg, color: colors.text }}>
      {status}
    </span>
  );
}
