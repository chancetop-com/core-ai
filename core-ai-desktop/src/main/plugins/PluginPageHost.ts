/**
 * Isolated WebContentsView host for plugin pages. A page gets its own in-memory session
 * partition, a fixed preload bridge, no Node integration and no network: package files and
 * generated media are the only URLs it can load.
 */
import { BrowserWindow, WebContentsView, ipcMain, nativeTheme, session, type IpcMainInvokeEvent, type Session } from 'electron';
import { randomUUID } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import type { ResolvedPluginPage } from './pluginStore';
import type { PluginHost } from './PluginHost';
import type { EngineSupervisor } from '../engine/EngineSupervisor';
import { assetUrl, pageUrl } from './pluginPagePaths.js';
import {
  installPluginAssetProtocol,
  installPluginPageProtocol,
  removePluginPageProtocols,
} from './PluginPageProtocol.js';

export interface PluginPageBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PluginPageHostOptions {
  pluginHost: () => PluginHost | null;
  supervisor: () => EngineSupervisor | null;
  preloadPath: string;
  onLog?: (line: string) => void;
  notify?: (text: string) => Promise<{ ok: boolean; error?: string }>;
}

export interface OpenPluginPageOptions {
  window: BrowserWindow;
  workspace: string;
  pluginId: string;
  pageId: string;
  bounds: PluginPageBounds;
}

interface PageInstance {
  token: string;
  window: BrowserWindow;
  pluginId: string;
  pageId: string;
  workspace: string;
  packageDir: string;
  page: ResolvedPluginPage;
  ses: Session;
  view: WebContentsView;
  closed: boolean;
  events: Set<string>;
  notifyTimes: number[];
}

export class PluginPageError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'PluginPageError';
    this.code = code;
  }
}

const ALLOWED_SCHEMES = new Set(['coreai-plugin-page:', 'coreai-plugin-asset:']);
const MAX_IMPORT_BYTES = 10 * 1024 * 1024;
const IMAGE_IMPORT_EXTENSIONS: Record<string, string> = {
  '.png': 'png',
  '.jpg': 'jpg',
  '.jpeg': 'jpg',
  '.webp': 'webp',
  '.gif': 'gif',
};
const PLUGIN_EVENTS: Record<string, string> = {
  status_change: 'session.status',
  turn_complete: 'turn.completed',
  tool_start: 'tool.started',
  tool_result: 'tool.finished',
  error: 'turn.failed',
  plan_update: 'plan.updated',
  task_status: 'task.updated',
  batch_tool_start: 'tool.batch_started',
  agent_wait: 'agent.wait',
  custom: 'custom',
};
const PLUGIN_EVENT_TYPES = new Set(Object.values(PLUGIN_EVENTS));

export class PluginPageHost {
  private readonly instances = new Map<string, PageInstance>();
  private readonly options: PluginPageHostOptions;

  constructor(options: PluginPageHostOptions) {
    this.options = options;
    ipcMain.handle('pluginPage:call', (event, token: string, method: string, params: unknown) =>
      this.call(event, token, method, params));
  }

  async open(options: OpenPluginPageOptions): Promise<{ token: string; title: string }> {
    const host = this.options.pluginHost();
    if (!host) throw new PluginPageError('unavailable', 'plugin host is not ready');
    if (!options.workspace || typeof options.workspace !== 'string') {
      throw new PluginPageError('invalid_request', 'workspace is required');
    }
    const page = await host.resolvePage(options.pluginId, options.pageId);
    if (!page) throw new PluginPageError('not_available', 'plugin page is not active or is not permitted');
    if (!page.granted.includes('ui.pages')) throw new PluginPageError('not_granted', 'ui.pages is not granted');

    const token = randomUUID();
    const partition = `plugin-page-${token}`;
    const ses = session.fromPartition(partition);
    configureSession(ses);
    installPluginPageProtocol(ses, page.dir);
    if (page.granted.includes('media.assets.read')) installPluginAssetProtocol(ses, options.workspace);

    const view = new WebContentsView({
      webPreferences: {
        preload: this.options.preloadPath,
        partition,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        webSecurity: true,
        additionalArguments: [`--coreai-plugin-page-token=${token}`],
      },
    });
    const instance: PageInstance = {
      token,
      window: options.window,
      pluginId: options.pluginId,
      pageId: options.pageId,
      workspace: options.workspace,
      packageDir: page.dir,
      page,
      ses,
      view,
      closed: false,
      events: new Set(),
      notifyTimes: [],
    };
    this.instances.set(token, instance);
    this.wireWebContents(instance);
    options.window.contentView.addChildView(view);
    this.setBounds(instance, options.bounds);
    this.options.onLog?.(`plugin page opened: ${options.pluginId}/${options.pageId}`);
    try {
      await view.webContents.loadURL(pageUrl(page.view));
    } catch (error) {
      this.close(token);
      throw new PluginPageError('load_failed', `plugin page failed to load: ${messageOf(error)}`);
    }
    return { token, title: page.title };
  }

