import { useEffect, useMemo, useState } from 'react';
import { X, FileText, Code as CodeIcon, FileCode, Globe, Image as ImageIcon, Download, Copy, Check, Loader2 } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import type { PluggableList } from 'unified';
import CodeMirrorEditor from '../../../components/CodeMirrorEditor';
import type { ArtifactSpec } from './artifactTypes';
import { chatSanitizeSchema } from '../markdownSanitizeSchema';

const REHYPE_PLUGINS: PluggableList = [rehypeRaw, [rehypeSanitize, chatSanitizeSchema]];

function authedFileUrl(fileId: string): string {
  return `/api/files/${fileId}/content`;
}

async function fetchFileBlob(fileId: string): Promise<Blob> {
  const apiKey = localStorage.getItem('apiKey');
  const headers: Record<string, string> = {};
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  const res = await fetch(authedFileUrl(fileId), { headers });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.blob();
}

interface Props {
  artifact: ArtifactSpec;
  onClose: () => void;
}

type ViewMode = 'preview' | 'source';

function iconFor(spec: ArtifactSpec) {
  if (spec.kind === 'html') return <Globe size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'svg') return <ImageIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'markdown') return <FileText size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'code') return <CodeIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  return <FileCode size={16} style={{ color: 'var(--color-primary)' }} />;
}

function supportsPreview(spec: ArtifactSpec): boolean {
  if (spec.kind === 'html' || spec.kind === 'svg' || spec.kind === 'markdown') return true;
  // For file: always allow preview attempt — renderPreview will pick iframe/img/fallback
  // based on contentType or filename extension. Without this, streaming-added artifacts
  // (which carry no contentType yet) fall back to source view and show "No source".
  if (spec.kind === 'file') return true;
  return false;
}

const TEXT_EXT_RE = /\.(html?|css|js|jsx|ts|tsx|mjs|json|md|markdown|txt|xml|svg|py|java|kt|go|rb|rs|sh|bash|yaml|yml|toml|csv|tsv|sql|conf|ini|log)$/i;

function isTextFile(spec: ArtifactSpec): boolean {
  const ct = spec.contentType?.toLowerCase();
  if (ct) {
    if (ct.startsWith('text/')) return true;
    if (ct.includes('json') || ct.includes('xml') || ct.includes('javascript') || ct.includes('yaml')) return true;
  }
  if (spec.fileName && TEXT_EXT_RE.test(spec.fileName)) return true;
  return false;
}

function supportsSource(spec: ArtifactSpec): boolean {
  if (spec.kind === 'file') return isTextFile(spec);
  return true;
}

function languageToFilename(lang: string | undefined): string {
  if (!lang) return 'snippet.txt';
  const l = lang.toLowerCase();
  if (l === 'js' || l === 'javascript') return 'snippet.js';
  if (l === 'ts' || l === 'typescript') return 'snippet.ts';
  if (l === 'py' || l === 'python') return 'snippet.py';
  if (l === 'json') return 'snippet.json';
  if (l === 'md' || l === 'markdown') return 'snippet.md';
  if (l === 'html' || l === 'svg') return `snippet.${l}`;
  return `snippet.${l}`;
}

function fileDownloadUrl(spec: ArtifactSpec): string | null {
  if (spec.kind !== 'file' || !spec.fileId) return null;
  return `/api/files/${spec.fileId}/content`;
}

