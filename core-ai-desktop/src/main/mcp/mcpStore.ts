// Desktop-owned MCP configuration store. Everything lives under userData/mcp, one document per
// workspace, so a workspace never sees another workspace's servers, secrets or processes. The
// workspace `.core-ai/MCP.json` and the user-level files are never read or written here; they are
// only imported once, on explicit user action.
import { createHash, randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { safeStorage } from 'electron';

const SCHEMA_VERSION = 1;

export interface McpServerDraft {
  id?: string;
  name: string;
  enabled: boolean;
  config: Record<string, unknown>;
}

export interface McpServerRecord extends McpServerDraft {
  id: string;
  createdAt: string;
  updatedAt: string;
}

export interface McpServerView extends McpServerRecord {
  secretNames: string[];
}

export interface WorkspaceMcpDocument {
  schemaVersion: number;
  workspaceKey: string;
  servers: McpServerRecord[];
}

interface SecretsDocument {
  schemaVersion: number;
  workspaceKey: string;
  /** serverId -> secret name -> base64 ciphertext */
  servers: Record<string, Record<string, string>>;
}

export interface McpStoreResult {
  servers: McpServerRecord[];
  secretNames: Record<string, string[]>;
}

function emptyDocument(workspaceKey: string): WorkspaceMcpDocument {
  return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers: [] };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function workspaceId(workspaceKey: string): string {
  return createHash('sha256').update(workspaceKey).digest('hex');
}

export class McpStore {
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly root: string) {}

  get rootPath(): string {
    return this.root;
  }

  configPath(workspaceKey: string): string {
    return path.join(this.root, 'workspaces', `${workspaceId(workspaceKey)}.json`);
  }

  secretsPath(workspaceKey: string): string {
    return path.join(this.root, 'secrets', `${workspaceId(workspaceKey)}.json`);
  }

  async list(workspaceKey: string): Promise<McpStoreResult> {
    const document = await this.readConfig(workspaceKey);
    const secrets = await this.readSecrets(workspaceKey);
    const secretNames: Record<string, string[]> = {};
    for (const server of document.servers) {
      secretNames[server.id] = Object.keys(secrets.servers[server.id] ?? {}).sort();
    }
    return { servers: document.servers, secretNames };
  }

  async revealSecrets(workspaceKey: string, serverId: string): Promise<Record<string, string>> {
    const secrets = await this.readSecrets(workspaceKey);
    const values = secrets.servers[serverId] ?? {};
    const out: Record<string, string> = {};
    for (const [name, ciphertext] of Object.entries(values)) {
      out[name] = this.decrypt(ciphertext);
    }
    return out;
  }

  async serverSecrets(workspaceKey: string, serverId: string): Promise<Record<string, string>> {
    return this.revealSecrets(workspaceKey, serverId);
  }

  /** Encrypts a full secret map for a server; a null value deletes that secret. */
  encryptSecrets(values: Record<string, string | null>): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [name, value] of Object.entries(values)) {
      if (value === null || value === undefined) continue;
      out[name] = this.encrypt(value);
    }
    return out;
  }

  async save(
    workspaceKey: string,
    draft: McpServerDraft,
    secretPatch?: Record<string, string | null>,
  ): Promise<McpServerRecord> {
    return this.mutate(workspaceKey, async () => {
      const document = await this.readConfig(workspaceKey);
      const now = new Date().toISOString();
      const existing = draft.id ? document.servers.find((server) => server.id === draft.id) : undefined;
      if (draft.id && !existing) throw new Error('MCP server not found');
      const duplicate = document.servers.find((server) => server.name === draft.name && server.id !== draft.id);
      if (duplicate) throw new Error(`an MCP server named ${draft.name} already exists in this workspace`);
      const record: McpServerRecord = {
        ...draft,
        id: existing?.id ?? randomUUID(),
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
      };
      document.servers = existing
        ? document.servers.map((server) => (server.id === record.id ? record : server))
        : [...document.servers, record];
      const secrets = await this.readSecrets(workspaceKey);
      if (secretPatch) {
        const current = secrets.servers[record.id] ?? {};
        for (const [name, value] of Object.entries(secretPatch)) {
          if (value === null || value === undefined) delete current[name];
          else current[name] = this.encrypt(value);
        }
        secrets.servers[record.id] = current;
      }
      await this.writeJson(this.configPath(workspaceKey), document);
      await this.writeJson(this.secretsPath(workspaceKey), secrets);
      return record;
    });
  }

  async setEnabled(workspaceKey: string, id: string, enabled: boolean): Promise<McpServerRecord> {
    const { servers } = await this.list(workspaceKey);
    const server = servers.find((entry) => entry.id === id);
    if (!server) throw new Error('MCP server not found');
    return this.save(workspaceKey, { ...server, enabled });
  }

  /**
   * Copies imported definitions into this workspace only. `skip` keeps the existing server,
   * `replace` overwrites its config (secrets are untouched), `rename` adds a numbered copy.
   */
  async importServers(
    workspaceKey: string,
    servers: McpServerDraft[],
    mode: 'skip' | 'replace' | 'rename',
  ): Promise<{ imported: string[]; skipped: string[]; renamed: string[] }> {
    return this.mutate(workspaceKey, async () => {
      const document = await this.readConfig(workspaceKey);
      const imported: string[] = [];
      const skipped: string[] = [];
      const renamed: string[] = [];
      const now = new Date().toISOString();
      for (const draft of servers) {
        const existing = document.servers.find((server) => server.name === draft.name);
        if (existing && mode === 'skip') {
          skipped.push(draft.name);
          continue;
        }
        if (existing && mode === 'replace') {
          document.servers = document.servers.map((server) => (server.id === existing.id
            ? { ...server, config: draft.config, enabled: true, updatedAt: now }
            : server));
          imported.push(draft.name);
          continue;
        }
        const name = existing ? this.uniqueName(document.servers, draft.name) : draft.name;
        if (existing) renamed.push(name);
        document.servers.push({ ...draft, id: randomUUID(), name, enabled: true, createdAt: now, updatedAt: now });
        imported.push(name);
      }
      if (imported.length > 0) await this.writeJson(this.configPath(workspaceKey), document);
      return { imported, skipped, renamed };
    });
  }

  private uniqueName(servers: McpServerRecord[], name: string): string {
    const taken = new Set(servers.map((server) => server.name));
    for (let index = 2; index < 100; index++) {
      const candidate = `${name}-${index}`;
      if (!taken.has(candidate)) return candidate;
    }
    throw new Error(`no free server name for ${name}`);
  }

  async remove(workspaceKey: string, id: string): Promise<void> {
    await this.mutate(workspaceKey, async () => {
      const document = await this.readConfig(workspaceKey);
      document.servers = document.servers.filter((server) => server.id !== id);
      const secrets = await this.readSecrets(workspaceKey);
      delete secrets.servers[id];
      await this.writeJson(this.configPath(workspaceKey), document);
      await this.writeJson(this.secretsPath(workspaceKey), secrets);
    });
  }

  /** Content hash used as the optimistic-concurrency token in save requests. */
  hashOf(servers: McpServerRecord[]): string {
    const payload = servers.map((server) => `${server.id}:${server.updatedAt}`).sort().join('|');
    return createHash('sha256').update(payload).digest('hex');
  }

  async copyForRollback(workspaceKey: string): Promise<{ config: string; secrets: string }> {
    return { config: await this.readText(this.configPath(workspaceKey)), secrets: await this.readText(this.secretsPath(workspaceKey)) };
  }

  async restore(workspaceKey: string, snapshot: { config: string; secrets: string }): Promise<void> {
    await this.writeText(this.configPath(workspaceKey), snapshot.config);
    await this.writeText(this.secretsPath(workspaceKey), snapshot.secrets);
  }

  private async mutate<T>(workspaceKey: string, action: () => Promise<T>): Promise<T> {
    const run = this.queue.then(action);
    this.queue = run.then(() => undefined, () => undefined);
    return run;
  }

  private encrypt(value: string): string {
    if (!safeStorage.isEncryptionAvailable()) {
      throw new Error('the operating system key store is unavailable; refusing to store MCP secrets in plain text');
    }
    return safeStorage.encryptString(value).toString('base64');
  }

  private decrypt(ciphertext: string): string {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('the operating system key store is unavailable');
    return safeStorage.decryptString(Buffer.from(ciphertext, 'base64'));
  }

  private async readConfig(workspaceKey: string): Promise<WorkspaceMcpDocument> {
    const raw = await this.readText(this.configPath(workspaceKey));
    if (!raw) return emptyDocument(workspaceKey);
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!isRecord(parsed) || parsed.schemaVersion !== SCHEMA_VERSION || !Array.isArray(parsed.servers)) {
        return emptyDocument(workspaceKey);
      }
      const servers = parsed.servers.filter(isRecord).map((server) => server as unknown as McpServerRecord);
      return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers };
    } catch {
      return emptyDocument(workspaceKey);
    }
  }

  private async readSecrets(workspaceKey: string): Promise<SecretsDocument> {
    const raw = await this.readText(this.secretsPath(workspaceKey));
    if (!raw) return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers: {} };
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!isRecord(parsed) || parsed.schemaVersion !== SCHEMA_VERSION || !isRecord(parsed.servers)) {
        return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers: {} };
      }
      return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers: parsed.servers as Record<string, Record<string, string>> };
    } catch {
      return { schemaVersion: SCHEMA_VERSION, workspaceKey, servers: {} };
    }
  }

  private async readText(file: string): Promise<string> {
    try {
      return await fs.readFile(file, 'utf8');
    } catch {
      return '';
    }
  }

  private async writeJson(file: string, value: unknown): Promise<void> {
    await this.writeText(file, `${JSON.stringify(value, null, 2)}\n`);
  }

  private async writeText(file: string, value: string): Promise<void> {
    await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
    const temp = `${file}.tmp-${process.pid}-${randomUUID()}`;
    await fs.writeFile(temp, value, { mode: 0o600 });
    try {
      await fs.rename(temp, file);
    } catch {
      await fs.rm(file, { force: true }).catch(() => undefined);
      await fs.rename(temp, file);
    }
  }
}