  setBoundsByToken(token: string, bounds: PluginPageBounds): boolean {
    const instance = this.instances.get(token);
    if (!instance) return false;
    this.setBounds(instance, bounds);
    return true;
  }

  close(token: string): boolean {
    const instance = this.instances.get(token);
    if (!instance) return false;
    instance.closed = true;
    this.instances.delete(token);
    try {
      instance.window.contentView.removeChildView(instance.view);
    } catch {
      // the window may already be gone
    }
    try {
      instance.view.webContents.close();
    } catch {
      // already destroyed
    }
    removePluginPageProtocols(instance.ses);
    this.options.onLog?.(`plugin page closed: ${instance.pluginId}/${instance.pageId}`);
    return true;
  }

  closeAll(): void {
    for (const token of [...this.instances.keys()]) this.close(token);
  }

  closeForPlugin(pluginId: string): void {
    for (const instance of [...this.instances.values()]) {
      if (instance.pluginId === pluginId) this.close(instance.token);
    }
  }

  private setBounds(instance: PageInstance, bounds: PluginPageBounds): void {
    const content = instance.window.getContentBounds();
    const width = clampInt(bounds.width, 1, content.width);
    const height = clampInt(bounds.height, 1, content.height);
    const x = clampInt(bounds.x, 0, Math.max(0, content.width - width));
    const y = clampInt(bounds.y, 0, Math.max(0, content.height - height));
    instance.view.setBounds({ x, y, width, height });
  }

  private wireWebContents(instance: PageInstance): void {
    const contents = instance.view.webContents;
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
    contents.on('will-navigate', (event) => event.preventDefault());
    contents.on('will-redirect', (event) => event.preventDefault());
    contents.on('did-fail-load', (_event, code, description, url) => {
      this.options.onLog?.(`plugin page failed to load (${code} ${description}): ${url}`);
    });
    contents.on('will-attach-webview', (event) => event.preventDefault());
    contents.on('render-process-gone', (_event, details) => {
      this.options.onLog?.(`plugin page renderer gone (${details.reason}); closing`);
      this.close(instance.token);
    });
  }

