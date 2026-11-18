/**
 * Plugin registry transport: server metadata is fetched with the stored login, package bytes are
 * downloaded only from the URL the registry pins, and the digest is verified before the desktop
 * hands the archive to the plugin store.
 */
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream, promises as fs } from 'node:fs';
import * as http from 'node:http';
import * as https from 'node:https';
import path from 'node:path';
import type { AuthEntry } from '../auth/AuthStore';
import type { RegistryPlugin } from '../../shared/pluginRegistry';

export type { RegistryPlugin } from '../../shared/pluginRegistry';

const MAX_PACKAGE_BYTES = 256 * 1024 * 1024;

export class RegistryError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'RegistryError';
    this.code = code;
  }
}

interface RawRegistryPlugin {
  id?: unknown;
  name?: unknown;
  version?: unknown;
  description?: unknown;
  vendor?: unknown;
  homepage?: unknown;
  download_url?: unknown;
  sha256?: unknown;
  size?: unknown;
  required_capabilities?: unknown;
  optional_capabilities?: unknown;
  components?: unknown;
  updated_at?: unknown;
}

interface RawSearchResponse {
  plugins?: unknown;
  total?: unknown;
}

function normalizeServerUrl(value: string): string {
  return String(value ?? '').trim().replace(/\/+$/, '');
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function toPlugin(raw: unknown): RegistryPlugin | null {
  if (!raw || typeof raw !== 'object') return null;
  const entry = raw as RawRegistryPlugin;
  if (typeof entry.id !== 'string' || typeof entry.name !== 'string' || typeof entry.version !== 'string') return null;
  if (typeof entry.download_url !== 'string' || typeof entry.sha256 !== 'string') return null;
  return {
    id: entry.id,
    name: entry.name,
    version: entry.version,
    description: typeof entry.description === 'string' ? entry.description : null,
    vendor: typeof entry.vendor === 'string' ? entry.vendor : null,
    homepage: typeof entry.homepage === 'string' ? entry.homepage : null,
    downloadUrl: entry.download_url,
    sha256: entry.sha256,
    size: typeof entry.size === 'number' ? entry.size : null,
    requiredCapabilities: stringArray(entry.required_capabilities),
    optionalCapabilities: stringArray(entry.optional_capabilities),
    components: stringArray(entry.components),
    updatedAt: typeof entry.updated_at === 'string' ? entry.updated_at : null,
  };
}

function requestJson(entry: AuthEntry, pathname: string, timeoutMs = 15_000): Promise<unknown> {
  const target = new URL(pathname, `${normalizeServerUrl(entry.server_url)}/`);
  const transport = target.protocol === 'http:' ? http : https;
  return new Promise((resolve, reject) => {
    const request = transport.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || (target.protocol === 'http:' ? 80 : 443),
      path: `${target.pathname}${target.search}`,
      method: 'GET',
      headers: { Authorization: `Bearer ${entry.api_key}`, Accept: 'application/json' },
      rejectUnauthorized: entry.insecure !== true,
    }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk: string) => { body += chunk; });
      response.on('end', () => {
        const status = response.statusCode ?? 0;
        if (status !== 200) {
          reject(new RegistryError('registry_failed', `registry request failed (${status})`));
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch {
          reject(new RegistryError('registry_invalid', 'registry returned invalid JSON'));
        }
      });
    });
    request.setTimeout(timeoutMs, () => request.destroy(new RegistryError('registry_timeout', 'registry request timed out')));
    request.on('error', (error) => reject(error instanceof RegistryError ? error : new RegistryError('registry_unreachable', error.message)));
    request.end();
  });
}

export async function searchRegistryPlugins(entry: AuthEntry, query: string, limit = 50): Promise<{ plugins: RegistryPlugin[]; total: number }> {
  const params = new URLSearchParams();
  if (query.trim()) params.set('query', query.trim());
  params.set('limit', String(limit));
  const payload = await requestJson(entry, `/api/plugins?${params.toString()}`) as RawSearchResponse;
  const plugins = Array.isArray(payload?.plugins) ? payload.plugins.map(toPlugin).filter((plugin): plugin is RegistryPlugin => plugin !== null) : [];
  return { plugins, total: typeof payload?.total === 'number' ? payload.total : plugins.length };
}

