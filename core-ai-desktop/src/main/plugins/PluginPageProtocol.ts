// Two isolated schemes for plugin pages: the page package (HTML/CSS/JS) and generated media.
// Both are served from a dedicated session partition, so the page has no cookie, storage or
// network context shared with the host renderer. Their privileges live in shared/protocolSchemes,
// which registers every custom scheme in one call.
import type { Session } from 'electron';
import { createReadStream, statSync } from 'node:fs';
import { Readable } from 'node:stream';
import { PLUGIN_ASSET_SCHEME, PLUGIN_PAGE_SCHEME } from '../../shared/protocolSchemes';
import {
  PLUGIN_PAGE_CSP,
  contentTypeFor,
  relativeFromPathname,
  resolveMediaFile,
  resolvePackageFile,
} from './pluginPagePaths.js';

export function installPluginPageProtocol(ses: Session, packageDir: string): void {
  ses.protocol.handle(PLUGIN_PAGE_SCHEME, (request) => serveFile(request, (relative) =>
    resolvePackageFile(packageDir, relative)));
}

export function installPluginAssetProtocol(ses: Session, workspace: string): void {
  ses.protocol.handle(PLUGIN_ASSET_SCHEME, (request) => serveFile(request, (relative) =>
    resolveMediaFile(workspace, relative)));
}

function unhandle(ses: Session, scheme: string): void {
  try {
    ses.protocol.unhandle(scheme);
  } catch {
    // not installed for this partition
  }
}

export function removePluginPageProtocols(ses: Session): void {
  unhandle(ses, PLUGIN_PAGE_SCHEME);
  unhandle(ses, PLUGIN_ASSET_SCHEME);
}

async function serveFile(request: Request, resolve: (relative: string) => string | null): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response('method not allowed', { status: 405 });
  }
  let url: URL;
  try {
    url = new URL(request.url);
  } catch {
    return new Response('bad request', { status: 400 });
  }
  const relative = relativeFromPathname(url.pathname);
  if (!relative) return new Response('bad request', { status: 400 });
  const target = resolve(relative);
  if (!target) return new Response('not found', { status: 404 });

  let size: number;
  try {
    size = statSync(target).size;
  } catch {
    return new Response('not found', { status: 404 });
  }
  const contentType = contentTypeFor(target);
  const headers: Record<string, string> = {
    'Content-Type': contentType,
    'Content-Length': String(size),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
  };
  if (contentType.startsWith('text/html')) headers['Content-Security-Policy'] = PLUGIN_PAGE_CSP;
  if (request.method === 'HEAD' || size === 0) return new Response(null, { status: 200, headers });
  const stream = Readable.toWeb(createReadStream(target)) as unknown as ReadableStream;
  return new Response(stream, { status: 200, headers });
}
