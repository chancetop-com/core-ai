// Electron main: window + IPC + engine supervision. Agent work (loops, file IO, compression)
// stays in the engine child process; this process only routes.
import { app, BrowserWindow, dialog, ipcMain, nativeTheme, screen, shell } from 'electron';
import http from 'node:http';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { BrowserManager } from './browser/BrowserManager';
import { EngineSupervisor } from './engine/EngineSupervisor';
import { RpcCallError } from './engine/RpcClient';

// The app is often launched from a terminal that can close while the window stays open (or from a
// killed shell); a log write to that dead pipe raises EPIPE and used to take down the main process
// with Electron's "A JavaScript error occurred in the main process" dialog. Logging must never be
// fatal: swallow pipe errors on both std streams and guard the console itself (covers the async
// 'error' event and a synchronous throw alike).
for (const stream of [process.stdout, process.stderr]) {
  stream.on('error', () => undefined);
}
for (const method of ['log', 'info', 'warn', 'error'] as const) {
  const original = console[method].bind(console);
  console[method] = (...args: unknown[]) => {
    try {
      original(...args);
    } catch {
      // the console pipe is gone — drop the line
    }
  };
}

interface WindowBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface DesktopSettings {
  lastWorkspace?: string;
  approvalPolicy?: string;
  workspaces?: string[];
  colorScheme?: string;
  windowBounds?: WindowBounds;
  windowMaximized?: boolean;
}

interface CallEnvelope {
  ok: boolean;
  result?: unknown;
  error?: { code: number; message: string; data?: Record<string, unknown> };
}

interface WorkspaceSessionSummary {
  path: string;
  count: number;
  latestSessionId: string | null;
  latestTitle: string | null;
  latestUpdatedAt: number;
}

interface AuthEntry {
  server_url: string;
  api_key: string;
  user_id?: string | null;
  name?: string | null;
  role?: string | null;
  login_at?: string | null;
  active?: boolean;
}

interface AuthStatus {
  loggedIn: boolean;
  name: string | null;
  userId: string | null;
  role: string | null;
  serverUrl: string | null;
  loginAt: string | null;
}

interface AuthServerEntry {
  serverUrl: string;
  name: string | null;
  userId: string | null;
  active: boolean;
  loginAt: string | null;
}

const clientVersion = '0.1.0';
let mainWindow: BrowserWindow | null = null;
let supervisor: EngineSupervisor | null = null;
let browserManager: BrowserManager | null = null;
let settings: DesktopSettings = {};
let quitting = false;

// The embedded browser must keep producing frames and processing agent input while the app
// window is occluded or in the background; Chromium backgrounds occluded windows by default.
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');

const WINDOW_BACKGROUND = { dark: '#0f1115', light: '#f4f6f8' };
const WINDOW_DEFAULT = { width: 1280, height: 840 };
const WINDOW_MIN = { width: 960, height: 600 };

type ColorScheme = 'system' | 'light' | 'dark';

function normalizeColorScheme(value: unknown): ColorScheme {
  return value === 'light' || value === 'dark' ? value : 'system';
}

/** Matches the renderer's `--bg` for the palette Chromium is currently painting. */
function windowBackground(): string {
  return nativeTheme.shouldUseDarkColors ? WINDOW_BACKGROUND.dark : WINDOW_BACKGROUND.light;
}

/**
 * Appearance: 'system' follows Windows, 'light'/'dark' pin the app. The renderer styles light and
 * dark purely off `prefers-color-scheme`, which nativeTheme.themeSource overrides app-wide —
 * so switching needs no renderer-side re-render or stylesheet reload.
 */
function applyColorScheme(): void {
  nativeTheme.themeSource = normalizeColorScheme(settings.colorScheme);
  mainWindow?.setBackgroundColor(windowBackground());
}

// ---------- window placement (reopen where and how the app was left) ----------

let windowStateTimer: ReturnType<typeof setTimeout> | null = null;

