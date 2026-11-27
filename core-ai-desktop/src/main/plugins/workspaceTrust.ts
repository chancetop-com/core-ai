/**
 * Sole writer of user-side workspace trust records (schemaVersion 2).
 *
 * The record lives in the plugin store, never in the workspace, so a repository cannot declare
 * itself trusted. The engine evaluates fingerprints strictly read-only; this store persists both
 * trusted fingerprints and explicit revoked tombstones (never deleting keys on revoke, so a
 * legacy CLI record cannot resurrect a revoked desktop workspace).
 */
import { randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import type { WorkspaceTrustMigration } from '../../shared/workspaceTrust';

const SCHEMA_VERSION = 2;
const TRUST_FILE = 'workspace-trust.json';

export interface WorkspaceTrustRecord {
  fingerprint: string;
  trustedAt: string;
}

export interface WorkspaceTrustRevokedRecord {
  state: 'revoked';
  revokedAt: string;
}

export type WorkspaceTrustEntry = WorkspaceTrustRecord | WorkspaceTrustRevokedRecord;

interface TrustDocument {
  schemaVersion: number;
  workspaces: Record<string, WorkspaceTrustEntry>;
}

function emptyDocument(): TrustDocument {
  return { schemaVersion: SCHEMA_VERSION, workspaces: {} };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isRevokedEntry(entry: WorkspaceTrustEntry): entry is WorkspaceTrustRevokedRecord {
  return 'state' in entry && entry.state === 'revoked';
}

function isSupportedSchemaVersion(value: unknown): boolean {
  return value === 1 || value === 2 || value === '1' || value === '2';
}

export class WorkspaceTrustStore {
  private readonly file: string;
  private queue: Promise<unknown> = Promise.resolve();

  constructor(root: string) {
    this.file = path.join(path.resolve(root), TRUST_FILE);
  }

  get filePath(): string {
    return this.file;
  }

  async read(key: string): Promise<WorkspaceTrustRecord | null> {
    const entry = await this.readEntry(key);
    if (!entry || isRevokedEntry(entry)) return null;
    return { ...entry };
  }

  async readEntry(key: string): Promise<WorkspaceTrustEntry | null> {
    const document = await this.load();
    const entry = document.workspaces[key];
    return entry ? { ...entry } : null;
  }

  async isRevoked(key: string): Promise<boolean> {
    const entry = await this.readEntry(key);
    return entry !== null && isRevokedEntry(entry);
  }

  async trust(key: string, fingerprint: string): Promise<void> {
    if (!key || !fingerprint) throw new Error('workspace trust needs a key and fingerprint');
    await this.mutate((document) => {
      document.workspaces[key] = { fingerprint, trustedAt: new Date().toISOString() };
      return true;
    });
  }

  /** Writes an explicit revoked tombstone; never deletes the key. */
  async revoke(key: string): Promise<void> {
    if (!key) throw new Error('workspace trust revoke needs a key');
    await this.mutate((document) => {
      document.workspaces[key] = { state: 'revoked', revokedAt: new Date().toISOString() };
      return true;
    });
  }

  /**
   * Idempotently persists a one-time legacy-to-desktop migration using the desktop fingerprint.
   * Never overwrites an existing desktop record or revoked tombstone, and leaves the legacy key untouched.
   */
  async applyMigration(migration: WorkspaceTrustMigration): Promise<boolean> {
    if (!migration?.desktopKey || !migration.desktopFingerprint) return false;
    return this.mutate((document) => {
      if (Object.prototype.hasOwnProperty.call(document.workspaces, migration.desktopKey)) {
        return false;
      }
      document.workspaces[migration.desktopKey] = {
        fingerprint: migration.desktopFingerprint,
        trustedAt: new Date().toISOString(),
      };
      return true;
    });
  }

  private async load(): Promise<TrustDocument> {
    try {
      const parsed: unknown = JSON.parse(await fs.readFile(this.file, 'utf8'));
      if (!isRecord(parsed) || !isSupportedSchemaVersion(parsed.schemaVersion) || !isRecord(parsed.workspaces)) {
        return emptyDocument();
      }
      const document = emptyDocument();
      for (const [key, value] of Object.entries(parsed.workspaces)) {
        if (!isRecord(value)) continue;
        if (value.state === 'revoked' && typeof value.fingerprint !== 'string') {
          document.workspaces[key] = {
            state: 'revoked',
            revokedAt: typeof value.revokedAt === 'string' ? value.revokedAt : new Date().toISOString(),
          };
        } else if (typeof value.fingerprint === 'string' && value.state !== 'revoked') {
          document.workspaces[key] = {
            fingerprint: value.fingerprint,
            trustedAt: typeof value.trustedAt === 'string' ? value.trustedAt : new Date().toISOString(),
          };
        }
      }
      return document;
    } catch {
      return emptyDocument();
    }
  }

  private async mutate(action: (document: TrustDocument) => boolean): Promise<boolean> {
    const run = this.queue.then(async () => {
      const document = await this.load();
      const changed = action(document);
      if (!changed) return false;
      document.schemaVersion = SCHEMA_VERSION;
      await fs.mkdir(path.dirname(this.file), { recursive: true, mode: 0o700 });
      const temp = `${this.file}.tmp-${process.pid}-${randomUUID()}`;
      const handle = await fs.open(temp, 'w', 0o600);
      try {
        await handle.writeFile(`${JSON.stringify(document, null, 2)}\n`, 'utf8');
        await handle.sync();
      } finally {
        await handle.close();
      }
      try {
        await fs.rename(temp, this.file);
      } catch {
        await fs.rm(this.file, { force: true }).catch(() => undefined);
        await fs.rename(temp, this.file);
      }
      return true;
    });
    this.queue = run.then(() => undefined, () => undefined);
    return run;
  }
}
