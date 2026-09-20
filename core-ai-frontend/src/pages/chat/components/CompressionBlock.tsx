import { Sparkles } from 'lucide-react';
import type { CompressionSegment } from '../types';
import { compressionUsageText } from '../utils';

/**
 * Context compression that shortened the history before this turn ran. It renders as one row of the
 * turn's internal-step container (the Tools block), so the reader can tell why the conversation above
 * is shorter than it looks, and it is persisted with the message, so it is still there after a reload.
 */
export default function CompressionBlock({ seg }: { seg: CompressionSegment }) {
  return (
    <div className="flex items-center gap-1.5 px-3 py-2 text-xs"
      style={{ color: 'var(--color-text-secondary)' }}>
      <Sparkles size={14} className="shrink-0" />
      <span className="font-medium shrink-0">Context compressed</span>
      <span className="truncate" style={{ color: 'var(--color-text-muted)' }}>
        {seg.before} -&gt; {seg.after} messages{compressionUsageText(seg)}
      </span>
    </div>
  );
}