function normalizeWindowBounds(value: unknown): WindowBounds | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Record<string, unknown>;
  const isNumber = (entry: unknown): entry is number => typeof entry === 'number' && Number.isFinite(entry);
  const { x, y, width, height } = raw;
  if (!isNumber(x) || !isNumber(y) || !isNumber(width) || !isNumber(height)) return null;
  return { x, y, width, height };
}

/** Saved bounds count only while they land on an attached display — monitors come and go between runs. */
function restorableWindowBounds(): WindowBounds | null {
  const saved = normalizeWindowBounds(settings.windowBounds);
  if (!saved) return null;
  const bounds = {
    ...saved,
    width: Math.max(saved.width, WINDOW_MIN.width),
    height: Math.max(saved.height, WINDOW_MIN.height),
  };
  const onScreen = screen.getAllDisplays().some((display) => {
    const area = display.workArea;
    return bounds.x < area.x + area.width && bounds.x + bounds.width > area.x
      && bounds.y < area.y + area.height && bounds.y + bounds.height > area.y;
  });
  return onScreen ? bounds : null;
}

/** `getNormalBounds` keeps the un-maximized rect, so restoring a maximized session stays one click reversible. */
function rememberWindowState(): void {
  if (!mainWindow || mainWindow.isDestroyed() || mainWindow.isMinimized()) return;
  const bounds = mainWindow.getNormalBounds();
  settings.windowBounds = { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
  settings.windowMaximized = mainWindow.isMaximized();
  saveSettings();
}

/** Drag-resize fires move/resize continuously; coalesce the writes to settings.json. */
function scheduleWindowStateSave(): void {
  if (windowStateTimer) clearTimeout(windowStateTimer);
  windowStateTimer = setTimeout(() => {
    windowStateTimer = null;
    rememberWindowState();
  }, 400);
}

function flushWindowStateSave(): void {
  if (windowStateTimer) {
    clearTimeout(windowStateTimer);
    windowStateTimer = null;
  }
  rememberWindowState();
}

function settingsFile(): string {
  return path.join(app.getPath('userData'), 'settings.json');
}

function loadSettings(): DesktopSettings {
  try {
    return normalizeSettings(JSON.parse(readFileSync(settingsFile(), 'utf8')) as DesktopSettings);
  } catch {
    return { workspaces: [] };
  }
}

function normalizeSettings(loaded: DesktopSettings): DesktopSettings {
  const settings = { ...loaded };
  const list = Array.isArray(settings.workspaces)
    ? settings.workspaces.filter((entry): entry is string => typeof entry === 'string')
    : [];
  if (settings.lastWorkspace && !list.includes(settings.lastWorkspace)) {
    list.unshift(settings.lastWorkspace);
  }
  settings.workspaces = list.slice(0, 8);
  return settings;
}

function rememberWorkspace(workspace: string): void {
  const list = (settings.workspaces ?? []).filter((entry) => entry !== workspace);
  list.unshift(workspace);
  settings.workspaces = list.slice(0, 8);
  settings.lastWorkspace = workspace;
  saveSettings();
}

function localDateString(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function defaultWorkspacePath(): string {
  return path.join(os.homedir(), '.core-ai', 'workspaces', localDateString());
}

/** The threshold-free default: a dated workspace under ~/.core-ai/workspaces, created on demand. */
function defaultWorkspace(): string {
  const workspace = defaultWorkspacePath();
  mkdirSync(workspace, { recursive: true });
  rememberWorkspace(workspace);
  return workspace;
}

/**
 * Startup stays neutral: unless today's default workspace already exists (the user worked in it
 * earlier today), the app starts in the virtual "default" state and creates nothing.
 * Manual workspaces are one click away in the dropdown; they are never auto-connected.
 */
function resolveStartupWorkspace(): string | null {
  const workspace = defaultWorkspacePath();
  return existsSync(workspace) ? workspace : null;
}

// ---------- cross-workspace session summaries (read-only disk scan) ----------

/** Sessions live in ~/.core-ai/sessions/<workspace folder name> (mirrors PathUtils.sessionsDir). */
function sessionsDirOf(workspace: string): string {
  const folder = path.basename(workspace) || 'root';
  return path.join(os.homedir(), '.core-ai', 'sessions', folder);
}

function firstTextPart(content: unknown): string | null {
  if (!Array.isArray(content)) return null;
  for (const part of content as Array<{ type?: unknown; text?: unknown }>) {
    if (String(part?.type ?? '').toLowerCase() === 'text' && typeof part?.text === 'string' && part.text.trim()) {
      return part.text.trim().slice(0, 80);
    }
  }
  return null;
}

/** Mirrors the engine's title rule: renamed sidecar first, then the first user message. */
function readSessionTitle(workspace: string, sessionId: string): string | null {
  try {
    const dir = sessionsDirOf(workspace);
    const sidecar = path.join(dir, 'titles.json');
    if (existsSync(sidecar)) {
      const titles = JSON.parse(readFileSync(sidecar, 'utf8')) as Record<string, string>;
      const renamed = titles?.[sessionId];
      if (typeof renamed === 'string' && renamed.trim()) return renamed.trim();
    }
    const file = path.join(dir, `${sessionId}.data`);
    if (!existsSync(file) || statSync(file).size > 15 * 1024 * 1024) return null;
    const data = JSON.parse(readFileSync(file, 'utf8')) as { messages?: unknown[]; history?: unknown[] };
    for (const list of [data.history, data.messages]) {
      if (!Array.isArray(list)) continue;
      for (const message of list as Array<{ role?: unknown; content?: unknown }>) {
        if (String(message?.role ?? '').toLowerCase() !== 'user') continue;
        const text = firstTextPart(message?.content);
        if (text) return text;
      }
    }
    return null;
  } catch {
    return null;
  }
}

function summarizeWorkspace(workspace: string): WorkspaceSessionSummary {
  const summary: WorkspaceSessionSummary = { path: workspace, count: 0, latestSessionId: null, latestTitle: null, latestUpdatedAt: 0 };
  if (!existsSync(workspace)) return summary;
  try {
    const dir = sessionsDirOf(workspace);
    let latestMtime = 0;
    let latestFile: string | null = null;
    for (const name of readdirSync(dir)) {
      if (!name.endsWith('.data')) continue;
      summary.count += 1;
      const mtime = statSync(path.join(dir, name)).mtimeMs;
      if (mtime > latestMtime) {
        latestMtime = mtime;
        latestFile = name;
      }
    }
    if (latestFile) {
      summary.latestSessionId = latestFile.slice(0, -'.data'.length);
      summary.latestUpdatedAt = latestMtime;
    }
  } catch {
    // no sessions dir yet
  }
  return summary;
}

function buildWorkspaceSummaries(): WorkspaceSessionSummary[] {
  const paths = [...(settings.workspaces ?? [])];
  const fallback = defaultWorkspacePath();
  if (!paths.includes(fallback)) paths.push(fallback);
  const summaries = paths.map(summarizeWorkspace);
  let candidate: WorkspaceSessionSummary | null = null;
  for (const summary of summaries) {
    if (summary.latestSessionId && (!candidate || summary.latestUpdatedAt > candidate.latestUpdatedAt)) {
      candidate = summary;
    }
  }
  if (candidate?.latestSessionId) {
    candidate.latestTitle = readSessionTitle(candidate.path, candidate.latestSessionId);
  }
  return summaries;
}

// ---------- server auth (~/.core-ai/auth.json, shared with core-ai-cli) ----------

function authFile(): string {
  return path.join(os.homedir(), '.core-ai', 'auth.json');
}

function loadAuthEntries(): AuthEntry[] {
  try {
    const parsed = JSON.parse(readFileSync(authFile(), 'utf8')) as AuthEntry | AuthEntry[];
    if (Array.isArray(parsed)) return parsed;
    if (parsed && typeof parsed === 'object') return [parsed];
    return [];
  } catch {
    return [];
  }
}

function activeAuthEntry(): AuthEntry | null {
  return loadAuthEntries().find((entry) => entry.active && entry.api_key) ?? null;
}

function authStatus(): AuthStatus {
  const entry = activeAuthEntry();
  if (!entry) {
    return { loggedIn: false, name: null, userId: null, role: null, serverUrl: null, loginAt: null };
  }
  return {
    loggedIn: true,
    name: entry.name ?? null,
    userId: entry.user_id ?? null,
    role: entry.role ?? null,
    serverUrl: entry.server_url ?? null,
    loginAt: entry.login_at ?? null,
  };
}

/** Saved environments (dev / uat / prod …), kubectl-context style: many entries, one active. */
function listAuthServers(): AuthServerEntry[] {
  return loadAuthEntries()
    .filter((entry) => entry.server_url && entry.api_key)
    .map((entry) => ({
      serverUrl: entry.server_url,
      name: entry.name ?? null,
      userId: entry.user_id ?? null,
      active: Boolean(entry.active),
      loginAt: entry.login_at ?? null,
    }))
    .sort((a, b) => Number(b.active) - Number(a.active) || (b.loginAt ?? '').localeCompare(a.loginAt ?? ''));
}

function writeAuthEntries(entries: AuthEntry[]): void {
  mkdirSync(path.dirname(authFile()), { recursive: true });
  writeFileSync(authFile(), JSON.stringify(entries, null, 2));
}

function saveAuthEntry(serverUrl: string, apiKey: string, userId: string | null, name: string | null): void {
  const entries = loadAuthEntries();
  const previous = entries.find((entry) => entry.server_url === serverUrl);
  const kept = entries
    .filter((entry) => entry.server_url !== serverUrl)
    .map((entry) => ({ ...entry, active: false }));
  kept.push({
    server_url: serverUrl,
    api_key: apiKey,
    user_id: userId,
    name,
    role: previous?.role ?? null,
    login_at: new Date().toISOString(),
    active: true,
  });
  writeAuthEntries(kept);
}

function removeAuthEntries(serverUrl: string): void {
  writeAuthEntries(loadAuthEntries().filter((entry) => entry.server_url !== serverUrl));
}

async function fetchUserProfile(serverUrl: string, apiKey: string): Promise<{ id?: string; name?: string } | null> {
  try {
    const response = await fetch(`${serverUrl}/api/user/me`, { headers: { Authorization: `Bearer ${apiKey}` } });
    if (response.status !== 200) return null;
    return (await response.json()) as { id?: string; name?: string };
  } catch {
    return null;
  }
}

function normalizeServerUrl(value: string): string {
  const trimmed = value.trim().replace(/\/+$/, '');
  return /^https?:\/\//.test(trimmed) ? trimmed : '';
}

function authPage(title: string, message: string, ok: boolean): string {
  const color = ok ? '#4ade80' : '#f87171';
  const mark = ok ? '&#10003;' : '&#10007;';
  return '<!DOCTYPE html><html><head><meta charset="utf-8"><title>' + title + '</title>'
    + '<style>body { font-family: -apple-system, sans-serif; display: flex; justify-content: center; align-items: center;'
    + ' height: 100vh; margin: 0; background: #1a1a2e; color: #e0e0e0; }'
    + ' .box { text-align: center; padding: 48px; } h1 { color: ' + color + '; margin-bottom: 8px; } p { color: #888; }</style>'
    + '</head><body><div class="box"><h1>' + mark + ' ' + title + '</h1><p>' + message + '</p></div></body></html>';
}

/** Browser login: local callback server + the same redirect contract as core-ai-cli. */
function waitForBrowserCallback(serverUrl: string): Promise<string> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      server.close();
    };
    const server = http.createServer((request, response) => {
      try {
        const url = new URL(request.url ?? '/', 'http://127.0.0.1');
        const apiKey = url.searchParams.get('api_key');
        const error = url.searchParams.get('error');
        if (error) {
          response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          response.end(authPage('Login Failed', error, false));
          finish();
          reject(new Error(error));
        } else if (apiKey && apiKey.trim()) {
          response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          response.end(authPage('Login Complete', 'You may close this tab and return to the app.', true));
          finish();
          resolve(apiKey.trim());
        } else {
          response.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
          response.end('missing api_key');
        }
      } catch (error) {
        finish();
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
    const timer = setTimeout(() => {
      finish();
      reject(new Error('timed out waiting for browser confirmation'));
    }, 120_000);
    server.on('error', (error) => {
      finish();
      reject(error);
    });
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      const callback = encodeURIComponent(`http://127.0.0.1:${port}/callback`);
      void shell.openExternal(`${serverUrl}/login?callback=${callback}`);
    });
  });
}

