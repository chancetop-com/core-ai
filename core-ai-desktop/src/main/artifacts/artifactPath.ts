// Pure helpers for the coreai-artifact:// protocol: URL parsing, the .core-ai + registered-paths
// whitelist and HTTP range handling. Kept free of any Electron import so the node:test suite can
// exercise them directly.
import { readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';

export interface ByteRange {
  start: number;
  end: number;
}

const CONTENT_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.pdf': 'application/pdf',
  '.html': 'text/html',
  '.htm': 'text/html',
  '.md': 'text/markdown',
  '.json': 'application/json',
  '.txt': 'text/plain',
  '.log': 'text/plain',
  '.csv': 'text/csv',
  '.xml': 'application/xml',
};

export function contentTypeFor(filePath: string): string {
  return CONTENT_TYPES[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream';
}

/** First path segment = the workspace token; the host is normalized by the URL parser and cannot hold it. */
export function tokenOf(pathname: string): string {
  const trimmed = pathname.replace(/^\/+/, '');
  const slash = trimmed.indexOf('/');
  return slash === -1 ? trimmed : trimmed.slice(0, slash);
}

export function decodeRelativePath(pathname: string): string | null {
  const trimmed = pathname.replace(/^\/+/, '');
  const slash = trimmed.indexOf('/');
  if (slash === -1) return '';
  const segments = trimmed.slice(slash + 1).split('/');
  const decoded: string[] = [];
  for (const segment of segments) {
    try {
      decoded.push(decodeURIComponent(segment));
    } catch {
      return null;
    }
  }
  return decoded.join('/');
}

/** Rejects `..`, absolute paths and symlinks pointing outside {workspace}/.core-ai. */
export function resolveInsideArtifacts(workspace: string, relativePath: string): string | null {
  const root = realpathOrNull(path.resolve(workspace, '.core-ai'));
  if (!root) return null;
  const target = realpathOrNull(path.resolve(workspace, relativePath));
  if (!target) return null;
  return isInside(root, target) ? target : null;
}

/** Like resolveInsideArtifacts, but for registered files anywhere inside {workspace} (no .core-ai root). */
export function resolveInsideWorkspace(workspace: string, relativePath: string): string | null {
  const root = realpathOrNull(path.resolve(workspace));
  if (!root) return null;
  const target = realpathOrNull(path.resolve(workspace, relativePath));
  if (!target) return null;
  return isInside(root, target) ? target : null;
}

/** Manifest written by the engine's submit_artifacts tool (LocalArtifactManifest on the Java side). */
export function artifactManifestPath(workspace: string): string {
  return path.join(workspace, '.core-ai', 'artifacts', 'manifest.jsonl');
}

/** Registered artifact paths, read per request (small file; a stale cache would outlive a register). */
export function registeredArtifactPaths(workspace: string): Set<string> {
  let text: string;
  try {
    text = readFileSync(artifactManifestPath(workspace), 'utf8');
  } catch {
    return new Set();
  }
  const paths = new Set<string>();
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try {
      const entry = JSON.parse(line) as { path?: unknown };
      if (typeof entry?.path === 'string' && entry.path) paths.add(entry.path);
    } catch {
      // a torn append at the tail must not break listing
    }
  }
  return paths;
}

/** Boundary-aware containment: a bare `startsWith` would treat `<ws>/core-ai-evil` as inside `<ws>/core-ai`. */
export function isInside(parent: string, child: string): boolean {
  const relative = path.relative(parent, child);
  return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative);
}

export function parseRange(header: string | null, size: number): ByteRange | 'invalid' | null {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return 'invalid';
  const [, startText, endText] = match;
  if (startText === '' && endText === '') return 'invalid';
  let start: number;
  let end: number;
  if (startText === '') {
    const suffix = Number(endText);
    if (suffix === 0) return 'invalid';
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(startText);
    end = endText === '' ? size - 1 : Number(endText);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) return 'invalid';
  return { start, end: Math.min(end, size - 1) };
}

function realpathOrNull(target: string): string | null {
  try {
    return realpathSync(target);
  } catch {
    return null;
  }
}
