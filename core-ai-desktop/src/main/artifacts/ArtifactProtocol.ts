// coreai-artifact:// — serves local artifacts to the renderer without base64 over IPC. The scheme is
// registered as privileged before app ready (module top level); the handler is installed on ready. A
// per-workspace token occupies the FIRST PATH segment, never the host: a `standard` scheme lowercases
// and IDN-normalizes the host, so a token there would not round-trip. Files under {workspace}/.core-ai
// and paths registered in {workspace}/.core-ai/artifacts/manifest.jsonl (the engine's submit_artifacts)
// are served; `..`, absolute paths and symlink escapes are rejected.
import { protocol } from 'electron';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { statSync } from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import {
  contentTypeFor,
  decodeRelativePath,
  isArtifactPathAllowed,
  parseRange,
  registeredArtifactPaths,
  resolveInsideArtifacts,
  resolveInsideWorkspace,
  tokenOf,
} from './artifactPath.js';

const SCHEME = 'coreai-artifact';

/** The renderer is a `file://` page; a fetch to this scheme is cross-origin and needs an allow-origin. */
const CORS_HEADERS = { 'Access-Control-Allow-Origin': '*' };

const tokenByWorkspace = new Map<string, string>();
const workspaceByToken = new Map<string, string>();

/** Must run before app ready; a custom scheme is not `'self'` and needs explicit privileges. */
export function registerArtifactScheme(): void {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: SCHEME,
      privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true },
    },
  ]);
}

/** Base URL for the renderer; stable per workspace (sha256 of the resolved path). */
export function artifactBase(workspace: string): { baseUrl: string } {
  const canonical = path.resolve(workspace);
  let token = tokenByWorkspace.get(canonical);
  if (!token) {
    token = createHash('sha256').update(canonical).digest('hex').slice(0, 32);
    tokenByWorkspace.set(canonical, token);
    workspaceByToken.set(token, canonical);
  }
  return { baseUrl: `${SCHEME}://artifacts/${token}` };
}

export function registerArtifactProtocol(): void {
  protocol.handle(SCHEME, (request) => serve(request));
}

async function serve(request: Request): Promise<Response> {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: { ...CORS_HEADERS, 'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS' } });
  }
  let url: URL;
  try {
    url = new URL(request.url);
  } catch {
    return new Response('bad request', { status: 400, headers: CORS_HEADERS });
  }
  const workspace = workspaceByToken.get(tokenOf(url.pathname));
  if (!workspace) return new Response('not found', { status: 404, headers: CORS_HEADERS });

  const relativePath = decodeRelativePath(url.pathname);
  if (relativePath === null) return new Response('bad request', { status: 400, headers: CORS_HEADERS });

  let target = resolveInsideArtifacts(workspace, relativePath);
  if (!target && registeredArtifactPaths(workspace).has(relativePath)) {
    target = resolveInsideWorkspace(workspace, relativePath);
  }
  if (!target) {
    // An artifact deleted after it was listed is gone (404); a path this workspace never served is not ours (403).
    const allowed = isArtifactPathAllowed(workspace, relativePath);
    return new Response(allowed ? 'not found' : 'forbidden', { status: allowed ? 404 : 403, headers: CORS_HEADERS });
  }

  let size: number;
  try {
    const stat = statSync(target);
    if (!stat.isFile()) return new Response('not found', { status: 404, headers: CORS_HEADERS });
    size = stat.size;
  } catch {
    return new Response('not found', { status: 404, headers: CORS_HEADERS });
  }

  const range = parseRange(request.headers.get('range'), size);
  if (range === 'invalid') {
    return new Response(null, { status: 416, headers: { ...CORS_HEADERS, 'Content-Range': `bytes */${size}` } });
  }
  const start = range ? range.start : 0;
  const end = range ? range.end : Math.max(0, size - 1);
  const headers: Record<string, string> = {
    ...CORS_HEADERS,
    'Content-Type': contentTypeFor(target),
    'Accept-Ranges': 'bytes',
    'Content-Length': String(size === 0 ? 0 : end - start + 1),
    'Cache-Control': 'no-store',
  };
  if (range) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;

  const status = range ? 206 : 200;
  if (request.method === 'HEAD' || size === 0) {
    return new Response(null, { status, headers });
  }
  const stream = Readable.toWeb(createReadStream(target, { start, end })) as unknown as ReadableStream;
  return new Response(stream, { status, headers });
}
