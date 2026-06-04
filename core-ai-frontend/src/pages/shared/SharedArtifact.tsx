import { useEffect, useState } from 'react';
import { Download, FileText, Loader2, Moon, Sun } from 'lucide-react';
import { useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import type { PluggableList } from 'unified';
import { publicArtifactApi, type SharedArtifactResponse } from '../../api/files';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';
import JsonTreeView from '../../components/JsonTreeView';
import { useTheme } from '../../hooks/useTheme';
import { chatSanitizeSchema } from '../chat/markdownSanitizeSchema';

const REHYPE_PLUGINS: PluggableList = [rehypeRaw, [rehypeSanitize, chatSanitizeSchema]];
const TEXT_EXT_RE = /\.(html?|css|js|jsx|ts|tsx|mjs|json|md|markdown|txt|xml|svg|py|java|kt|go|rb|rs|sh|bash|yaml|yml|toml|csv|tsv|sql|conf|ini|log)$/i;

interface FileState {
  fileBlobUrl: string | null;
  fileText: string | null;
  fileError: string | null;
  fileLoading: boolean;
}

function isTextFile(file: SharedArtifactResponse): boolean {
  const ct = file.content_type?.toLowerCase();
  if (ct) {
    if (ct.startsWith('text/')) return true;
    if (ct.includes('json') || ct.includes('xml') || ct.includes('javascript') || ct.includes('yaml')) return true;
  }
  return TEXT_EXT_RE.test(file.file_name);
}

function isJsonFile(file: SharedArtifactResponse): boolean {
  const ct = file.content_type?.toLowerCase() ?? '';
  return ct.includes('json') || /\.json$/i.test(file.file_name);
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function formatCreatedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function renderPreview(file: SharedArtifactResponse, state: FileState) {
  if (state.fileLoading) {
    return (
      <div className="h-full flex items-center justify-center gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={16} className="animate-spin" /> Loading artifact...
      </div>
    );
  }
  if (state.fileError) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-error)' }}>Failed to load artifact: {state.fileError}</div>;
  }
  if (!state.fileBlobUrl) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No preview available.</div>;
  }

  const lowerName = file.file_name.toLowerCase();
  const contentType = file.content_type ?? '';
  const isImage = contentType.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/.test(lowerName);
  const isHtml = contentType === 'text/html' || /\.html?$/.test(lowerName);
  const isMarkdown = contentType === 'text/markdown' || /\.(md|markdown)$/.test(lowerName);

  if (isJsonFile(file) && state.fileText != null) {
    return <JsonTreeView value={state.fileText} />;
  }
  if (isImage) {
    return <div className="h-full p-6 flex items-center justify-center"><img src={state.fileBlobUrl} alt={file.file_name} className="max-w-full max-h-full" /></div>;
  }
  if (isHtml) {
    return <iframe sandbox="allow-scripts" src={state.fileBlobUrl} title={file.file_name} className="w-full h-full border-0" />;
  }
  if (isMarkdown && state.fileText != null) {
    return (
      <div className="px-6 py-4 text-sm [&_pre]:bg-[var(--color-bg-tertiary)] [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto [&_table]:border-collapse [&_table]:my-2 [&_th]:border [&_th]:border-[var(--color-border)] [&_th]:px-2 [&_th]:py-1 [&_th]:bg-[var(--color-bg-tertiary)] [&_td]:border [&_td]:border-[var(--color-border)] [&_td]:px-2 [&_td]:py-1 [&_svg]:block [&_svg]:max-w-full [&_svg]:h-auto"
        style={{ color: 'var(--color-text)' }}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={REHYPE_PLUGINS}>{state.fileText}</ReactMarkdown>
      </div>
    );
  }
  if (isTextFile(file) && state.fileText != null) {
    return (
      <div className="h-full">
        <CodeMirrorEditor value={state.fileText} filename={file.file_name} onChange={() => undefined} readOnly />
      </div>
    );
  }
  return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Preview not available for this file type.</div>;
}

