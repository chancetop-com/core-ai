export type ArtifactKind = 'html' | 'svg' | 'code' | 'markdown' | 'file';

export interface ArtifactSpec {
  kind: ArtifactKind;
  title: string;
  // For kind in {html,svg,code,markdown}: inline text content
  content?: string;
  // For kind='code'/'file': source language hint (e.g. python, json, html)
  language?: string;
  // For kind='file': server-side upload metadata
  fileId?: string;
  fileName?: string;
  contentType?: string;
  size?: number;
  // For kind='file': public same-origin content URL (no auth); takes precedence over fileId
  contentUrl?: string;
}

const LONG_MD_CHAR_THRESHOLD = 800;
const LONG_MD_LINE_THRESHOLD = 30;

export function isLongMarkdown(text: string): boolean {
  if (!text) return false;
  if (text.length >= LONG_MD_CHAR_THRESHOLD) return true;
  const lines = text.split('\n').length;
  return lines >= LONG_MD_LINE_THRESHOLD;
}

export function summarizeArtifact(spec: ArtifactSpec): string {
  if (spec.kind === 'file') {
    const parts: string[] = [];
    if (spec.contentType) parts.push(spec.contentType);
    if (typeof spec.size === 'number') parts.push(formatBytes(spec.size));
    return parts.join(' · ') || 'File';
  }
  const chars = spec.content?.length ?? 0;
  const lines = spec.content ? spec.content.split('\n').length : 0;
  const lang = spec.language ? `${spec.language} · ` : '';
  return `${lang}${lines} lines · ${chars} chars`;
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
