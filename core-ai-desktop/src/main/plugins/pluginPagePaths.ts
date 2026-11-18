// Pure path/URL rules for plugin pages. Kept Electron-free so node:test can exercise the escape
// checks directly; the page and asset protocols both resolve through these helpers.
import { realpathSync, statSync } from 'node:fs';
import path from 'node:path';

const CONTENT_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
};

export const PLUGIN_PAGE_CSP = [
  "default-src 'none'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: coreai-plugin-asset:",
  "media-src 'self' coreai-plugin-asset:",
  "font-src 'self' data:",
  "connect-src 'none'",
  "frame-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join('; ');

export function contentTypeFor(filePath: string): string {
  return CONTENT_TYPES[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream';
}

/** URL pathname -> decoded package-relative path, or null for malformed / escaping input. */
export function relativeFromPathname(pathname: string): string | null {
  const raw = pathname.replace(/^\/+/, '');
  if (!raw) return null;
  const segments: string[] = [];
  for (const segment of raw.split('/')) {
    let decoded: string;
    try {
      decoded = decodeURIComponent(segment);
    } catch {
      return null;
    }
    if (!decoded || decoded === '.' || decoded === '..') return null;
    if (decoded.includes('\\') || decoded.includes('\0')) return null;
    if (/^[a-zA-Z]:/.test(decoded)) return null;
    segments.push(decoded);
  }
  return segments.join('/');
}

export function isInside(parent: string, child: string): boolean {
  const relative = path.relative(parent, child);
  return relative !== '' && !relative.startsWith('..') && !path.isAbsolute(relative);
}

function realpathOrNull(target: string): string | null {
  try {
    return realpathSync(target);
  } catch {
    return null;
  }
}

/** Resolve a file under a package root; symlinks and `..` cannot leave the root. */
export function resolvePackageFile(root: string, relativePath: string): string | null {
  const canonicalRoot = realpathOrNull(path.resolve(root));
  if (!canonicalRoot) return null;
  const target = realpathOrNull(path.resolve(canonicalRoot, relativePath));
  if (!target) return null;
  if (!isInside(canonicalRoot, target)) return null;
  try {
    return statSync(target).isFile() ? target : null;
  } catch {
    return null;
  }
}

/** Only generated media is exposed to a plugin page, never arbitrary workspace files. */
export function resolveMediaFile(workspace: string, relativePath: string): string | null {
  const normalized = relativePath.replace(/\\/g, '/');
  if (!normalized.startsWith('.core-ai/media/')) return null;
  if (!/\.(png|jpe?g|gif|webp|mp4|webm|mov|m4v)$/i.test(normalized)) return null;
  const mediaRoot = path.resolve(workspace, '.core-ai', 'media');
  const canonicalRoot = realpathOrNull(mediaRoot);
  if (!canonicalRoot) return null;
  const target = realpathOrNull(path.resolve(workspace, normalized));
  if (!target || !isInside(canonicalRoot, target)) return null;
  try {
    return statSync(target).isFile() ? target : null;
  } catch {
    return null;
  }
}

export function assetUrl(relativePath: string): string {
  return `coreai-plugin-asset://assets/${relativePath.split('/').map(encodeURIComponent).join('/')}`;
}

export function pageUrl(view: string): string {
  return `coreai-plugin-page://page/${view.split('/').map(encodeURIComponent).join('/')}`;
}
