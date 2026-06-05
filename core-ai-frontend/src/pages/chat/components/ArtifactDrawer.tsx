import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { X, FileText, Code as CodeIcon, FileCode, Globe, Image as ImageIcon, Download, Copy, Check, Loader2, Maximize2, Minimize2, Share2 } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import type { PluggableList } from 'unified';
import { fileApi } from '../../../api/files';
import CodeMirrorEditor from '../../../components/CodeMirrorEditor';
import JsonTreeView from '../../../components/JsonTreeView';
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
type ShareStatus = 'idle' | 'loading' | 'copied' | 'error';

function iconFor(spec: ArtifactSpec) {
  if (spec.kind === 'html') return <Globe size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'svg') return <ImageIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'markdown') return <FileText size={16} style={{ color: 'var(--color-primary)' }} />;
  if (spec.kind === 'code') return <CodeIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  return <FileCode size={16} style={{ color: 'var(--color-primary)' }} />;
}

function isJsonCodeArtifact(spec: ArtifactSpec): boolean {
  return spec.kind === 'code' && spec.language?.toLowerCase() === 'json';
}

function isJsonFileArtifact(spec: ArtifactSpec): boolean {
  if (spec.kind !== 'file') return false;
  const ct = spec.contentType?.toLowerCase() ?? '';
  if (ct.includes('json')) return true;
  return !!spec.fileName && /\.json$/i.test(spec.fileName);
}