async function loginViaBrowser(serverUrl: string): Promise<AuthStatus> {
  const apiKey = await waitForBrowserCallback(serverUrl);
  const profile = await fetchUserProfile(serverUrl, apiKey);
  saveAuthEntry(serverUrl, apiKey, profile?.id ?? null, profile?.name ?? null);
  await supervisor?.shutdownAll();
  return authStatus();
}

async function loginWithApiKey(serverUrl: string, apiKey: string): Promise<AuthStatus> {
  const profile = await fetchUserProfile(serverUrl, apiKey);
  if (!profile) throw new Error('API key rejected by the server (GET /api/user/me failed)');
  saveAuthEntry(serverUrl, apiKey, profile.id ?? null, profile.name ?? null);
  await supervisor?.shutdownAll();
  return authStatus();
}

function saveSettings(): void {
  try {
    writeFileSync(settingsFile(), JSON.stringify(settings, null, 2));
  } catch (error) {
    console.error('failed to save desktop settings', error);
  }
}

function resolveEnginePath(): string {
  if (process.env.CORE_AI_ENGINE_PATH) return process.env.CORE_AI_ENGINE_PATH;
  const repoRoot = path.resolve(app.getAppPath(), '..');
  const name = process.platform === 'win32' ? 'core-ai-cli.bat' : 'core-ai-cli';
  return path.join(repoRoot, 'build', 'core-ai-cli', 'install', 'core-ai-cli', 'bin', name);
}

