/**
 * Isolated WebContentsView host for plugin pages. A page gets its own in-memory session
 * partition, a fixed preload bridge, no Node integration and no network: package files and
 * generated media are the only URLs it can load.
 */
import { BrowserWindow, WebContentsView, ipcMain, nativeTheme, session, type IpcMainInvokeEvent, type Session } from 'electron';
import { randomUUID } from 'node:crypto';
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

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