export default function SharedArtifact() {
  const { token = '' } = useParams();
  const { dark, toggle } = useTheme();
  const [artifact, setArtifact] = useState<SharedArtifactResponse | null>(null);
  const [metadataLoading, setMetadataLoading] = useState(true);
  const [metadataError, setMetadataError] = useState<string | null>(null);
  const [fileBlobUrl, setFileBlobUrl] = useState<string | null>(null);
  const [fileText, setFileText] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    document.title = artifact ? `${artifact.file_name} - core-ai` : 'Shared artifact - core-ai';
  }, [artifact]);

  useEffect(() => {
    let cancelled = false;
    setMetadataLoading(true);
    setMetadataError(null);
    setArtifact(null);
    publicArtifactApi.get(token).then(result => {
      if (cancelled) return;
      setArtifact(result);
      setMetadataLoading(false);
    }).catch(err => {
      if (cancelled) return;
      setMetadataError(err instanceof Error ? err.message : String(err));
      setMetadataLoading(false);
    });
    return () => { cancelled = true; };
  }, [token]);

  useEffect(() => {
    if (!artifact) return;
    let cancelled = false;
    let createdUrl: string | null = null;
    setFileLoading(true);
    setFileError(null);
    setFileBlobUrl(null);
    setFileText(null);
    fetch(publicArtifactApi.contentUrl(token)).then(async res => {
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
      const blob = await res.blob();
      if (cancelled) return;
      createdUrl = URL.createObjectURL(blob);
      setFileBlobUrl(createdUrl);
      if (isTextFile(artifact)) {
        const text = await blob.text();
        if (cancelled) return;
        setFileText(text);
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
  }, [artifact, token]);

  const handleDownload = async () => {
    if (!artifact || downloading) return;
    setDownloading(true);
    try {
      let href = fileBlobUrl;
      let revoke = false;
      if (!href) {
        const res = await fetch(publicArtifactApi.contentUrl(token));
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
        href = URL.createObjectURL(await res.blob());
        revoke = true;
      }
      const a = document.createElement('a');
      a.href = href;
      a.download = artifact.file_name;
      a.click();
      if (revoke) URL.revokeObjectURL(href);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col" style={{ background: 'var(--color-bg)', color: 'var(--color-text)' }}>
      <header className="h-16 px-4 md:px-6 flex items-center justify-between border-b"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <a href="/" className="flex items-center" aria-label="core-ai">
          <img src={dark ? '/logo-lockup-dark.svg' : '/logo-lockup.svg'} alt="core-ai" className="h-9" />
        </a>
        <button onClick={toggle}
          className="w-9 h-9 inline-flex items-center justify-center rounded-lg cursor-pointer transition-colors"
          style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}
          title={dark ? 'Light Mode' : 'Dark Mode'}>
          {dark ? <Sun size={16} /> : <Moon size={16} />}
        </button>
      </header>

      <main className="flex-1 w-full max-w-6xl mx-auto px-4 py-4 md:px-6 md:py-6 flex flex-col gap-4 min-h-0">
        {metadataLoading && (
          <div className="flex-1 flex items-center justify-center gap-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 size={16} className="animate-spin" /> Loading artifact...
          </div>
        )}
        {!metadataLoading && metadataError && (
          <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center">
            <FileText size={32} style={{ color: 'var(--color-text-secondary)' }} />
            <div className="text-sm" style={{ color: 'var(--color-text)' }}>Shared artifact not found or expired.</div>
          </div>
        )}
        {!metadataLoading && artifact && (
          <>
            <section className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div className="min-w-0">
                <div className="flex items-center gap-2 min-w-0">
                  <FileText size={18} className="shrink-0" style={{ color: 'var(--color-primary)' }} />
                  <h1 className="text-base font-semibold truncate" style={{ color: 'var(--color-text)' }}>{artifact.file_name}</h1>
                </div>
                <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  <span>{artifact.content_type || 'File'}</span>
                  <span>{formatBytes(artifact.size)}</span>
                  <span>{formatCreatedAt(artifact.created_at)}</span>
                </div>
              </div>
              <button onClick={handleDownload}
                disabled={downloading}
                className="h-9 px-3 inline-flex items-center justify-center gap-2 rounded-lg text-sm cursor-pointer transition-colors disabled:cursor-wait disabled:opacity-70"
                style={{ background: 'var(--color-primary)', color: '#fff' }}>
                {downloading ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                Download
              </button>
            </section>

            <section className="flex-1 min-h-[420px] overflow-auto border rounded-lg"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              {renderPreview(artifact, { fileBlobUrl, fileText, fileError, fileLoading })}
            </section>
          </>
        )}
      </main>
    </div>
  );
}
