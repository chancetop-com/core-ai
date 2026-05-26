import { X, Terminal } from 'lucide-react';
import type { SandboxTerminalSpec } from '../types';

interface Props {
  sandbox: SandboxTerminalSpec;
  onClose: () => void;
}

export default function SandboxTerminalPanel({ sandbox, onClose }: Props) {
  return (
    <div className="w-[520px] shrink-0 flex flex-col border-l"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)' }}>
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b shrink-0"
        style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 min-w-0">
          <Terminal size={16} style={{ color: 'var(--color-text-secondary)' }} />
          <div className="min-w-0">
            <div className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
              Sandbox Terminal
            </div>
            <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)', fontFamily: 'monospace' }}>
              {sandbox.hostname || sandbox.sandboxId}
            </div>
          </div>
        </div>
        <button onClick={onClose}
          className="p-1 rounded-md hover:opacity-80"
          style={{ color: 'var(--color-text-secondary)' }}>
          <X size={16} />
        </button>
      </div>

      {/* Info bar */}
      <div className="flex flex-wrap gap-x-4 gap-y-0.5 px-4 py-2 border-b shrink-0 text-xs"
        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}>
        {sandbox.ip && (
          <span>
            <span style={{ color: 'var(--color-text-secondary)' }}>ip</span>{' '}
            <span style={{ fontFamily: 'monospace' }}>{sandbox.ip}</span>
          </span>
        )}
        {sandbox.image && (
          <span>
            <span style={{ color: 'var(--color-text-secondary)' }}>image</span>{' '}
            <span style={{ fontFamily: 'monospace' }}>{sandbox.image}</span>
          </span>
        )}
      </div>

      {/* Terminal body (placeholder) */}
      <div className="flex-1 min-h-0 flex flex-col p-4"
        style={{ background: '#1e1e1e' }}>
        <div className="flex-1 rounded-md border overflow-hidden"
          style={{ borderColor: '#333', background: '#0d0d0d' }}>
          {/* Terminal toolbar */}
          <div className="flex items-center gap-2 px-3 py-1.5 border-b"
            style={{ borderColor: '#333', background: '#1a1a1a' }}>
            <span className="text-xs" style={{ color: '#888' }}>bash</span>
          </div>
          {/* Terminal content placeholder */}
          <div className="p-4 font-mono text-xs leading-relaxed"
            style={{ color: '#888', height: '100%' }}>
            <div style={{ color: '#4ec9b0' }}>$ _</div>
            <div className="mt-2" style={{ color: '#666' }}>
              Terminal connection will be available here.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
