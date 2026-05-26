import { useState } from 'react';
import { ChevronDown, ChevronRight, Check, AlertTriangle, Loader2, Terminal } from 'lucide-react';
import type { SandboxSegment, SandboxTerminalSpec } from '../types';

interface Props {
  seg: SandboxSegment;
  onOpenTerminal: (spec: SandboxTerminalSpec) => void;
}

export default function SandboxBlock({ seg, onOpenTerminal }: Props) {
  const { sandboxType, message, sandboxId, hostname, ip, image, durationMs } = seg;
  const isPending = sandboxType === 'creating' || sandboxType === 'replacing';
  const isReady = sandboxType === 'ready';
  const isError = sandboxType === 'error';

  const [expanded, setExpanded] = useState(false);

  const handleOpenTerminal = () => {
    onOpenTerminal({ sandboxId, hostname, ip, image });
  };

  return (
    <div className="mb-2 rounded-xl border text-xs"
      style={{
        borderColor: isReady ? 'var(--color-success)' : isError ? 'var(--color-error)' : 'var(--color-border)',
        background: 'var(--color-bg-tertiary)',
      }}>
      <div className="flex items-center px-3 py-2">
        <button onClick={() => setExpanded(e => !e)}
          className="flex items-center gap-1.5 flex-1 min-w-0 cursor-pointer"
          style={{
            color: isReady ? 'var(--color-success)' : isError ? 'var(--color-error)' : 'var(--color-text-secondary)',
          }}>
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          {isPending
            ? <Loader2 size={14} className="animate-spin" />
            : isReady
              ? <Check size={14} />
              : <AlertTriangle size={14} />
          }
          <span className="font-medium truncate">{message}</span>
          {durationMs != null && (
            <span style={{ color: 'var(--color-text-muted)' }}>{durationMs}ms</span>
          )}
        </button>
        <button onClick={handleOpenTerminal}
          className="ml-2 p-1 rounded-md hover:opacity-80 shrink-0"
          style={{ color: 'var(--color-text-muted)' }}
          title="Open terminal">
          <Terminal size={13} />
        </button>
      </div>
      {expanded && (hostname || ip || image) && (
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
