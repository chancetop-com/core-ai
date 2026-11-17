/**
 * User-side workspace trust records.
 *
 * The record lives in the plugin store, never in the workspace, so a repository cannot declare
 * itself trusted. The fingerprint is produced by the engine; the desktop stores it verbatim and
 * never invents one. Writing is atomic and serialized within this process.
 */
import { randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';

const SCHEMA_VERSION = 1;
const TRUST_FILE = 'workspace-trust.json';

interface TrustDocument {
  schemaVersion: number;
  workspaces: Record<string, { fingerprint: string; trustedAt: string }>;
}

export interface WorkspaceTrustRecord {
  fingerprint: string;
  trustedAt: string;
}

function emptyDocument(): TrustDocument {
  return { schemaVersion: SCHEMA_VERSION, workspaces: {} };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
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
    const document = await this.load();
    const record = document.workspaces[key];
    return record ? { ...record } : null;
  }

  async trust(key: string, fingerprint: string): Promise<void> {
    if (!key || !fingerprint) throw new Error('workspace trust needs a key and fingerprint');
    await this.mutate((document) => {
      document.workspaces[key] = { fingerprint, trustedAt: new Date().toISOString() };
    });
  }

  async revoke(key: string): Promise<void> {
    await this.mutate((document) => {
      delete document.workspaces[key];
    });
  }

  private async load(): Promise<TrustDocument> {
    try {
      const parsed: unknown = JSON.parse(await fs.readFile(this.file, 'utf8'));
      if (!isRecord(parsed) || parsed.schemaVersion !== SCHEMA_VERSION || !isRecord(parsed.workspaces)) {
        return emptyDocument();
      }
      const document = emptyDocument();
      for (const [key, value] of Object.entries(parsed.workspaces)) {
        if (!isRecord(value) || typeof value.fingerprint !== 'string') continue;
        document.workspaces[key] = {
          fingerprint: value.fingerprint,
          trustedAt: typeof value.trustedAt === 'string' ? value.trustedAt : new Date().toISOString(),
        };
      }
      return document;
    } catch {
      // A corrupt trust file must not crash the app; it simply grants nothing.
      return emptyDocument();
    }
  }

  private async mutate(action: (document: TrustDocument) => void): Promise<void> {
    const run = this.queue.then(async () => {
      const document = await this.load();
      action(document);
      await fs.mkdir(path.dirname(this.file), { recursive: true, mode: 0o700 });
      const temp = `${this.file}.tmp-${process.pid}-${randomUUID()}`;
      await fs.writeFile(temp, `${JSON.stringify(document, null, 2)}\n`, { mode: 0o600 });
      try {
        await fs.rename(temp, this.file);
      } catch {
        await fs.rm(this.file, { force: true }).catch(() => undefined);
        await fs.rename(temp, this.file);
      }
    });
    this.queue = run.then(() => undefined, () => undefined);
    await run;
  }
}
