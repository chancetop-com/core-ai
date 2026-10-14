// Embedded browser for the desktop (design §4.9 P2): the app owns real Chromium tabs as
// WebContentsView children of the main window — the panel IS the browser (clickable, scrollable,
// sign-in capable), not a screencast of an external instance. Agents attach through the
// controlled CDP bridge (./CdpBridge.ts) which fronts webContents.debugger, so browser-use keeps
// working unchanged via BU_CDP_URL. Engine env is a constant for the whole run: the bridge only
// listens while the browser is running, and the harness retries the endpoint for 30s while the
// desktop auto-starts it.
import { app, BrowserWindow, session, WebContentsView } from 'electron';
import type { WebContents } from 'electron';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { CdpBridge, type BrowserHost, type HostTarget } from './CdpBridge';

export interface BrowserTabInfo {
  id: string;
  title: string;
  url: string;
  active: boolean;
}

export interface BrowserStatus {
  running: boolean;
  port: number;
  tabs: BrowserTabInfo[];
  activeId: string | null;
  url: string | null;
  title: string | null;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
}

export interface BrowserBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface BrowserManagerOptions {
  onState: (status: BrowserStatus) => void;
  /** The agent needs a laid-out tab but the panel is not reporting bounds (hidden/never opened). */
  onNeedsPanel: () => void;
}

/** Kept at the port the manual SOP and BU_CDP_URL conventions already use. */
const CDP_PORT = 9222;
const PARTITION = 'persist:coreai-browser';
const START_URL = 'about:blank';

interface Tab {
  id: string;
  view: WebContentsView;
}

export class BrowserManager implements BrowserHost {
  private window: BrowserWindow | null = null;
  private bridge: CdpBridge | null = null;
  private tabs: Tab[] = [];
  private activeId: string | null = null;
  private bounds: BrowserBounds | null = null;
  private panelVisible = false;
  private readonly attachedContents = new Set<number>();
  private readonly zeroSizeWarned = new Set<string>();
  private readonly token: string;
  private readonly userAgent: string;
  private sessionConfigured = false;

  constructor(private readonly options: BrowserManagerOptions) {
    this.token = loadOrCreateToken(path.join(app.getPath('userData')));
    this.userAgent = buildUserAgent();
  }

  attachWindow(window: BrowserWindow | null): void {
    this.window = window;
  }

  /**
   * Injected into every engine spawn. Constant: the token is persisted and the port is fixed, so
   * engines started before the browser still point at the right endpoint; the harness retries.
   * CORE_AI_BROWSER_MANAGED tells the engine's agent that the app hosts the browser (its prompt
   * section forbids running the skill's own browser lifecycle).
   */
  engineEnv(): Record<string, string> {
    return {
      BU_CDP_URL: `http://127.0.0.1:${CDP_PORT}/${this.token}`,
      CORE_AI_BROWSER_MANAGED: '1',
    };
  }

  get running(): boolean {
    return this.bridge !== null;
  }

