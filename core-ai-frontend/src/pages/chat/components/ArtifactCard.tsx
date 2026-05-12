import { FileText, Code as CodeIcon, FileCode, Globe, Image as ImageIcon, ArrowRight } from 'lucide-react';
import type { ArtifactSpec } from './artifactTypes';
import { summarizeArtifact } from './artifactTypes';

interface Props {
  artifact: ArtifactSpec;
  onOpen: (spec: ArtifactSpec) => void;
}

function iconFor(spec: ArtifactSpec) {
  if (spec.kind === 'html') return <Globe size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'svg') return <ImageIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'markdown') return <FileText size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'code') return <CodeIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  return <FileCode size={16} style={{ color: 'var(--color-primary)' }} />;
}

export default function ArtifactCard({ artifact, onOpen }: Props) {
  return (
    <button onClick={() => onOpen(artifact)}
      className="group inline-flex items-center gap-3 rounded-xl border px-3 py-2 my-1.5 max-w-full text-left cursor-pointer transition-colors"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}
      onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
      onMouseLeave={e => (e.currentTarget.style.background = 'var(--color-bg-secondary)')}>
      <div className="flex items-center justify-center rounded-lg shrink-0"
        style={{ background: 'var(--color-bg-tertiary)', width: 32, height: 32 }}>
        {iconFor(artifact)}
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
          {artifact.title}
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
