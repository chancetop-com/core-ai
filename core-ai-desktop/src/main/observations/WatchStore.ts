// Durable Watch + Outbox store. One writer (the main process), short transactions, no network I/O
// inside them: a match and its notification are committed together before the dispatcher runs.
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export interface WatchTarget {
  channel: string;
  accountId: string;
  target: string;
  chatType: string;
}

export interface CreateWatchInput {
  watchId: string;
  originId: string;
  ownerSessionId: string;
  sourceWorkspace: string;
  sourceSessionId: string;
  taskRunId: string;
  runGeneration: number;
  scope: string;
  action: string;
  sourceAlias: string | null;
  target: WatchTarget;
  expiresAt: number;
}

export interface WatchRecord extends CreateWatchInput {
  state: string;
  createdAt: number;
  matchedEventId: string | null;
  matchedAt: number | null;
  lastError: string | null;
  deliveryState: string | null;
}

export interface DeliveryRecord {
  deliveryId: string;
  watchId: string;
  eventId: string;
  channel: string;
  accountId: string;
  target: string;
  chatType: string;
  body: string;
  state: string;
  attempts: number;
  lastError: string | null;
  createdAt: number;
  updatedAt: number;
}

type Row = Record<string, unknown>;

export class WatchStore {
  static open(file: string): WatchStore {
    mkdirSync(path.dirname(file), { recursive: true });
    const db = new DatabaseSync(file);
    db.exec('PRAGMA journal_mode = WAL');
    db.exec('PRAGMA synchronous = FULL');
    const store = new WatchStore(db);
    store.migrate();
    return store;
  }

  private constructor(private readonly db: DatabaseSync) {}