  private async call(event: IpcMainInvokeEvent, token: string, method: string, params: unknown): Promise<unknown> {
    const instance = this.instances.get(token);
    if (!instance || instance.closed) throw new PluginPageError('not_open', 'plugin page is not open');
    if (event.sender !== instance.view.webContents) throw new PluginPageError('forbidden', 'wrong page sender');
    await this.refreshPage(instance);
    const args = isRecord(params) ? params : {};
    switch (method) {
      case 'context':
        return {
          pluginId: instance.page.pluginId,
          pluginName: instance.page.pluginName,
          pageId: instance.page.pageId,
          scope: instance.page.scope,
          workspace: instance.workspace,
          capabilities: instance.page.granted,
          theme: nativeTheme.shouldUseDarkColors ? 'dark' : 'light',
        };
      case 'assets.list':
        this.requireCapability(instance, 'media.assets.read');
        return this.listAssets(instance);
      case 'assets.import':
        this.requireCapability(instance, 'media.assets.write');
        return this.importAsset(instance, args);
      case 'media.models':
        this.requireCapability(instance, 'media.models.read');
        return this.engine(instance, 'media/models', {});
      case 'media.generate':
        this.requireCapability(instance, 'media.generate');
        return this.trustedEngine(instance, 'media/generate', { ...args, _owner: pageOwner(instance) });
      case 'media.jobs.get':
        this.requireCapability(instance, 'media.generate');
        return this.trustedEngine(instance, 'media/jobs/get', { ...args, _owner: pageOwner(instance) });
      case 'media.jobs.list':
        this.requireCapability(instance, 'media.generate');
        return this.trustedEngine(instance, 'media/jobs/list', { _owner: pageOwner(instance) });
      case 'sessions.list':
        this.requireCapability(instance, 'sessions.read');
        return this.engine(instance, 'session/list', { limit: 50, cursor: typeof args.cursor === 'number' ? args.cursor : 0 });
      case 'sessions.rename':
        this.requireCapability(instance, 'sessions.write');
        if (typeof args.sessionId !== 'string' || typeof args.title !== 'string' || !args.title.trim()) {
          throw new PluginPageError('invalid_request', 'sessionId and title are required');
        }
        return this.trustedEngine(instance, 'session/rename', { sessionId: args.sessionId, title: args.title });
      case 'schedules.list':
        this.requireCapability(instance, 'schedules.read');
        return this.engine(instance, 'schedules/list', {
          scope: 'workspace',
          enabledOnly: args.enabledOnly === true,
        });
      case 'schedules.get':
        this.requireCapability(instance, 'schedules.read');
        return this.engine(instance, 'schedules/get', {
          id: await this.workspaceScheduleId(instance, args.id),
          runsLimit: typeof args.runsLimit === 'number' ? args.runsLimit : 20,
        });
      case 'schedules.update':
        this.requireCapability(instance, 'schedules.write');
        return this.trustedEngine(instance, 'schedules/update', await this.scheduleUpdate(instance, args));
      case 'schedules.delete':
        this.requireCapability(instance, 'schedules.write');
        return this.trustedEngine(instance, 'schedules/delete', { id: await this.workspaceScheduleId(instance, args.id) });
      case 'schedules.trigger':
        this.requireCapability(instance, 'schedules.write');
        return this.trustedEngine(instance, 'schedules/trigger', { id: await this.workspaceScheduleId(instance, args.id) });
      case 'memory.list':
        this.requireCapability(instance, 'memory.read');
        return this.engine(instance, 'memory/list', { limit: 200, cursor: typeof args.cursor === 'number' ? args.cursor : 0 });
      case 'memory.read':
        this.requireCapability(instance, 'memory.read');
        if (typeof args.path !== 'string') throw new PluginPageError('invalid_request', 'path is required');
        return this.engine(instance, 'memory/read', { path: args.path });
      case 'memory.write':
        this.requireCapability(instance, 'memory.write');
        if (typeof args.path !== 'string' || typeof args.content !== 'string') {
          throw new PluginPageError('invalid_request', 'path and content are required');
        }
        return this.engine(instance, 'memory/write', {
          path: args.path,
          content: args.content,
          mode: typeof args.mode === 'string' ? args.mode : 'append',
        });
      case 'workspace.summary':
        this.requireCapability(instance, 'workspace.read');
        return this.workspaceSummary(instance);
      case 'usage.summary':
        this.requireCapability(instance, 'usage.read');
        return this.engine(instance, 'usage/summary', { range: typeof args.range === 'string' ? args.range : 'all' });
      case 'storage.get':
        this.requireCapability(instance, 'plugin.storage');
        return { value: await this.storage().storageGet(instance.pluginId, storageKey(args.key)) };
      case 'storage.keys':
        this.requireCapability(instance, 'plugin.storage');
        return { keys: await this.storage().storageKeys(instance.pluginId) };
      case 'storage.set':
        this.requireCapability(instance, 'plugin.storage');
        await this.storage().storageSet(instance.pluginId, storageKey(args.key), storageValue(args.value));
        return { ok: true };
      case 'storage.delete':
        this.requireCapability(instance, 'plugin.storage');
        return { deleted: await this.storage().storageDelete(instance.pluginId, storageKey(args.key)) };
      case 'events.subscribe':
        this.requireCapability(instance, 'events.read');
        return this.subscribeEvents(instance, args.types);
      case 'notify.send':
        this.requireCapability(instance, 'notification.send');
        return this.sendNotification(instance, args.text);
      default:
        throw new PluginPageError('unknown_method', `unknown page method: ${method}`);
    }
  }