function send(channel: string, payload: unknown): void {
  mainWindow?.webContents.send(channel, payload);
}

function createWindow(): void {
  const windowIcon = path.join(__dirname, 'logo.png');
  const bounds = restorableWindowBounds();
  mainWindow = new BrowserWindow({
    ...(bounds ? { x: bounds.x, y: bounds.y } : {}),
    width: bounds?.width ?? WINDOW_DEFAULT.width,
    height: bounds?.height ?? WINDOW_DEFAULT.height,
    minWidth: WINDOW_MIN.width,
    minHeight: WINDOW_MIN.height,
    show: false,
    backgroundColor: windowBackground(),
    title: 'core-ai',
    icon: existsSync(windowIcon) ? windowIcon : undefined,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.removeMenu();
  // Maximize before the window is shown so a remembered maximized session never flashes a windowed frame.
  if (settings.windowMaximized) mainWindow.maximize();
  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('resize', scheduleWindowStateSave);
  mainWindow.on('move', scheduleWindowStateSave);
  mainWindow.on('maximize', scheduleWindowStateSave);
  mainWindow.on('unmaximize', scheduleWindowStateSave);
  mainWindow.on('close', flushWindowStateSave);
  void mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (input.type === 'keyDown' && input.key === 'F12') {
      mainWindow?.webContents.toggleDevTools();
      event.preventDefault();
    }
  });
  mainWindow.on('closed', () => {
    mainWindow = null;
    browserManager?.attachWindow(null);
  });
}