  async start(): Promise<BrowserStatus> {
    if (this.bridge) return this.status();
    if (!this.window) throw new Error('the desktop window is not ready');
    this.configureSessionOnce();
    const bridge = new CdpBridge({
      host: this,
      port: CDP_PORT,
      token: this.token,
      chromeVersion: process.versions.chrome ?? '0.0.0.0',
      userAgent: this.userAgent,
      v8Version: process.versions.v8 ?? '',
    });
    try {
      await bridge.start();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`cannot listen on 127.0.0.1:${CDP_PORT} for the embedded browser (${message}) - `
        + 'another core-ai-desktop instance or a leftover debug browser may hold that port; close it and retry');
    }
    this.bridge = bridge;
    try {
      this.createTab({ url: START_URL, activate: true });
    } catch (error) {
      this.bridge = null;
      await bridge.stop();
      throw error;
    }
    console.log(`[browser] embedded browser started, CDP endpoint ${bridge.endpointUrl()}`);
    return this.status();
  }

  async stop(): Promise<BrowserStatus> {
    const bridge = this.bridge;
    this.bridge = null;
    for (const tab of [...this.tabs]) {
      this.destroyTab(tab, { allowAutoStop: false });
    }
    this.activeId = null;
    if (bridge) await bridge.stop();
    console.log('[browser] embedded browser stopped');
    this.emitState();
    return this.status();
  }

  status(): BrowserStatus {
    const active = this.activeTab();
    const wc = active ? this.webContentsOf(active) : null;
    return {
      running: this.running,
      port: CDP_PORT,
      tabs: this.tabs.map((tab) => ({
        id: tab.id,
        title: this.titleOf(tab),
        url: this.urlOf(tab),
        active: tab.id === this.activeId,
      })),
      activeId: this.activeId,
      url: active ? this.urlOf(active) : null,
      title: active ? this.titleOf(active) : null,
      loading: wc ? wc.isLoading() : false,
      canGoBack: wc ? wc.navigationHistory.canGoBack() : false,
      canGoForward: wc ? wc.navigationHistory.canGoForward() : false,
    };
  }

  /** Position of the renderer's placeholder in window coordinates; null hides the panel. */
  setBounds(bounds: BrowserBounds | null): void {
    if (bounds
      && Number.isFinite(bounds.x) && Number.isFinite(bounds.y)
      && Number.isFinite(bounds.width) && Number.isFinite(bounds.height)
      && bounds.width > 1 && bounds.height > 1) {
      this.bounds = {
        x: Math.round(bounds.x),
        y: Math.round(bounds.y),
        width: Math.round(bounds.width),
        height: Math.round(bounds.height),
      };
      this.panelVisible = true;
    } else {
      // The panel hid: keep the last rect so hidden views stay sized (a 0x0 viewport breaks
      // agent input and screenshots while the user has the pane closed).
      this.panelVisible = false;
    }
    this.applyViewBounds();
  }

  navigate(input: string): BrowserStatus {
    const tab = this.requireActiveTab();
    const url = resolveNavigationInput(input ?? '');
    if (!url) throw new Error(`blocked URL (the embedded browser only visits http/https): ${input}`);
    const wc = this.webContentsOf(tab);
    if (wc) void wc.loadURL(url).catch(() => undefined);
    return this.status();
  }

  goBack(): BrowserStatus {
    const tab = this.requireActiveTab();
    const wc = this.webContentsOf(tab);
    if (wc?.navigationHistory.canGoBack()) {
      wc.navigationHistory.goBack();
    }
    return this.status();
  }

  goForward(): BrowserStatus {
    const tab = this.requireActiveTab();
    const wc = this.webContentsOf(tab);
    if (wc?.navigationHistory.canGoForward()) {
      wc.navigationHistory.goForward();
    }
    return this.status();
  }

  reload(): BrowserStatus {
    const tab = this.requireActiveTab();
    this.webContentsOf(tab)?.reload();
    return this.status();
  }

  stopLoading(): BrowserStatus {
    const tab = this.requireActiveTab();
    this.webContentsOf(tab)?.stop();
    return this.status();
  }

  newTab(url?: string): BrowserStatus {
    const target = url && isAllowedNavigation(url) ? url : START_URL;
    this.createTab({ url: target, activate: true });
    return this.status();
  }

  closeTab(targetId: string): BrowserStatus {
    const tab = this.tabById(targetId);
    if (tab) this.destroyTab(tab, { allowAutoStop: true });
    return this.status();
  }

  selectTab(targetId: string): BrowserStatus {
    this.setActive(targetId);
    return this.status();
  }

  // ---------- BrowserHost (the CDP bridge talks to the embedded tabs through this) ----------

  listTargets(): HostTarget[] {
    return this.tabs.map((tab) => ({
      targetId: tab.id,
      title: this.titleOf(tab),
      url: this.urlOf(tab),
    }));
  }

  activateTarget(targetId: string): void {
    this.setActive(targetId);
  }

  async createTarget(url: string, background: boolean): Promise<HostTarget> {
    const target = url && isAllowedNavigation(url) ? url : START_URL;
    const tab = this.createTab({ url: target, activate: !background });
    return { targetId: tab.id, title: this.titleOf(tab), url: this.urlOf(tab) };
  }

  async closeTarget(targetId: string): Promise<void> {
    const tab = this.tabById(targetId);
    if (!tab) throw new Error('No target with given id found');
    this.destroyTab(tab, { allowAutoStop: true });
  }

  sendCommand(targetId: string, method: string, params: Record<string, unknown>): Promise<unknown> {
    const tab = this.requireTab(targetId);
    this.warnOnZeroSize(tab);
    this.ensureDebugger(tab);
    // Input and screenshots need a compositor frame, and a collapsed pane stops producing them
    // (input is dropped, capture never returns). Nudge the renderer to reopen the pane first, then
    // give it the beat to lay out before dispatching; reads and navigations work while collapsed.
    const restoring = needsSurface(method) && this.restorePaneForAgent();
    const dispatch = (): Promise<unknown> => {
      let forwarded = params;
      if (method === 'Page.captureScreenshot' && !this.isShown(tab)) {
        // A hidden view has no compositor surface: the default surface capture waits for one that
        // never comes and hangs the caller; capturing from the renderer view returns the same pixels.
        forwarded = { ...params, fromSurface: false };
      }
      return tab.view.webContents.debugger.sendCommand(method, forwarded);
    };
    return restoring ? this.waitForPane().then(dispatch) : dispatch();
  }

  subscribe(targetId: string, listener: (method: string, params: unknown) => void): () => void {
    const tab = this.tabById(targetId);
    if (!tab) return () => undefined;
    this.warnOnZeroSize(tab);
    this.ensureDebugger(tab);
    const wc = this.webContentsOf(tab);
    if (!wc) return () => undefined;
    const dispatch = (_event: unknown, method: string, params: unknown): void => listener(method, params);
    wc.debugger.on('message', dispatch);
    return () => {
      try {
        wc.debugger.removeListener('message', dispatch);
      } catch {
        // the tab is gone
      }
    };
  }

  /** A tab whose view was never laid out has a 0x0 viewport: input lands nowhere. */
  private warnOnZeroSize(tab: Tab): void {
    const bounds = tab.view.getBounds();
    if (bounds.width > 1 && bounds.height > 1) return;
    // Nudge the renderer to (re)open the panel so bounds arrive; only log the first time.
    this.options.onNeedsPanel();
    if (this.zeroSizeWarned.has(tab.id)) return;
    this.zeroSizeWarned.add(tab.id);
    console.error(`[browser] tab ${tab.id} has a zero-size view — the panel has not reported bounds yet; `
      + 'clicks/typing will not land until it does');
  }

  /**
   * A frame-dependent agent call while the user has the pane collapsed (design §4.9): a hidden
   * view stops producing frames, so the pane must come back for input/screenshots to work at all.
   */
  private restorePaneForAgent(): boolean {
    if (this.panelVisible) return false;
    this.options.onNeedsPanel();
    return true;
  }

  /** Bounded wait for the renderer to hand the pane back, plus one beat for the first frame. */
  private async waitForPane(): Promise<void> {
    const deadline = Date.now() + 2_500;
    while (!this.panelVisible && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    if (this.panelVisible) await new Promise((resolve) => setTimeout(resolve, 200));
  }

  // ---------- internals ----------

  private createTab(options: { url: string; activate: boolean }): Tab {
    const window = this.window;
    if (!window) throw new Error('the desktop window is not ready');
    const view = new WebContentsView({
      webPreferences: {
        partition: PARTITION,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        // Background tabs must keep rendering: the agent screenshots tabs the user is not on.
        backgroundThrottling: false,
      },
    });
    const wc = view.webContents;
    const tab: Tab = { id: String(wc.id), view };
    this.tabs.push(tab);
    window.contentView.addChildView(view);
    view.setVisible(false);
    this.wireTab(tab);
    void wc.loadURL(options.url).catch(() => undefined);
    if (options.activate) this.setActive(tab.id);
    // Background-created tabs must be laid out too (agent tabs are often not the visible one).
    this.applyViewBounds();
    console.log(`[browser] tab created id=${tab.id} url=${options.url}`);
    this.emitState();
    return tab;
  }

  private wireTab(tab: Tab): void {
    const wc = tab.view.webContents;
    const update = (): void => this.emitState();
    wc.on('page-title-updated', update);
    wc.on('did-navigate', update);
    wc.on('did-navigate-in-page', update);
    wc.on('did-start-loading', update);
    wc.on('did-stop-loading', () => {
      // Re-assert the layout after a navigation settles (bounds set before a view was laid out
      // are otherwise dropped by Chromium).
      this.applyViewBounds();
      update();
    });
    wc.on('did-fail-load', (_event, code, description, url, isMainFrame) => {
      if (isMainFrame) console.error(`[browser] tab ${tab.id} load failed (${code} ${description}) ${url}`);
    });
    wc.on('will-navigate', (event, url) => {
      if (!isAllowedNavigation(url)) {
        event.preventDefault();
        console.error(`[browser] blocked navigation to ${url}`);
      }
    });
    wc.on('render-process-gone', (_event, details) => {
      console.error(`[browser] tab ${tab.id} renderer gone: ${details.reason}`);
      this.destroyTab(tab, { allowAutoStop: true });
    });
    wc.once('destroyed', () => {
      // A page can close its own tab (window.close()) and a crashed renderer drops the webContents
      // with it: `view.webContents` then reads undefined. Without this hook the tab would stay in
      // `tabs` as a zombie and every later title/stop/reload/status call would throw "Cannot read
      // properties of undefined" — bricking the pane and the agent's CDP bridge until a restart.
      if (!this.tabs.includes(tab)) return;
      console.log(`[browser] tab ${tab.id} closed from the page or renderer side`);
      this.destroyTab(tab, { allowAutoStop: true });
    });
    wc.setWindowOpenHandler((details) => {
      if (isAllowedNavigation(details.url)) {
        const activate = details.disposition === 'foreground-tab' || details.disposition === 'new-window';
        this.createTab({ url: details.url, activate });
      }
      return { action: 'deny' };
    });
  }

  private destroyTab(tab: Tab, options: { allowAutoStop: boolean }): void {
    if (!this.tabs.includes(tab)) return;
    this.tabs = this.tabs.filter((entry) => entry !== tab);
    // The webContents may already be gone (page-closed tab, crashed renderer) — touch it only when
    // it still exists so the removeChildView/activeId/emit cleanup below always runs.
    const wc = this.webContentsOf(tab);
    if (wc) this.attachedContents.delete(wc.id);
    try {
      this.window?.contentView.removeChildView(tab.view);
    } catch {
      // the window may already be gone
    }
    if (wc) {
      try {
        wc.close();
      } catch {
        // already destroyed
      }
    }
    if (this.activeId === tab.id) {
      this.activeId = this.tabs[0]?.id ?? null;
    }
    console.log(`[browser] tab closed id=${tab.id}`);
    this.applyViewBounds();
    this.emitState();
    if (options.allowAutoStop && this.tabs.length === 0 && this.bridge) {
      // Reply to Target.closeTarget first, then stop; deferred so the response can flush.
      setImmediate(() => {
        if (this.tabs.length === 0 && this.bridge) void this.stop();
      });
    }
  }

  private setActive(targetId: string): void {
    const tab = this.tabById(targetId);
    if (!tab || this.activeId === targetId) {
      if (tab && (!this.panelVisible || this.bounds === null)) this.options.onNeedsPanel();
      return;
    }
    this.activeId = targetId;
    this.applyViewBounds();
    if (!this.panelVisible || this.bounds === null) this.options.onNeedsPanel();
    this.emitState();
  }

  private applyViewBounds(): void {
    // Only a view that is visible at an on-window position gets laid out by Chromium; hidden
    // views that were shown once keep their viewport size. Tabs never shown rely on the renderer
    // opening the panel (onNeedsPanel) before the agent drives them.
    for (const tab of this.tabs) {
      const shown = this.running && tab.id === this.activeId && this.panelVisible && this.bounds !== null;
      tab.view.setVisible(shown);
      if (this.bounds) tab.view.setBounds(this.bounds);
    }
  }

  private isShown(tab: Tab): boolean {
    return this.running && tab.id === this.activeId && this.panelVisible && this.bounds !== null;
  }

  private ensureDebugger(tab: Tab): void {
    const wc = this.webContentsOf(tab);
    if (!wc || wc.isDestroyed()) throw new Error('browser tab is closed');
    if (this.attachedContents.has(wc.id)) return;
    try {
      wc.debugger.attach('1.3');
    } catch (error) {
      if (!String(error instanceof Error ? error.message : error).includes('already attached')) throw error;
    }
    this.attachedContents.add(wc.id);
    wc.debugger.on('detach', () => this.attachedContents.delete(wc.id));
  }

  private configureSessionOnce(): void {
    if (this.sessionConfigured) return;
    this.sessionConfigured = true;
    const browserSession = session.fromPartition(PARTITION);
    browserSession.setUserAgent(this.userAgent);
    // The pane hosts arbitrary web pages: deny every capability request except a sanitized
    // clipboard write (copy buttons); the agent does not need camera/mic/notifications/etc.
    browserSession.setPermissionRequestHandler((_wc, permission, callback) => {
      callback(permission === 'clipboard-sanitized-write');
    });
    browserSession.on('will-download', (_event, item) => {
      try {
        item.setSavePath(nextDownloadPath(item.getFilename()));
        console.log(`[browser] download started: ${item.getFilename()}`);
      } catch (error) {
        console.error('[browser] download path failed', error);
      }
    });
  }

  private activeTab(): Tab | null {
    return this.tabById(this.activeId ?? '');
  }

  private requireActiveTab(): Tab {
    const tab = this.activeTab();
    if (!tab) throw new Error('no browser tab is open');
    return tab;
  }

  private tabById(targetId: string): Tab | null {
    return this.tabs.find((tab) => tab.id === targetId) ?? null;
  }

  private requireTab(targetId: string): Tab {
    const tab = this.tabById(targetId);
    if (!tab) throw new Error('No target with given id found');
    return tab;
  }

  private titleOf(tab: Tab): string {
    const wc = this.webContentsOf(tab);
    if (!wc) return '';
    const title = wc.getTitle();
    return title && title !== 'about:blank' ? title : '';
  }

  private urlOf(tab: Tab): string {
    const wc = this.webContentsOf(tab);
    return (wc && wc.getURL()) || 'about:blank';
  }

  /**
   * `view.webContents` reads `undefined` once the webContents is destroyed (a page can close its
   * own tab via window.close()) — Electron's typings don't model that, but every wc-facing path
   * must cope. The 'destroyed' listener in wireTab removes such tabs; this is the belt to it.
   */
  private webContentsOf(tab: Tab): WebContents | null {
    const wc: WebContents | undefined = tab.view.webContents;
    return wc ?? null;
  }

  private emitState(): void {
    this.options.onState(this.status());
  }
}