  private migrate(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS watches (
        watch_id TEXT PRIMARY KEY,
        origin_id TEXT NOT NULL,
        owner_session_id TEXT NOT NULL,
        source_workspace TEXT NOT NULL,
        source_session_id TEXT NOT NULL,
        task_run_id TEXT NOT NULL,
        run_generation INTEGER NOT NULL,
        scope TEXT NOT NULL,
        action TEXT NOT NULL,
        source_alias TEXT,
        channel TEXT NOT NULL,
        account_id TEXT NOT NULL,
        target TEXT NOT NULL,
        chat_type TEXT NOT NULL,
        state TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        matched_event_id TEXT,
        matched_at INTEGER,
        last_error TEXT
      );
      CREATE INDEX IF NOT EXISTS watches_owner ON watches(owner_session_id, created_at);
      CREATE INDEX IF NOT EXISTS watches_run ON watches(source_workspace, source_session_id, task_run_id, run_generation, state);
      CREATE TABLE IF NOT EXISTS deliveries (
        delivery_id TEXT PRIMARY KEY,
        watch_id TEXT NOT NULL,
        event_id TEXT NOT NULL,
        channel TEXT NOT NULL,
        account_id TEXT NOT NULL,
        target TEXT NOT NULL,
        chat_type TEXT NOT NULL,
        body TEXT NOT NULL,
        state TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0,
        next_attempt_at INTEGER,
        last_error TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE UNIQUE INDEX IF NOT EXISTS deliveries_watch_event ON deliveries(watch_id, event_id);
      CREATE INDEX IF NOT EXISTS deliveries_state ON deliveries(state, created_at);
    `);
  }

  private tx<T>(work: () => T): T {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = work();
      this.db.exec('COMMIT');
      return result;
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  createActiveWatch(input: CreateWatchInput): WatchRecord {
    const now = Date.now();
    this.tx(() => {
      this.db.prepare(`
        INSERT INTO watches (watch_id, origin_id, owner_session_id, source_workspace, source_session_id,
          task_run_id, run_generation, scope, action, source_alias, channel, account_id, target, chat_type,
          state, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?)
      `).run(input.watchId, input.originId, input.ownerSessionId, input.sourceWorkspace, input.sourceSessionId,
        input.taskRunId, input.runGeneration, input.scope, input.action, input.sourceAlias, input.target.channel,
        input.target.accountId, input.target.target, input.target.chatType, now, input.expiresAt);
    });
    return this.getWatch(input.watchId)!;
  }

  getWatch(watchId: string): WatchRecord | null {
    const row = this.db.prepare(`
      SELECT w.*, (SELECT d.state FROM deliveries d WHERE d.watch_id = w.watch_id ORDER BY d.created_at DESC LIMIT 1) AS delivery_state
      FROM watches w WHERE w.watch_id = ?
    `).get(watchId) as Row | undefined;
    return row ? toWatch(row) : null;
  }

  findActiveWatch(sourceWorkspace: string, sourceSessionId: string, taskRunId: string, runGeneration: number, scope: string): WatchRecord | null {
    const row = this.db.prepare(`
      SELECT w.*, NULL AS delivery_state FROM watches w
      WHERE w.state = 'active' AND w.source_workspace = ? AND w.source_session_id = ?
        AND w.task_run_id = ? AND w.run_generation = ? AND w.scope = ?
      ORDER BY w.created_at DESC LIMIT 1
    `).get(sourceWorkspace, sourceSessionId, taskRunId, runGeneration, scope) as Row | undefined;
    return row ? toWatch(row) : null;
  }

  findActiveWatchesForEnded(sourceWorkspace: string, sourceSessionId: string, taskRunId: string, runGeneration: number): WatchRecord[] {
    const rows = this.db.prepare(`
      SELECT w.*, NULL AS delivery_state FROM watches w
      WHERE w.state = 'active' AND w.source_workspace = ? AND w.source_session_id = ?
        AND w.task_run_id = ? AND w.run_generation = ?
      ORDER BY w.created_at ASC
    `).all(sourceWorkspace, sourceSessionId, taskRunId, runGeneration) as Row[];
    return rows.map(toWatch);
  }

  findWatchByRun(ownerSessionId: string, sourceSessionId: string, taskRunId: string, runGeneration: number, scope: string): WatchRecord | null {
    const row = this.db.prepare(`
      SELECT w.*, (SELECT d.state FROM deliveries d WHERE d.watch_id = w.watch_id ORDER BY d.created_at DESC LIMIT 1) AS delivery_state
      FROM watches w
      WHERE w.owner_session_id = ? AND w.source_session_id = ? AND w.task_run_id = ?
        AND w.run_generation = ? AND w.scope = ?
      ORDER BY w.created_at DESC LIMIT 1
    `).get(ownerSessionId, sourceSessionId, taskRunId, runGeneration, scope) as Row | undefined;
    return row ? toWatch(row) : null;
  }

  listWatches(ownerSessionId: string, limit: number): WatchRecord[] {
    const rows = this.db.prepare(`
      SELECT w.*, (SELECT d.state FROM deliveries d WHERE d.watch_id = w.watch_id ORDER BY d.created_at DESC LIMIT 1) AS delivery_state
      FROM watches w WHERE w.owner_session_id = ?
      ORDER BY w.created_at DESC LIMIT ?
    `).all(ownerSessionId, limit) as Row[];
    return rows.map(toWatch);
  }

  listActiveWatches(limit: number): WatchRecord[] {
    const rows = this.db.prepare(`
      SELECT w.*, NULL AS delivery_state FROM watches w
      WHERE w.state = 'active' ORDER BY w.created_at ASC LIMIT ?
    `).all(limit) as Row[];
    return rows.map(toWatch);
  }

  matchWatch(watchId: string, eventId: string, body: string): DeliveryRecord | null {
    const deliveryId = `delivery-${randomUUID()}`;
    const now = Date.now();
    return this.tx(() => {
      const changed = this.db.prepare(
        "UPDATE watches SET state = 'matched', matched_event_id = ?, matched_at = ? WHERE watch_id = ? AND state = 'active'",
      ).run(eventId, now, watchId);
      if (Number(changed.changes) === 0) return null;
      const watch = this.getWatch(watchId);
      if (!watch) return null;
      this.db.prepare(`
        INSERT INTO deliveries (delivery_id, watch_id, event_id, channel, account_id, target, chat_type, body,
          state, attempts, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued', 0, ?, ?, ?)
      `).run(deliveryId, watchId, eventId, watch.target.channel, watch.target.accountId, watch.target.target,
        watch.target.chatType, body, now, now, now);
      return this.getDelivery(deliveryId);
    });
  }

  createMatchedWatch(input: CreateWatchInput, eventId: string, body: string): DeliveryRecord {
    const deliveryId = `delivery-${randomUUID()}`;
    const now = Date.now();
    this.tx(() => {
      this.db.prepare(`
        INSERT INTO watches (watch_id, origin_id, owner_session_id, source_workspace, source_session_id,
          task_run_id, run_generation, scope, action, source_alias, channel, account_id, target, chat_type,
          state, created_at, expires_at, matched_event_id, matched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'matched', ?, ?, ?, ?)
      `).run(input.watchId, input.originId, input.ownerSessionId, input.sourceWorkspace, input.sourceSessionId,
        input.taskRunId, input.runGeneration, input.scope, input.action, input.sourceAlias, input.target.channel,
        input.target.accountId, input.target.target, input.target.chatType, now, input.expiresAt, eventId, now);
      this.db.prepare(`
        INSERT INTO deliveries (delivery_id, watch_id, event_id, channel, account_id, target, chat_type, body,
          state, attempts, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued', 0, ?, ?, ?)
      `).run(deliveryId, input.watchId, eventId, input.target.channel, input.target.accountId, input.target.target,
        input.target.chatType, body, now, now, now);
    });
    return this.getDelivery(deliveryId)!;
  }

  getDelivery(deliveryId: string): DeliveryRecord | null {
    const row = this.db.prepare('SELECT * FROM deliveries WHERE delivery_id = ?').get(deliveryId) as Row | undefined;
    return row ? toDelivery(row) : null;
  }

  listQueuedDeliveries(limit: number): DeliveryRecord[] {
    const rows = this.db.prepare(
      "SELECT * FROM deliveries WHERE state = 'queued' ORDER BY created_at ASC LIMIT ?",
    ).all(limit) as Row[];
    return rows.map(toDelivery);
  }

  claimDelivery(deliveryId: string): boolean {
    const result = this.db.prepare(
      "UPDATE deliveries SET state = 'dispatching', updated_at = ? WHERE delivery_id = ? AND state = 'queued'",
    ).run(Date.now(), deliveryId);
    return Number(result.changes) > 0;
  }

  finishDelivery(deliveryId: string, state: string, error: string | null): void {
    this.db.prepare(`
      UPDATE deliveries SET state = ?, attempts = attempts + 1, last_error = ?, updated_at = ?
      WHERE delivery_id = ?
    `).run(state, error, Date.now(), deliveryId);
  }

  cancelWatch(watchId: string): WatchRecord | null {
    this.tx(() => {
      this.db.prepare("UPDATE watches SET state = 'cancelled' WHERE watch_id = ? AND state IN ('active', 'matched')").run(watchId);
      this.db.prepare("UPDATE deliveries SET state = 'cancelled', updated_at = ? WHERE watch_id = ? AND state = 'queued'")
        .run(Date.now(), watchId);
    });
    return this.getWatch(watchId);
  }

  close(): void {
    this.db.close();
  }
}

function toWatch(row: Row): WatchRecord {
  return {
    watchId: String(row.watch_id),
    originId: String(row.origin_id),
    ownerSessionId: String(row.owner_session_id),
    sourceWorkspace: String(row.source_workspace),
    sourceSessionId: String(row.source_session_id),
    taskRunId: String(row.task_run_id),
    runGeneration: Number(row.run_generation),
    scope: String(row.scope),
    action: String(row.action),
    sourceAlias: row.source_alias == null ? null : String(row.source_alias),
    target: {
      channel: String(row.channel),
      accountId: String(row.account_id),
      target: String(row.target),
      chatType: String(row.chat_type),
    },
    state: String(row.state),
    createdAt: Number(row.created_at),
    expiresAt: Number(row.expires_at),
    matchedEventId: row.matched_event_id == null ? null : String(row.matched_event_id),
    matchedAt: row.matched_at == null ? null : Number(row.matched_at),
    lastError: row.last_error == null ? null : String(row.last_error),
    deliveryState: row.delivery_state == null ? null : String(row.delivery_state),
  };
}

function toDelivery(row: Row): DeliveryRecord {
  return {
    deliveryId: String(row.delivery_id),
    watchId: String(row.watch_id),
    eventId: String(row.event_id),
    channel: String(row.channel),
    accountId: String(row.account_id),
    target: String(row.target),
    chatType: String(row.chat_type),
    body: String(row.body),
    state: String(row.state),
    attempts: Number(row.attempts),
    lastError: row.last_error == null ? null : String(row.last_error),
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
  };
}
