// Server-side home data for plugin pages: reports and todos are read/written here, in the main
// process, with the stored login. A plugin page never sees the API key or chooses the server URL.
import * as http from 'node:http';
import * as https from 'node:https';
import { activeAuthEntry, loadAuthEntries, type AuthEntry } from '../auth/AuthStore';

export class HomeDataError extends Error {
  readonly code: string;
  readonly status: number | null;

  constructor(code: string, message: string, status: number | null = null) {
    super(message);
    this.name = 'HomeDataError';
    this.code = code;
    this.status = status;
  }
}

export interface HomeStatus {
  signedIn: boolean;
  serverHost: string | null;
  userName: string | null;
}

export interface HomeReportSummary {
  id: string;
  title: string | null;
  description: string | null;
  displayType: string | null;
  datasetId: string | null;
  datasetName: string | null;
  revision: number | null;
  updatedAt: string | null;
  createdAt: string | null;
}

export interface HomeReportDetail extends HomeReportSummary {
  specVersion: number | null;
  specJson: string | null;
}

export interface HomeReportData {
  reportId: string | null;
  status: string | null;
  timezone: string | null;
  fromInclusive: string | null;
  toExclusive: string | null;
  columns: Array<{ name: string; type: string }>;
  rows: Array<Array<string | null>>;
  matchedRecordCount: number | null;
  invalidValueCount: number | null;
  evaluatedAt: string | null;
  sourceUpdatedAt: string | null;
  freshnessStatus: string | null;
  hasMore: boolean;
}

export interface HomeTodo {
  id: string;
  title: string;
  description: string | null;
  completed: boolean;
  priority: string | null;
  dueDate: string | null;
  source: string;
  originDate: string | null;
  originSessionIds: string[];
  dedupeKey: string | null;
  generatedBy: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface HomeTodoDigest {
  date: string;
  status: string;
  model: string | null;
  promptVersion: string | null;
  sessionCount: number | null;
  messageCount: number | null;
  todoCount: number | null;
  skippedCount: number | null;
  error: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface HomeDataOptions {
  loadEntries?: () => AuthEntry[];
  timeoutMs?: number;
}

const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const REPORT_DATA_RANGES = new Set(['TODAY', 'YESTERDAY', 'LAST_N_COMPLETE_DAYS', 'ROLLING_DAYS']);
const MAX_LIST_LIMIT = 50;
const MAX_TODO_TITLE = 200;
const MAX_TODO_DESCRIPTION = 2000;
const PRIORITIES = new Set(['high', 'normal', 'low']);

function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(Math.round(value), max));
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.length ? value : null;
}

function stringList(value: unknown, max: number): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === 'string' && item.length > 0).slice(0, max);
}

function numberOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function validHomeId(value: unknown): value is string {
  return typeof value === 'string' && ID_PATTERN.test(value);
}

export function normalizeReportColumns(value: unknown): Array<{ name: string; type: string }> {
  if (!Array.isArray(value)) return [];
  const columns: Array<{ name: string; type: string }> = [];
  for (const item of value.slice(0, 40)) {
    if (!item || typeof item !== 'object') continue;
    const column = item as Record<string, unknown>;
    const name = typeof column.name === 'string' ? column.name.slice(0, 200) : null;
    if (!name) continue;
    columns.push({ name, type: typeof column.type === 'string' ? column.type : 'STRING' });
  }
  return columns;
}

export function normalizeReportRows(value: unknown, columnCount: number): Array<Array<string | null>> {
  if (!Array.isArray(value)) return [];
  const rows: Array<Array<string | null>> = [];
  for (const raw of value.slice(0, 200)) {
    if (!Array.isArray(raw)) continue;
    const row: Array<string | null> = [];
    for (let index = 0; index < columnCount; index++) {
      const cell = raw[index];
      row.push(cell == null ? null : String(cell).slice(0, 500));
    }
    rows.push(row);
  }
  return rows;
}

