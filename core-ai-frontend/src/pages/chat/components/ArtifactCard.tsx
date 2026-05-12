import { FileText, Code as CodeIcon, Package, Globe, Image as ImageIcon, ArrowRight } from 'lucide-react';
import type { ArtifactSpec } from './artifactTypes';
import { summarizeArtifact } from './artifactTypes';

interface Props {
  artifact: ArtifactSpec;
  onOpen: (spec: ArtifactSpec) => void;
}

function iconFor(spec: ArtifactSpec) {
  const color = spec.kind === 'file' ? 'var(--color-success)' : 'var(--color-primary)';
  if (spec.kind === 'html') return <Globe size={16} style={{ color }} />;
  if (spec.kind === 'svg') return <ImageIcon size={16} style={{ color }} />;
  if (spec.kind === 'markdown') return <FileText size={16} style={{ color }} />;
  if (spec.kind === 'code') return <CodeIcon size={16} style={{ color }} />;
  return <Package size={16} style={{ color }} />;
}

export default function ArtifactCard({ artifact, onOpen }: Props) {
  const isFile = artifact.kind === 'file';
  const accent = isFile ? 'var(--color-success)' : 'var(--color-border)';
  const tintBg = isFile ? 'var(--color-success)' + '12' : 'var(--color-bg-secondary)';
  const tintHoverBg = isFile ? 'var(--color-success)' + '22' : 'var(--color-bg-tertiary)';
  const iconBg = isFile ? 'var(--color-success)' + '20' : 'var(--color-bg-tertiary)';
  return (
    <button onClick={() => onOpen(artifact)}
      className="group flex w-fit max-w-md items-center gap-3 rounded-xl border px-3 py-2 my-1.5 text-left cursor-pointer transition-colors"
      style={{ borderColor: accent, background: tintBg }}
      onMouseEnter={e => (e.currentTarget.style.background = tintHoverBg)}
      onMouseLeave={e => (e.currentTarget.style.background = tintBg)}>
      <div className="flex items-center justify-center rounded-lg shrink-0"
        style={{ background: iconBg, width: 32, height: 32 }}>
        {iconFor(artifact)}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
            {artifact.title}
          </span>
          {isFile && (
            <span className="text-[10px] uppercase tracking-wide font-semibold px-1.5 py-0.5 rounded shrink-0"
              style={{ color: 'var(--color-success)', background: 'var(--color-success)' + '20' }}>
              Artifact
            </span>
          )}
        </div>
        <div className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>
          {summarizeArtifact(artifact)}
        </div>
      </div>
      <ArrowRight size={14} className="shrink-0 opacity-50 group-hover:opacity-100 transition-opacity"
        style={{ color: 'var(--color-text-secondary)' }} />
    </button>
  );
}
