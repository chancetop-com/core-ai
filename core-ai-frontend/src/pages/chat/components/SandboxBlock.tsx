import { Loader2, Check, AlertTriangle } from 'lucide-react';
import type { SandboxSegment } from '../types';

export default function SandboxBlock({ seg }: { seg: SandboxSegment }) {
  const { sandboxType, message, hostname, ip, image, durationMs } = seg;
  const isPending = sandboxType === 'creating' || sandboxType === 'replacing';
  const isReady = sandboxType === 'ready';
  const isError = sandboxType === 'error';

  return (
    <div className="mb-2 rounded-xl border text-xs"
      style={{
        borderColor: isReady ? 'var(--color-success)' : isError ? 'var(--color-error)' : 'var(--color-border)',
        background: 'var(--color-bg-tertiary)',
      }}>
      <div className="flex items-center gap-2 px-3 py-2"
        style={{
          color: isReady ? 'var(--color-success)' : isError ? 'var(--color-error)' : 'var(--color-text-secondary)',
        }}>
        {isPending
          ? <Loader2 size={14} className="animate-spin" />
          : isReady
            ? <Check size={14} />
            : <AlertTriangle size={14} />
        }
        <span className="font-medium">{message}</span>
        {durationMs != null && (
          <span style={{ color: 'var(--color-text-muted)' }}>{durationMs}ms</span>
        )}
      </div>
      {(hostname || ip || image) && (
        <div className="px-3 pb-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex flex-col gap-0.5 pt-1.5" style={{ fontFamily: 'monospace', fontSize: '11px', color: 'var(--color-text-secondary)' }}>
            {hostname && (
              <div className="flex gap-2">
                <span style={{ color: 'var(--color-text-muted)', fontFamily: 'inherit' }}>hostname</span>
                <span>{hostname}</span>
              </div>
            )}
            {ip && (
              <div className="flex gap-2">
                <span style={{ color: 'var(--color-text-muted)', fontFamily: 'inherit' }}>ip</span>
                <span>{ip}</span>
              </div>
            )}
            {image && (
              <div className="flex gap-2">
                <span style={{ color: 'var(--color-text-muted)', fontFamily: 'inherit' }}>image</span>
                <span>{image}</span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