function supportsPreview(spec: ArtifactSpec): boolean {
  if (spec.kind === 'html' || spec.kind === 'svg' || spec.kind === 'markdown') return true;
  if (isJsonCodeArtifact(spec)) return true;
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

const WIDTH_STORAGE_KEY = 'artifact_drawer_width';
const MIN_DRAWER_WIDTH = 320;
const DEFAULT_DRAWER_WIDTH = 520;
const RESERVED_CHAT_WIDTH = 400;

function loadStoredWidth(): number {
  try {
    const raw = localStorage.getItem(WIDTH_STORAGE_KEY);
    const n = raw ? parseInt(raw, 10) : NaN;
    return Number.isFinite(n) && n >= MIN_DRAWER_WIDTH ? n : DEFAULT_DRAWER_WIDTH;
  } catch {
    return DEFAULT_DRAWER_WIDTH;
  }
}

export default function ArtifactDrawer({ artifact, onClose }: Props) {
  const canPreview = supportsPreview(artifact);
  const canSource = supportsSource(artifact);
  const [mode, setMode] = useState<ViewMode>(canPreview ? 'preview' : 'source');
  const [copied, setCopied] = useState(false);
  const [shareStatus, setShareStatus] = useState<ShareStatus>('idle');
  const [shareToast, setShareToast] = useState<string | null>(null);
  const [fileBlobUrl, setFileBlobUrl] = useState<string | null>(null);
  const [fileText, setFileText] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const [width, setWidth] = useState<number>(() => loadStoredWidth());
  const [isDragging, setIsDragging] = useState(false);
  const [maximized, setMaximized] = useState(false);

  // While dragging the left edge: track cursor with window listeners so iframe
  // content inside the drawer can't swallow the mousemove. Clamp to viewport.
  useEffect(() => {
    if (!isDragging) return;
    const onMove = (e: MouseEvent) => {
      const desired = window.innerWidth - e.clientX;
      const max = Math.max(MIN_DRAWER_WIDTH, window.innerWidth - RESERVED_CHAT_WIDTH);
      setWidth(Math.min(Math.max(desired, MIN_DRAWER_WIDTH), max));
    };
    const onUp = () => setIsDragging(false);
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, [isDragging]);

  // Persist on drag end (skip while dragging to avoid thrashing localStorage).
  useEffect(() => {
    if (isDragging) return;
    try {
      localStorage.setItem(WIDTH_STORAGE_KEY, String(width));
    } catch {
      // localStorage quota or disabled — ignore
    }
  }, [isDragging, width]);

  useEffect(() => {
    setMode(canPreview ? 'preview' : 'source');
  }, [canPreview, artifact.title]);

  useEffect(() => {
    setShareStatus('idle');
    setShareToast(null);
  }, [artifact.fileId]);

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

  const handleShare = async () => {
    if (!artifact.fileId || shareStatus === 'loading') return;
    setShareStatus('loading');
    try {
      const res = await fileApi.share(artifact.fileId);
      const shareUrl = new URL(res.share_url, window.location.origin).toString();
      try {
        await navigator.clipboard.writeText(shareUrl);
      } catch {
        window.prompt('Share link', shareUrl);
      }
      setShareStatus('copied');
      setShareToast('Share link copied, go share to your friend!');
      setTimeout(() => setShareStatus('idle'), 1800);
      setTimeout(() => setShareToast(null), 2500);
    } catch (err) {
      console.warn('share failed', err);
      setShareStatus('error');
      setShareToast('Share failed, please try again.');
      setTimeout(() => setShareStatus('idle'), 1800);
      setTimeout(() => setShareToast(null), 2500);
    }
  };

  const shareTitle = shareStatus === 'copied'
    ? 'Share link copied'
    : shareStatus === 'error'
      ? 'Share failed'
      : 'Share';

  const drawerContent = (
    <div className="flex flex-col h-full shrink-0 border-l relative"
      style={{ width: maximized ? '100%' : width, borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
      {/* drag handle on left edge — 6px hit area but only 1px visible accent */}
      {!maximized && (
        <div
          onMouseDown={e => { e.preventDefault(); setIsDragging(true); }}
          className="absolute top-0 left-0 h-full z-20"
          style={{ width: 6, marginLeft: -3, cursor: 'col-resize' }}
          title="Drag to resize"
        />
      )}
      {/* drag-time overlay: blocks iframe pointer events + forces col-resize cursor everywhere */}
      {isDragging && (
        <div className="fixed inset-0 z-50" style={{ cursor: 'col-resize' }} />
      )}
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
          {downloadUrl && (
            <div className="relative">
              <button onClick={handleShare}
                disabled={shareStatus === 'loading'}
                className="p-1.5 rounded-lg cursor-pointer transition-colors inline-flex items-center disabled:cursor-wait disabled:opacity-70"
                style={{ color: shareStatus === 'error' ? 'var(--color-error)' : shareStatus === 'copied' ? 'var(--color-success)' : 'var(--color-text-secondary)' }}
                title={shareTitle}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                {shareStatus === 'loading'
                  ? <Loader2 size={14} className="animate-spin" />
                  : shareStatus === 'copied'
                    ? <Check size={14} />
                    : <Share2 size={14} />}
              </button>
              {shareToast && (
                <div className="absolute top-full right-0 mt-1.5 px-3 py-1.5 rounded-lg text-sm whitespace-nowrap shadow-lg z-30"
                  style={{ background: 'var(--color-bg)', color: 'var(--color-text)', border: '1px solid var(--color-border)' }}>
                  {shareToast}
                </div>
              )}
            </div>
          )}
          <button onClick={() => setMaximized(m => !m)}
            className="p-1.5 rounded-lg cursor-pointer transition-colors"
            style={{ color: 'var(--color-text-secondary)' }}
            title={maximized ? 'Restore' : 'Maximize'}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
            {maximized ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
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
        {mode === 'preview' && renderPreview(artifact, { fileBlobUrl, fileText, fileError, fileLoading })}
        {mode === 'source' && renderSource(artifact, { fileText, fileError, fileLoading })}
      </div>
    </div>
  );

  if (maximized) {
    return createPortal(
      <div className="fixed inset-0 z-50">{drawerContent}</div>,
      document.body
    );
  }

  return drawerContent;
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

function renderPreview(spec: ArtifactSpec, state: FileState & FileSourceState) {
  if (isJsonCodeArtifact(spec) && spec.content) {
    return <JsonTreeView value={spec.content} />;
  }
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
    const isMarkdown = spec.contentType === 'text/markdown' || /\.(md|markdown)$/.test(lowerName);
    const isJson = isJsonFileArtifact(spec);
    if (isJson && state.fileText != null) {
      return <JsonTreeView value={state.fileText} />;
    }
    if (isImage) {
      return <div className="p-6 flex items-center justify-center"><img src={state.fileBlobUrl} alt={spec.title} className="max-w-full max-h-full" /></div>;
    }
    if (isHtml) {
      return <iframe sandbox="allow-scripts" src={state.fileBlobUrl} title={spec.title} className="w-full h-full border-0" />;
    }
    if (isMarkdown && state.fileText != null) {
      return (
        <div className="px-6 py-4 text-sm [&_pre]:bg-[var(--color-bg-tertiary)] [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto [&_table]:border-collapse [&_table]:my-2 [&_th]:border [&_th]:border-[var(--color-border)] [&_th]:px-2 [&_th]:py-1 [&_th]:bg-[var(--color-bg-tertiary)] [&_td]:border [&_td]:border-[var(--color-border)] [&_td]:px-2 [&_td]:py-1 [&_svg]:block [&_svg]:max-w-full [&_svg]:h-auto"
          style={{ color: 'var(--color-text)' }}>
          <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={REHYPE_PLUGINS}>{state.fileText}</ReactMarkdown>
        </div>
      );
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