export async function getRegistryPlugin(entry: AuthEntry, id: string): Promise<RegistryPlugin> {
  const plugin = toPlugin(await requestJson(entry, `/api/plugins/${encodeURIComponent(id)}`));
  if (!plugin) throw new RegistryError('registry_invalid', `registry returned an invalid plugin entry: ${id}`);
  return plugin;
}

/** Downloads and verifies a package; the caller owns (and removes) the returned temp file. */
export async function downloadRegistryPlugin(entry: AuthEntry, plugin: RegistryPlugin, downloadDir: string): Promise<string> {
  let target: URL;
  try {
    target = new URL(plugin.downloadUrl);
  } catch {
    throw new RegistryError('registry_invalid', 'plugin download URL is invalid');
  }
  if (target.protocol !== 'https:' && target.protocol !== 'http:') {
    throw new RegistryError('registry_invalid', 'plugin download URL must be http(s)');
  }
  if (!/^[a-f0-9]{64}$/i.test(plugin.sha256)) {
    throw new RegistryError('registry_invalid', 'plugin sha256 is invalid');
  }
  if (plugin.size !== null && (plugin.size <= 0 || plugin.size > MAX_PACKAGE_BYTES)) {
    throw new RegistryError('registry_invalid', 'plugin size is invalid');
  }

  await fs.mkdir(downloadDir, { recursive: true, mode: 0o700 });
  const destination = path.join(downloadDir, `${plugin.id}-${randomUUID()}.zip`);
  const serverOrigin = new URL(`${normalizeServerUrl(entry.server_url)}/`).origin;
  const sameOrigin = target.origin === serverOrigin;
  const transport = target.protocol === 'http:' ? http : https;
  const hash = createHash('sha256');
  let bytes = 0;

  try {
    await new Promise<void>((resolve, reject) => {
      const request = transport.request({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || (target.protocol === 'http:' ? 80 : 443),
        path: `${target.pathname}${target.search}`,
        method: 'GET',
        headers: sameOrigin ? { Authorization: `Bearer ${entry.api_key}` } : {},
        rejectUnauthorized: sameOrigin ? entry.insecure !== true : true,
      }, (response) => {
        const status = response.statusCode ?? 0;
        if (status !== 200) {
          response.resume();
          reject(new RegistryError('download_failed', `plugin download failed (${status})`));
          return;
        }
        const output = createWriteStream(destination, { mode: 0o600 });
        output.on('error', reject);
        response.on('data', (chunk: Buffer) => {
          bytes += chunk.length;
          if (bytes > MAX_PACKAGE_BYTES || (plugin.size !== null && bytes > plugin.size)) {
            request.destroy(new RegistryError('download_too_large', 'plugin package exceeds the registry size'));
            return;
          }
          hash.update(chunk);
        });
        response.on('error', reject);
        response.pipe(output);
        output.on('finish', () => resolve());
      });
      request.setTimeout(60_000, () => request.destroy(new RegistryError('download_timeout', 'plugin download timed out')));
      request.on('error', (error) => reject(error instanceof RegistryError ? error : new RegistryError('download_failed', error.message)));
      request.end();
    });

    const digest = hash.digest('hex');
    if (digest.toLowerCase() !== plugin.sha256.toLowerCase()) {
      throw new RegistryError('digest_mismatch', 'plugin package digest does not match the registry');
    }
    if (plugin.size !== null && bytes !== plugin.size) {
      throw new RegistryError('size_mismatch', 'plugin package size does not match the registry');
    }
    return destination;
  } catch (error) {
    await fs.rm(destination, { force: true }).catch(() => undefined);
    throw error;
  }
}