export default function ArtifactDrawer({ artifact, onClose }: Props) {
  const canPreview = supportsPreview(artifact);
  const canSource = supportsSource(artifact);
  const [mode, setMode] = useState<ViewMode>(canPreview ? 'preview' : 'source');
  const [copied, setCopied] = useState(false);
  const [fileBlobUrl, setFileBlobUrl] = useState<string | null>(null);
  const [fileText, setFileText] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [fileLoading, setFileLoading] = useState(false);

  useEffect(() => {
    setMode(canPreview ? 'preview' : 'source');
  }, [canPreview, artifact.title]);

  useEffect(() => {
    if (artifact.kind !== 'file' || !artifact.fileId) {
      setFileBlobUrl(null);
      setFileText(null);
      setFileError(null);
      return;
    }
    let cancelled = false;
    let createdUrl: string | null = null;
    setFileLoading(true);
    setFileError(null);
    setFileBlobUrl(null);
    setFileText(null);
    const wantText = isTextFile(artifact);
    fetchFileBlob(artifact.fileId).then(async blob => {
      if (cancelled) return;
      createdUrl = URL.createObjectURL(blob);
      setFileBlobUrl(createdUrl);
      if (wantText) {
        try {
          const text = await blob.text();
          if (cancelled) return;
          setFileText(text);
        } catch {
          // ignore text decoding failure; preview iframe still works
        }
      }
      setFileLoading(false);
    }).catch(err => {
      if (cancelled) return;
      setFileError(err instanceof Error ? err.message : String(err));
      setFileLoading(false);
    });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [artifact.kind, artifact.fileId, artifact.contentType, artifact.fileName]);

  const downloadUrl = useMemo(() => fileDownloadUrl(artifact), [artifact]);

  const copy = () => {
    if (!artifact.content) return;
    void navigator.clipboard.writeText(artifact.content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  const handleDownload = async () => {
    if (!artifact.fileId) return;
    try {
      const blob = await fetchFileBlob(artifact.fileId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = artifact.fileName || artifact.title;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.warn('download failed', err);
    }
  };

  return (
    <div className="flex flex-col h-full w-[520px] shrink-0 border-l"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
      <div className="flex items-center justify-between px-6 py-3 border-b min-h-[61px]"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex items-center justify-center rounded-lg shrink-0"
            style={{ background: 'var(--color-bg-tertiary)', width: 36, height: 36 }}>
            {iconFor(artifact)}
          </div>
          <div className="min-w-0">
            <span className="text-sm font-medium truncate block" style={{ color: 'var(--color-text)' }}>
              {artifact.title}
            </span>
            <span className="text-xs truncate block" style={{ color: 'var(--color-text-muted)' }}>
              {artifact.kind.toUpperCase()}{artifact.language ? ` · ${artifact.language}` : ''}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-1">
          {artifact.content && (
            <button onClick={copy}
              className="p-1.5 rounded-lg cursor-pointer transition-colors"
              style={{ color: 'var(--color-text-secondary)' }}
              title={copied ? 'Copied' : 'Copy content'}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
              {copied ? <Check size={14} /> : <Copy size={14} />}
            </button>
          )}
          {downloadUrl && (
            <button onClick={handleDownload}
              className="p-1.5 rounded-lg cursor-pointer transition-colors inline-flex items-center"
              style={{ color: 'var(--color-text-secondary)' }}
              title="Download"
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
              <Download size={14} />
            </button>
          )}
          <button onClick={onClose}
            className="p-1.5 rounded-lg cursor-pointer transition-colors"
            style={{ color: 'var(--color-text-secondary)' }}
            title="Close"
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
            <X size={14} />
          </button>
        </div>
      </div>

      {canPreview && canSource && (
        <div className="flex items-center gap-2 px-4 py-2 border-b"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
          <button onClick={() => setMode('preview')}
            className="px-3 py-1 text-xs rounded-md cursor-pointer transition-colors"
            style={{
              background: mode === 'preview' ? 'var(--color-primary)' + '20' : 'transparent',
              color: mode === 'preview' ? 'var(--color-primary)' : 'var(--color-text-secondary)',
            }}>
            Preview
          </button>
          <button onClick={() => setMode('source')}
            className="px-3 py-1 text-xs rounded-md cursor-pointer transition-colors"
            style={{
              background: mode === 'source' ? 'var(--color-primary)' + '20' : 'transparent',
              color: mode === 'source' ? 'var(--color-primary)' : 'var(--color-text-secondary)',
            }}>
            Source
          </button>
        </div>
      )}

      <div className="flex-1 min-h-0 overflow-auto" style={{ background: 'var(--color-bg)' }}>
        {mode === 'preview' && renderPreview(artifact, { fileBlobUrl, fileError, fileLoading })}
        {mode === 'source' && renderSource(artifact, { fileText, fileError, fileLoading })}
      </div>
    </div>
  );
}

interface FileState {
  fileBlobUrl: string | null;
  fileError: string | null;
  fileLoading: boolean;
}

interface FileSourceState {
  fileText: string | null;
  fileError: string | null;
  fileLoading: boolean;
}

function renderPreview(spec: ArtifactSpec, state: FileState) {
  if (spec.kind === 'html' && spec.content) {
    return <iframe sandbox="allow-scripts" srcDoc={spec.content} title={spec.title} className="w-full h-full border-0" />;
  }
  if (spec.kind === 'svg' && spec.content) {
    return (
      <div className="p-6 flex items-center justify-center" dangerouslySetInnerHTML={{ __html: spec.content }} />
    );
  }
  if (spec.kind === 'markdown' && spec.content) {
    return (
      <div className="px-6 py-4 text-sm [&_pre]:bg-[var(--color-bg-tertiary)] [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto [&_table]:border-collapse [&_table]:my-2 [&_th]:border [&_th]:border-[var(--color-border)] [&_th]:px-2 [&_th]:py-1 [&_th]:bg-[var(--color-bg-tertiary)] [&_td]:border [&_td]:border-[var(--color-border)] [&_td]:px-2 [&_td]:py-1 [&_svg]:block [&_svg]:max-w-full [&_svg]:h-auto"
        style={{ color: 'var(--color-text)' }}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={REHYPE_PLUGINS}>{spec.content}</ReactMarkdown>
      </div>
    );
  }
  if (spec.kind === 'file' && spec.fileId) {
    if (state.fileLoading) {
      return (
        <div className="p-6 flex items-center justify-center gap-2 text-sm" style={{ color: 'var(--color-text-muted)' }}>
          <Loader2 size={16} className="animate-spin" /> Loading file...
        </div>
      );
    }
    if (state.fileError) {
      return <div className="p-6 text-sm" style={{ color: 'var(--color-error)' }}>Failed to load file: {state.fileError}</div>;
    }
    if (!state.fileBlobUrl) {
      return <div className="p-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>No preview available.</div>;
    }
    const lowerName = spec.fileName?.toLowerCase() ?? '';
    const isImage = spec.contentType?.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/.test(lowerName);
    const isHtml = spec.contentType === 'text/html' || /\.html?$/.test(lowerName);
    if (isImage) {
      return <div className="p-6 flex items-center justify-center"><img src={state.fileBlobUrl} alt={spec.title} className="max-w-full max-h-full" /></div>;
    }
    if (isHtml) {
      return <iframe sandbox="allow-scripts" src={state.fileBlobUrl} title={spec.title} className="w-full h-full border-0" />;
    }
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>Preview not available for this file type. Use the download button.</div>;
  }
  return <div className="p-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>No preview available.</div>;
}

function renderSource(spec: ArtifactSpec, state: FileSourceState) {
  if (spec.kind === 'file') {
    if (state.fileLoading) {
      return (
        <div className="p-6 flex items-center justify-center gap-2 text-sm" style={{ color: 'var(--color-text-muted)' }}>
          <Loader2 size={16} className="animate-spin" /> Loading source...
        </div>
      );
    }
    if (state.fileError) {
      return <div className="p-6 text-sm" style={{ color: 'var(--color-error)' }}>Failed to load source: {state.fileError}</div>;
    }
    if (state.fileText == null) {
      return <div className="p-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>Source not available for this file type.</div>;
    }
    return (
      <div className="h-full">
        <CodeMirrorEditor value={state.fileText} filename={spec.fileName || 'snippet.txt'} onChange={() => undefined} readOnly />
      </div>
    );
  }
  if (spec.content == null) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-muted)' }}>No source available.</div>;
  }
  return (
    <div className="h-full">
      <CodeMirrorEditor value={spec.content} filename={languageToFilename(spec.language ?? spec.kind)} onChange={() => undefined} readOnly />
    </div>
  );
}