function registerIpc(): void {
  ipcMain.handle('app:info', () => {
    const enginePath = resolveEnginePath();
    return {
      enginePath,
      engineExists: existsSync(enginePath),
      desktopVersion: clientVersion,
      electron: process.versions.electron,
      node: process.versions.node,
      userData: app.getPath('userData'),
      homedir: os.homedir(),
      defaultWorkspacePath: defaultWorkspacePath(),
      defaultExists: existsSync(defaultWorkspacePath()),
      startupWorkspace: resolveStartupWorkspace(),
    };
  });

  ipcMain.handle('settings:get', () => settings);

  ipcMain.handle('settings:set', async (_event, patch: DesktopSettings) => {
    const previousPolicy = settings.approvalPolicy ?? 'ask';
    settings = { ...settings, ...patch };
    saveSettings();
    applyColorScheme();
    const nextPolicy = settings.approvalPolicy ?? 'ask';
    if (settings.lastWorkspace && nextPolicy !== previousPolicy) {
      await supervisor?.restart(settings.lastWorkspace);
    }
    return settings;
  });

  ipcMain.handle('workspace:choose', async () => {
    const result = await dialog.showOpenDialog({ properties: ['openDirectory'] });
    if (result.canceled || result.filePaths.length === 0) return null;
    rememberWorkspace(result.filePaths[0]);
    return settings.lastWorkspace;
  });

  ipcMain.handle('workspace:default', () => defaultWorkspace());

  ipcMain.handle('workspaces:summaries', () => buildWorkspaceSummaries());

  ipcMain.handle('auth:status', () => authStatus());

  ipcMain.handle('auth:servers', () => listAuthServers());

  ipcMain.handle('auth:switch', async (_event, serverUrl: string) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized) throw new Error('invalid server url');
    const entries = loadAuthEntries();
    if (!entries.some((entry) => entry.server_url === normalized)) {
      throw new Error(`environment is not signed in: ${normalized}`);
    }
    writeAuthEntries(entries.map((entry) => ({ ...entry, active: entry.server_url === normalized })));
    await supervisor?.shutdownAll();
    return authStatus();
  });

  ipcMain.handle('auth:login', async (_event, serverUrl: string) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized) throw new Error('invalid server url');
    return loginViaBrowser(normalized);
  });

  ipcMain.handle('auth:loginWithKey', async (_event, serverUrl: string, apiKey: string) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized) throw new Error('invalid server url');
    if (!apiKey || !apiKey.trim()) throw new Error('empty api key');
    return loginWithApiKey(normalized, apiKey.trim());
  });

  ipcMain.handle('auth:logout', async () => {
    const active = activeAuthEntry();
    if (!active) return authStatus();
    const confirm = await dialog.showMessageBox({
      type: 'question',
      buttons: ['Sign out', 'Cancel'],
      defaultId: 1,
      cancelId: 1,
      message: `Sign out of ${active.server_url}?`,
      detail: 'The saved credential will be deleted. The CLI and this app share the same sign-in.',
    });
    if (confirm.response !== 0) return authStatus();
    removeAuthEntries(active.server_url);
    await supervisor?.shutdownAll();
    return authStatus();
  });

  ipcMain.handle('engine:restart', async (_event, workspace: string) => {
    if (typeof workspace === 'string' && workspace) {
      await supervisor?.restart(workspace);
    }
  });

  ipcMain.handle('browser:start', async () => browserManager?.start() ?? null);

  ipcMain.handle('browser:stop', async () => browserManager?.stop() ?? null);

  ipcMain.handle('browser:status', () => browserManager?.status() ?? null);

  ipcMain.handle('browser:setBounds', (_event, bounds: unknown) => {
    browserManager?.setBounds(isBounds(bounds) ? bounds : null);
  });

  ipcMain.handle('browser:navigate', (_event, url: string) => browserManager?.navigate(String(url ?? '')) ?? null);

  ipcMain.handle('browser:back', () => browserManager?.goBack() ?? null);

  ipcMain.handle('browser:forward', () => browserManager?.goForward() ?? null);

  ipcMain.handle('browser:reload', () => browserManager?.reload() ?? null);

  ipcMain.handle('browser:stopLoading', () => browserManager?.stopLoading() ?? null);

  ipcMain.handle('browser:newTab', (_event, url?: string) => browserManager?.newTab(url) ?? null);

  ipcMain.handle('browser:closeTab', (_event, id: string) => browserManager?.closeTab(String(id ?? '')) ?? null);

  ipcMain.handle('browser:selectTab', (_event, id: string) => browserManager?.selectTab(String(id ?? '')) ?? null);

  ipcMain.handle('engine:call', async (_event, workspace: string, method: string, params: Record<string, unknown>): Promise<CallEnvelope> => {
    if (!supervisor) return { ok: false, error: { code: 0, message: 'engine supervisor not ready' } };
    try {
      const result = await supervisor.call(workspace, method, params ?? {}, settings.approvalPolicy ?? 'ask');
      return { ok: true, result };
    } catch (error) {
      if (error instanceof RpcCallError) {
        return { ok: false, error: { code: error.code, message: error.message, data: error.data } };
      }
      return { ok: false, error: { code: 0, message: error instanceof Error ? error.message : String(error) } };
    }
  });

  ipcMain.handle('shell:openPath', async (_event, workspace: string, relativePath: string) => {
    const absolute = resolveInsideWorkspace(workspace, relativePath);
    if (!absolute) return 'path escapes the workspace';
    return (await shell.openPath(absolute)) || null;
  });

  ipcMain.handle('shell:revealPath', (_event, workspace: string, relativePath: string) => {
    const absolute = resolveInsideWorkspace(workspace, relativePath);
    if (absolute) shell.showItemInFolder(absolute);
  });

  ipcMain.handle('shell:openExternal', async (_event, url: string) => {
    if (typeof url === 'string' && /^https?:\/\//i.test(url)) {
      await shell.openExternal(url);
    }
  });
}