/** http(s) everywhere, about:blank and blob: for page-internal previews; file:// etc. are refused. */
export function isAllowedNavigation(url: string): boolean {
  try {
    const parsed = new URL(url);
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return true;
    if (parsed.protocol === 'blob:') return true;
    return parsed.protocol === 'about:' && parsed.href.startsWith('about:blank');
  } catch {
    return false;
  }
}

/** CDP calls that need a compositor frame: without one, input is dropped and screenshots never return. */
function needsSurface(method: string): boolean {
  return method === 'Page.captureScreenshot' || method === 'Page.captureSnapshot' || method.startsWith('Input.');
}

/** Address-bar input: URL as typed, http(s) upgrade heuristics, otherwise a web search. */
export function resolveNavigationInput(input: string): string | null {
  const raw = input.trim();
  if (!raw) return null;
  if (/^[a-z][a-z0-9+.-]*:/i.test(raw)) return isAllowedNavigation(raw) ? raw : null;
  if (/^localhost([:/]|$)/i.test(raw) || /^\d{1,3}(\.\d{1,3}){3}(:\d+)?([/?#]|$)/.test(raw)) {
    return `http://${raw}`;
  }
  if (!raw.includes(' ') && /^[\w-]+(\.[\w-]+)+(:\d+)?([/?#].*)?$/.test(raw)) {
    return `https://${raw}`;
  }
  return `https://www.bing.com/search?q=${encodeURIComponent(raw)}`;
}

function nextDownloadPath(filename: string): string {
  const safe = filename.replace(/[\\/:*?"<>|]+/g, '_') || 'download';
  const dir = app.getPath('downloads');
  const ext = path.extname(safe);
  const base = path.basename(safe, ext);
  let candidate = path.join(dir, safe);
  for (let index = 1; existsSync(candidate) && index < 1_000; index += 1) {
    candidate = path.join(dir, `${base} (${index})${ext}`);
  }
  return candidate;
}

function buildUserAgent(): string {
  const chrome = process.versions.chrome ?? '0.0.0.0';
  const platform = process.platform === 'win32'
    ? 'Windows NT 10.0; Win64; x64'
    : process.platform === 'darwin' ? 'Macintosh; Intel Mac OS X 10_15_7' : 'X11; Linux x86_64';
  // Plain Chrome UA: sign-in pages reject the default "... Electron/x.y" token.
  return `Mozilla/5.0 (${platform}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chrome} Safari/537.36`;
}

/** Stable per-install secret in the app data dir; the bridge only answers on token-prefixed paths. */
function loadOrCreateToken(dir: string): string {
  const file = path.join(dir, 'browser-token');
  try {
    const existing = readFileSync(file, 'utf8').trim();
    if (/^[0-9a-f]{32}$/.test(existing)) return existing;
  } catch {
    // create below
  }
  const token = randomBytes(16).toString('hex');
  try {
    mkdirSync(dir, { recursive: true });
    writeFileSync(file, token, { mode: 0o600 });
  } catch (error) {
    console.error('[browser] failed to persist the bridge token', error);
  }
  return token;
}