  /** Re-reads the grant on every call: revoke/disable takes effect before the next bridge call. */
  private async refreshPage(instance: PageInstance): Promise<void> {
    const host = this.options.pluginHost();
    const page = host ? await host.resolvePage(instance.pluginId, instance.pageId) : null;
    if (!page) {
      this.close(instance.token);
      throw new PluginPageError('not_available', 'plugin page is no longer active or permitted');
    }
    instance.page = page;
  }

  private requireCapability(instance: PageInstance, capability: string): void {
    if (!instance.page.granted.includes(capability)) {
      throw new PluginPageError('not_granted', `${capability} is not granted to this plugin`);
    }
  }

  private async listAssets(instance: PageInstance): Promise<{ files: unknown[] }> {
    const result = await this.engine(instance, 'artifacts/list', { limit: 200 }) as {
      files?: Array<Record<string, unknown>>;
    };
    const files = Array.isArray(result?.files) ? result.files : [];
    return {
      files: files
        .filter((file) => (file.kind === 'image' || file.kind === 'video')
          && typeof file.path === 'string'
          && file.path.startsWith('.core-ai/media/'))
        .map((file) => ({
          name: file.name,
          kind: file.kind,
          size: file.size,
          createdAt: file.createdAt,
          url: assetUrl(String(file.path)),
        })),
    };
  }

  private async importAsset(instance: PageInstance, args: Record<string, unknown>): Promise<{ file: Record<string, unknown> }> {
    const name = typeof args.name === 'string' ? args.name.trim() : '';
    const data = typeof args.data === 'string' ? args.data : '';
    if (!name || name.length > 200 || !data) throw new PluginPageError('invalid_request', 'name and data are required');
    const extension = IMAGE_IMPORT_EXTENSIONS[path.extname(name).toLowerCase()];
    if (!extension) throw new PluginPageError('unsupported_type', 'only png, jpg, webp and gif imports are supported');
    if (!/^[A-Za-z0-9+/=\s]+$/.test(data)) throw new PluginPageError('invalid_request', 'data must be base64');
    const encoded = data.replace(/\s+/g, '');
    const padding = encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0;
    const byteLength = Math.floor(encoded.length * 3 / 4) - padding;
    if (byteLength <= 0 || byteLength > MAX_IMPORT_BYTES) throw new PluginPageError('too_large', 'reference image is too large');
    const bytes = Buffer.from(encoded, 'base64');
    const relative = `.core-ai/media/imports/import-${randomUUID()}.${extension}`;
    const root = path.resolve(instance.workspace, '.core-ai', 'media', 'imports');
    const target = path.resolve(instance.workspace, ...relative.split('/'));
    if (!target.startsWith(`${root}${path.sep}`)) throw new PluginPageError('invalid_request', 'invalid import path');
    await fs.mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
    await fs.writeFile(target, bytes, { mode: 0o600 });
    return {
      file: {
        name,
        kind: 'image',
        size: bytes.length,
        path: relative,
        url: assetUrl(relative),
      },
    };
  }

  private async workspaceScheduleId(instance: PageInstance, value: unknown): Promise<string> {
    if (typeof value !== 'string' || !value) throw new PluginPageError('invalid_request', 'schedule id is required');
    const result = await this.engine(instance, 'schedules/list', { scope: 'workspace' }) as {
      tasks?: Array<Record<string, unknown>>;
    };
    const tasks = Array.isArray(result?.tasks) ? result.tasks : [];
    if (!tasks.some((task) => task.id === value)) {
      throw new PluginPageError('not_found', `scheduled task is not owned by this workspace: ${value}`);
    }
    return value;
  }