function resolveInsideWorkspace(workspace: string, relativePath: string): string | null {
  const root = path.resolve(workspace);
  const absolute = path.resolve(root, relativePath);
  return absolute.startsWith(root) ? absolute : null;
}

interface BoundsPayload {
  x: number;
  y: number;
  width: number;
  height: number;
}

function isBounds(value: unknown): value is BoundsPayload {
  if (!value || typeof value !== 'object') return false;
  const bounds = value as Record<string, unknown>;
  return ['x', 'y', 'width', 'height'].every((key) => typeof bounds[key] === 'number' && Number.isFinite(bounds[key]));
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  void app.whenReady().then(() => {
    settings = loadSettings();
    applyColorScheme();
    nativeTheme.on('updated', () => mainWindow?.setBackgroundColor(windowBackground()));
    browserManager = new BrowserManager({
      onState: (status) => send('browser:state', status),
      onNeedsPanel: () => send('browser:needPanel', {}),
    });
    supervisor = new EngineSupervisor({
      enginePath: resolveEnginePath(),
      clientVersion,
      extraEnv: () => browserManager?.engineEnv() ?? {},
      onEvent: (workspace, method, params) => send('engine:event', { workspace, method, params }),
      onLog: (workspace, line) => {
        console.error(`[engine ${workspace}] ${line}`);
        send('engine:log', { workspace, line });
      },
    });
    registerIpc();
    createWindow();
    browserManager.attachWindow(mainWindow);
    if (process.env.COREAI_BROWSER_AUTOSTART === '1') {
      void browserManager.start().catch((error: Error) => console.error(`[browser] autostart failed: ${error.message}`));
    }
  });

  app.on('window-all-closed', () => {
    app.quit();
  });

  app.on('before-quit', (event) => {
    if (quitting || !supervisor) return;
    event.preventDefault();
    quitting = true;
    void browserManager?.stop();
    const failsafe = setTimeout(() => app.exit(0), 8_000);
    void supervisor.shutdownAll().finally(() => {
      clearTimeout(failsafe);
      app.exit(0);
    });
  });
}
