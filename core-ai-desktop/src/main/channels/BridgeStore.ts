// P0 bookkeeping for the IM bridge: which desktop session owns an OCG sessionKey, and
// which inbound messages were already answered. P1 replaces this with the journal/route
// stores described in docs/cn/design-desktop-ocg-channels.md; the file format stays local.
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export interface BridgeSessionRecord {
  sessionId: string;
  workspace: string;
  title: string;
  updatedAt: number;
}

export interface BridgeHandledRecord {
  state: 'replied' | 'acked' | 'failed';
  at: number;
}

interface BridgeStateFile {
  sessions: Record<string, BridgeSessionRecord>;
  handled: Record<string, BridgeHandledRecord>;
}

const MAX_HANDLED = 500;

export class BridgeStore {
  private state: BridgeStateFile = { sessions: {}, handled: {} };

  constructor(private readonly file: string) {
    this.load();
  }

  getSession(sessionKey: string): BridgeSessionRecord | null {
    return this.state.sessions[sessionKey] ?? null;
  }

  setSession(sessionKey: string, record: BridgeSessionRecord): void {
    this.state.sessions[sessionKey] = record;
    this.save();
  }

  isHandled(messageId: string): boolean {
    return Boolean(this.state.handled[messageId]);
  }

  markHandled(messageId: string, state: BridgeHandledRecord['state']): void {
    this.state.handled[messageId] = { state, at: Date.now() };
    const entries = Object.entries(this.state.handled).sort((left, right) => left[1].at - right[1].at);
    for (const [id] of entries.slice(0, Math.max(0, entries.length - MAX_HANDLED))) delete this.state.handled[id];
    this.save();
  }

  private load(): void {
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf8')) as Partial<BridgeStateFile>;
      if (parsed.sessions && typeof parsed.sessions === 'object') this.state.sessions = parsed.sessions;
      if (parsed.handled && typeof parsed.handled === 'object') this.state.handled = parsed.handled;
    } catch { /* first run */ }
  }

  private save(): void {
    try {
      mkdirSync(path.dirname(this.file), { recursive: true });
      const temporary = `${this.file}.tmp`;
      writeFileSync(temporary, JSON.stringify(this.state, null, 2), 'utf8');
      renameSync(temporary, this.file);
    } catch { /* bookkeeping must never break message handling */ }
  }
}