  private async scheduleUpdate(instance: PageInstance, args: Record<string, unknown>): Promise<Record<string, unknown>> {
    const params: Record<string, unknown> = { id: await this.workspaceScheduleId(instance, args.id) };
    for (const field of ['name', 'cronExpression', 'timezone', 'input']) {
      if (typeof args[field] === 'string') params[field] = args[field];
    }
    if (typeof args.enabled === 'boolean') params.enabled = args.enabled;
    return params;
  }

  private async workspaceSummary(instance: PageInstance): Promise<Record<string, unknown>> {
    const sessions = instance.page.granted.includes('sessions.read')
      ? await this.engine(instance, 'session/list', { limit: 500, cursor: 0 }) as { sessions?: unknown[] }
      : null;
    const artifacts = instance.page.granted.includes('media.assets.read')
      ? await this.engine(instance, 'artifacts/list', { limit: 500, cursor: 0 }) as { files?: unknown[] }
      : null;
    const memory = instance.page.granted.includes('memory.read')
      ? await this.engine(instance, 'memory/list', { limit: 500, cursor: 0 }) as { files?: unknown[] }
      : null;
    return {
      path: instance.workspace,
      sessionCount: sessions && Array.isArray(sessions.sessions) ? sessions.sessions.length : null,
      artifactCount: artifacts && Array.isArray(artifacts.files) ? artifacts.files.length : null,
      memoryFileCount: memory && Array.isArray(memory.files) ? memory.files.length : null,
    };
  }

  private storage(): PluginHost {
    const host = this.options.pluginHost();
    if (!host) throw new PluginPageError('unavailable', 'plugin host is not ready');
    return host;
  }

  private async sendNotification(instance: PageInstance, value: unknown): Promise<{ ok: boolean; error?: string }> {
    if (typeof value !== 'string' || !value.trim() || value.length > 2000) {
      throw new PluginPageError('invalid_request', 'notification text must be 1-2000 characters');
    }
    const now = Date.now();
    instance.notifyTimes = instance.notifyTimes.filter((time) => now - time < 60_000);
    if (instance.notifyTimes.length >= 5) {
      throw new PluginPageError('rate_limited', 'notification rate limit reached (5/minute)');
    }
    const notify = this.options.notify;
    if (!notify) throw new PluginPageError('unavailable', 'notifications are not available');
    instance.notifyTimes.push(now);
    try {
      return await notify(value);
    } catch (error) {
      return { ok: false, error: messageOf(error) };
    }
  }

  private subscribeEvents(instance: PageInstance, value: unknown): { types: string[] } {    if (value !== undefined && !Array.isArray(value)) throw new PluginPageError('invalid_request', 'types must be an array');
    const types = Array.isArray(value)
      ? [...new Set(value.filter((item): item is string => typeof item === 'string'))]
      : [];
    if (types.length > 10) throw new PluginPageError('invalid_request', 'at most 10 event types');
    for (const type of types) {
      if (!PLUGIN_EVENT_TYPES.has(type)) throw new PluginPageError('invalid_request', `unsupported event type: ${type}`);
    }
    instance.events = new Set(types);
    return { types: [...instance.events] };
  }

  /** Main forwards engine notifications here; only allowlisted, redacted fields reach a subscribed page. */
  onEngineEvent(workspace: string, method: string, params: Record<string, unknown>): void {
    if (method !== 'session/event') return;
    const sessionId = typeof params.sessionId === 'string' ? params.sessionId : '';
    const raw = params.event;
    if (!sessionId || !raw || typeof raw !== 'object') return;
    const event = raw as Record<string, unknown>;
    const type = typeof event.type === 'string' ? event.type : '';
    const publicType = PLUGIN_EVENTS[type];
    if (!publicType) return;
    const envelope = {
      type: publicType,
      version: 1,
      occurredAt: new Date().toISOString(),
      scope: { workspace, sessionId },
      data: eventData(type, event),
    };
    for (const instance of this.instances.values()) {
      if (instance.closed || instance.workspace !== workspace || !instance.events.has(publicType)) continue;
      try {
        instance.view.webContents.send('pluginPage:event', envelope);
      } catch {
        // page is closing; the close path will remove it
      }
    }
  }