function parseJsonArray(value: unknown): unknown[] {
  if (typeof value !== 'string' || !value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function summaryOf(raw: Record<string, unknown>): HomeReportSummary {
  return {
    id: String(raw.id ?? ''),
    title: text(raw.title),
    description: text(raw.description),
    displayType: text(raw.display_type),
    datasetId: text(raw.dataset_id),
    datasetName: text(raw.dataset_name),
    revision: numberOf(raw.revision),
    updatedAt: text(raw.updated_at),
    createdAt: text(raw.created_at),
  };
}

function todoOf(raw: Record<string, unknown>): HomeTodo {
  return {
    id: String(raw.id ?? ''),
    title: String(raw.title ?? ''),
    description: text(raw.description),
    completed: raw.completed === true,
    priority: text(raw.priority),
    dueDate: text(raw.due_date),
    source: raw.source === 'AUTO' ? 'AUTO' : 'MANUAL',
    originDate: text(raw.origin_date),
    originSessionIds: stringList(raw.origin_session_ids, 10),
    dedupeKey: text(raw.dedupe_key),
    generatedBy: text(raw.generated_by),
    createdAt: text(raw.created_at),
    updatedAt: text(raw.updated_at),
  };
}

export class HomeDataClient {
  private readonly entries: () => AuthEntry[];
  private readonly timeoutMs: number;

  constructor(options: HomeDataOptions = {}) {
    this.entries = options.loadEntries ?? (() => loadAuthEntries());
    this.timeoutMs = options.timeoutMs ?? 15_000;
  }

  status(): HomeStatus {
    const entry = this.entry();
    if (!entry) return { signedIn: false, serverHost: null, userName: null };
    return {
      signedIn: true,
      serverHost: hostOf(entry.server_url),
      userName: text(entry.name) ?? text(entry.user_id),
    };
  }

  async reports(offset = 0, limit = 20): Promise<{ reports: HomeReportSummary[]; total: number; fetchedAt: string }> {
    const body = await this.request('GET', `/api/reports?offset=${clampInt(offset, 0, 10_000, 0)}`
      + `&limit=${clampInt(limit, 1, MAX_LIST_LIMIT, 20)}`);
    const reports = Array.isArray(body.reports)
      ? (body.reports as Array<Record<string, unknown>>).filter((row) => typeof row.id === 'string').map(summaryOf)
      : [];
    return { reports, total: numberOf(body.total) ?? reports.length, fetchedAt: new Date().toISOString() };
  }

  async report(id: string): Promise<HomeReportDetail> {
    const body = await this.request('GET', `/api/reports/${encodeURIComponent(this.id(id))}`);
    return {
      ...summaryOf(body),
      specVersion: numberOf(body.spec_version),
      specJson: text(body.spec_json),
    };
  }

  async reportData(id: string, options: { rangeKind?: string; days?: number; pageSize?: number; offset?: number } = {}): Promise<HomeReportData> {
    const query = new URLSearchParams();
    const rangeKind = options.rangeKind && REPORT_DATA_RANGES.has(options.rangeKind) ? options.rangeKind : null;
    if (rangeKind) {
      query.set('range_kind', rangeKind);
      if (options.days != null) query.set('days', String(clampInt(options.days, 1, 90, 7)));
    }
    query.set('page_size', String(clampInt(options.pageSize, 1, 200, 100)));
    query.set('offset', String(clampInt(options.offset, 0, 10_000, 0)));
    const body = await this.request('GET', `/api/reports/${encodeURIComponent(this.id(id))}/data?${query.toString()}`);
    const columns = normalizeReportColumns(parseJsonArray(body.columns_json));
    return {
      reportId: text(body.report_id),
      status: text(body.status),
      timezone: text(body.timezone),
      fromInclusive: text(body.from_inclusive),
      toExclusive: text(body.to_exclusive),
      columns,
      rows: normalizeReportRows(parseJsonArray(body.rows_json), columns.length),
      matchedRecordCount: numberOf(body.matched_record_count),
      invalidValueCount: numberOf(body.invalid_value_count),
      evaluatedAt: text(body.evaluated_at),
      sourceUpdatedAt: text(body.source_updated_at),
      freshnessStatus: text(body.freshness_status),
      hasMore: body.has_more === true,
    };
  }

  async todos(): Promise<{ todos: HomeTodo[]; fetchedAt: string }> {
    const body = await this.request('GET', '/api/for-you/todos');
    const todos = Array.isArray(body.todos)
      ? (body.todos as Array<Record<string, unknown>>).filter((row) => typeof row.id === 'string').map(todoOf)
      : [];
    return { todos, fetchedAt: new Date().toISOString() };
  }

  async createTodo(input: { title: string; description?: string; priority?: string }): Promise<HomeTodo> {
    const title = input.title.trim();
    if (!title || title.length > MAX_TODO_TITLE) {
      throw new HomeDataError('invalid_request', `title must be 1-${MAX_TODO_TITLE} characters`);
    }
    const body = await this.request('POST', '/api/for-you/todos', {
      title,
      description: typeof input.description === 'string' ? input.description.slice(0, MAX_TODO_DESCRIPTION) : null,
      priority: input.priority && PRIORITIES.has(input.priority) ? input.priority : 'normal',
    });
    return todoOf(body);
  }

  async updateTodo(id: string, patch: { title?: string; description?: string; completed?: boolean; priority?: string; dueDate?: string }): Promise<HomeTodo> {
    const payload: Record<string, unknown> = {};
    if (typeof patch.title === 'string') {
      const title = patch.title.trim();
      if (!title || title.length > MAX_TODO_TITLE) throw new HomeDataError('invalid_request', 'title is invalid');
      payload.title = title;
    }
    if (typeof patch.description === 'string') payload.description = patch.description.slice(0, MAX_TODO_DESCRIPTION);
    if (typeof patch.completed === 'boolean') payload.completed = patch.completed;
    if (patch.priority && PRIORITIES.has(patch.priority)) payload.priority = patch.priority;
    if (typeof patch.dueDate === 'string' && patch.dueDate) payload.due_date = patch.dueDate;
    if (!Object.keys(payload).length) throw new HomeDataError('invalid_request', 'nothing to update');
    const body = await this.request('PUT', `/api/for-you/todos/${encodeURIComponent(this.id(id))}`, payload);
    return todoOf(body);
  }

  async deleteTodo(id: string): Promise<{ deleted: boolean }> {
    const body = await this.request('DELETE', `/api/for-you/todos/${encodeURIComponent(this.id(id))}`);
    return { deleted: body.deleted !== false };
  }

  async todoDigest(date?: string): Promise<HomeTodoDigest> {
    const query = date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? `?date=${date}` : '';
    const body = await this.request('GET', `/api/for-you/todos/digest${query}`);
    return {
      date: String(body.date ?? date ?? ''),
      status: String(body.status ?? 'NOT_RUN'),
      model: text(body.model),
      promptVersion: text(body.prompt_version),
      sessionCount: numberOf(body.session_count),
      messageCount: numberOf(body.message_count),
      todoCount: numberOf(body.todo_count),
      skippedCount: numberOf(body.skipped_count),
      error: text(body.error),
      startedAt: text(body.started_at),
      completedAt: text(body.completed_at),
    };
  }

  private entry(): AuthEntry | null {
    return activeAuthEntry(this.entries());
  }

  private id(value: string): string {
    if (!validHomeId(value)) throw new HomeDataError('invalid_request', 'invalid id');
    return value;
  }

  private request(method: string, path: string, body?: Record<string, unknown>): Promise<Record<string, unknown>> {
    const entry = this.entry();
    if (!entry) return Promise.reject(new HomeDataError('not_signed_in', 'sign in to core-ai-server first'));
    return this.send(entry, method, path, body == null ? null : JSON.stringify(body));
  }

  private send(entry: AuthEntry, method: string, path: string, payload: string | null): Promise<Record<string, unknown>> {
    let target: URL;
    try {
      target = new URL(path, normalizeUrl(entry.server_url));
    } catch {
      return Promise.reject(new HomeDataError('server_error', 'the stored server URL is invalid'));
    }
    const transport = target.protocol === 'http:' ? http : https;
    return new Promise((resolve, reject) => {
      const request = transport.request({
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || (target.protocol === 'http:' ? 80 : 443),
        path: `${target.pathname}${target.search}`,
        method,
        headers: {
          Authorization: `Bearer ${entry.api_key}`,
          Accept: 'application/json',
          ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': String(Buffer.byteLength(payload)) } : {}),
        },
        rejectUnauthorized: entry.insecure !== true,
      }, (response) => {
        const chunks: Buffer[] = [];
        response.on('data', (chunk: Buffer) => chunks.push(chunk));
        response.on('end', () => {
          const status = response.statusCode ?? 0;
          const textBody = Buffer.concat(chunks).toString('utf8');
          if (status >= 200 && status < 300) {
            if (!textBody.trim()) {
              resolve({});
              return;
            }
            try {
              const parsed: unknown = JSON.parse(textBody);
              resolve(parsed && typeof parsed === 'object' && !Array.isArray(parsed)
                ? parsed as Record<string, unknown>
                : { value: parsed });
            } catch {
              reject(new HomeDataError('server_error', 'server returned an invalid JSON response', status));
            }
            return;
          }
          reject(errorForStatus(status, textBody));
        });
      });
      request.setTimeout(this.timeoutMs, () => request.destroy(new HomeDataError('offline', 'request timed out')));
      request.on('error', (error) => {
        if (error instanceof HomeDataError) {
          reject(error);
          return;
        }
        reject(new HomeDataError('offline', 'cannot reach core-ai-server'));
      });
      if (payload) request.write(payload);
      request.end();
    });
  }
}

function errorForStatus(status: number, body: string): HomeDataError {
  if (status === 401) return new HomeDataError('not_signed_in', 'the saved login was rejected', status);
  if (status === 403) return new HomeDataError('forbidden', 'this account lacks the required permission', status);
  if (status === 404) return new HomeDataError('not_found', 'the item no longer exists', status);
  if (status === 400 || status === 422) {
    const message = serverMessage(body);
    return new HomeDataError('invalid_request', message ?? 'the server rejected the request', status);
  }
  if (status >= 500) return new HomeDataError('server_error', `core-ai-server returned ${status}`, status);
  return new HomeDataError('server_error', `unexpected response ${status}`, status);
}

function serverMessage(body: string): string | null {
  try {
    const parsed: unknown = JSON.parse(body);
    if (!parsed || typeof parsed !== 'object') return null;
    const record = parsed as Record<string, unknown>;
    const message = record.message ?? (record.error && typeof record.error === 'object'
      ? (record.error as Record<string, unknown>).message
      : null);
    return typeof message === 'string' ? message.slice(0, 200) : null;
  } catch {
    return null;
  }
}

function normalizeUrl(value: string): string {
  return String(value ?? '').trim().replace(/\/+$/, '');
}

function hostOf(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    return new URL(normalizeUrl(value)).host;
  } catch {
    return null;
  }
}