  private async trustedEngine(instance: PageInstance, method: string, params: Record<string, unknown>): Promise<unknown> {
    const supervisor = this.requireSupervisor();
    const trust = await supervisor.ensureTrustState(instance.workspace);
    if (trust?.state !== 'trusted') {
      throw new PluginPageError('TRUST_REQUIRED', 'this workspace is restricted; trust it before generating media');
    }
    return supervisor.call(instance.workspace, method, params);
  }

  private async engine(instance: PageInstance, method: string, params: Record<string, unknown>): Promise<unknown> {
    return this.requireSupervisor().call(instance.workspace, method, params);
  }

  private requireSupervisor(): EngineSupervisor {
    const supervisor = this.options.supervisor();
    if (!supervisor) throw new PluginPageError('unavailable', 'engine supervisor is not ready');
    return supervisor;
  }
}

function configureSession(ses: Session): void {
  ses.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  ses.setPermissionCheckHandler(() => false);
  ses.webRequest.onBeforeRequest((details, callback) => {
    let scheme: string;
    try {
      scheme = new URL(details.url).protocol;
    } catch {
      callback({ cancel: true });
      return;
    }
    callback({ cancel: !ALLOWED_SCHEMES.has(scheme) });
  });
}

function clampInt(value: unknown, min: number, max: number): number {
  const number = typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : min;
  return Math.max(min, Math.min(max, number));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function pageOwner(instance: PageInstance): string {
  return `${instance.pluginId}/${instance.pageId}`;
}

function storageKey(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value)) {
    throw new PluginPageError('invalid_request', 'invalid storage key');
  }
  return value;
}

function storageValue(value: unknown): string {
  if (typeof value !== 'string' || Buffer.byteLength(value, 'utf8') > 64 * 1024) {
    throw new PluginPageError('invalid_request', 'storage value must be a string up to 64 KiB');
  }
  return value;
}

function textField(event: Record<string, unknown>, ...names: string[]): string | null {
  for (const name of names) {
    const value = event[name];
    if (typeof value === 'string' && value) return value;
  }
  return null;
}

function numberField(event: Record<string, unknown>, ...names: string[]): number | null {
  for (const name of names) {
    const value = event[name];
    if (typeof value === 'number' && Number.isFinite(value)) return value;
  }
  return null;
}

function eventData(type: string, event: Record<string, unknown>): Record<string, unknown> {
  switch (type) {
    case 'status_change':
      return { status: textField(event, 'status') };
    case 'turn_complete':
      return {
        turnId: textField(event, 'turn_id', 'turnId'),
        cancelled: event.cancelled === true,
        durationMs: numberField(event, 'duration_ms', 'durationMs'),
        inputTokens: numberField(event, 'input_tokens', 'inputTokens'),
        outputTokens: numberField(event, 'output_tokens', 'outputTokens'),
        cachedTokens: numberField(event, 'cached_tokens', 'cachedTokens'),
        costUsd: numberField(event, 'cost_usd', 'costUsd'),
        maxTurnsReached: event.max_turns_reached === true,
        budgetExhausted: event.budget_exhausted === true,
      };
    case 'tool_start':
      return {
        tool: textField(event, 'toolName', 'tool', 'name'),
        callId: textField(event, 'callId', 'call_id'),
        taskId: textField(event, 'taskId', 'task_id'),
        runInBackground: event.run_in_background === true,
      };
    case 'tool_result':
      return {
        tool: textField(event, 'toolName', 'tool', 'name'),
        callId: textField(event, 'callId', 'call_id'),
        status: textField(event, 'status'),
        durationMs: numberField(event, 'durationMs', 'duration_ms'),
        taskId: textField(event, 'taskId', 'task_id'),
      };
    case 'error':
      return { message: (textField(event, 'message', 'error') ?? 'turn failed').slice(0, 500) };
    case 'plan_update':
      return { status: textField(event, 'status') };
    case 'task_status':
      return { taskId: textField(event, 'taskId', 'task_id'), status: textField(event, 'status') };
    case 'batch_tool_start':
      return { count: numberField(event, 'count') ?? (Array.isArray(event.tools) ? event.tools.length : 0) };
    case 'agent_wait':
      return { reason: textField(event, 'reason') };
    case 'custom':
      return { name: textField(event, 'name') };
    default:
      return {};
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
