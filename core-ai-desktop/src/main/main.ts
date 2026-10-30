// Electron main: window + IPC + engine supervision. Agent work (loops, file IO, compression)
// stays in the engine child process; this process only routes.
import { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, nativeTheme, screen, shell, Tray } from 'electron';
import QRCode from 'qrcode';
import { spawn } from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { removeStaged, stageAttachments, stageBuffers, sweepStaging, type StagedAttachment, type StagedBufferInput } from './attachments/AttachmentStager';
import { artifactBase, registerArtifactProtocol, registerArtifactScheme } from './artifacts/ArtifactProtocol';
import {
  type AuthServerEntry,
  type AuthStatus,
  activeAuthEntry,
  authServersOf,
  authStatusOf,
  loadAuthEntries,
  withActive,
  withInsecure,
  withSavedEntry,
  without,
  writeAuthEntries,
} from './auth/AuthStore';
import { BrowserManager } from './browser/BrowserManager';
import { ChannelBridge, type ChannelBridgeState } from './channels/ChannelBridge';
import { type ChannelBinding, resolveBindingWorkspace } from './channels/Bindings';
import { createOcgPort, OcgHttpPort } from './channels/OcgClient';
import { ocgAck, ocgReply, ocgSend, ocgWait, type OcgInboundMessage } from './channels/OcgCli';
import { CuaManager } from './computer/CuaManager';
import { BuiltinSkills } from './skills/BuiltinSkills';
import { browserUseStatus, installBrowserUse, installOcg, ocgStatus, resolveExecutable, runOcg, withExecutableOnPath } from './skills/RuntimeTools';
import { EngineSupervisor } from './engine/EngineSupervisor';
import { RpcCallError } from './engine/RpcClient';
import { ScheduleWaker } from './schedule/ScheduleWaker';
import { readEngineManifest, standaloneCliTargets, syncStandaloneClis } from './update/CliUpdate';
import { installArgs, UpdateService } from './update/UpdateService';

for (const stream of [process.stdout, process.stderr]) stream.on('error', () => undefined);
for (const method of ['log', 'info', 'warn', 'error'] as const) {
  const original = console[method].bind(console);
  console[method] = (...args: unknown[]) => { try { original(...args); } catch { /* closed console pipe */ } };
}

interface WindowBounds { x: number; y: number; width: number; height: number }
interface DesktopSettings {
  lastWorkspace?: string;
  approvalPolicy?: string;
  workspaces?: string[];
  colorScheme?: string;
  windowBounds?: WindowBounds;
  windowMaximized?: boolean;
  computerUse?: boolean;
  browserSkillEnabled?: boolean;
  channelsSkillEnabled?: boolean;
  /** P0: route incoming IM messages from the local OCG mailbox into desktop sessions. */
  channelsBridgeEnabled?: boolean;
  /** IM bindings: channel/account/peer -> workspace. */
  channelBindings?: ChannelBinding[];
  backgroundScheduling?: boolean;
  /** Bundled engine version whose standalone CLI copies this install has already refreshed. */
  cliSyncedEngineVersion?: string;
}
interface CallEnvelope { ok: boolean; result?: unknown; error?: { code: number; message: string; data?: Record<string, unknown> } }
interface WorkspaceSessionSummary { path: string; count: number; latestSessionId: string | null; latestTitle: string | null; latestUpdatedAt: number }
declare const __APP_VERSION__: string;

const clientVersion = __APP_VERSION__;
let mainWindow: BrowserWindow | null = null;
let supervisor: EngineSupervisor | null = null;
let browserManager: BrowserManager | null = null;
let cuaManager: CuaManager | null = null;
let builtinSkills: BuiltinSkills | null = null;
let updateService: UpdateService | null = null;
let scheduleWaker: ScheduleWaker | null = null;
let channelBridge: ChannelBridge | null = null;
let ocgAdmin: OcgHttpPort | null = null;
let tray: Tray | null = null;
let settings: DesktopSettings = {};
let quitting = false;
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
registerArtifactScheme();
const WINDOW_BACKGROUND = { dark: '#0f1115', light: '#f4f6f8' };
const WINDOW_DEFAULT = { width: 1280, height: 840 };
const WINDOW_MIN = { width: 960, height: 600 };
const UPDATE_CHECK_DELAY_MS = 3_000;
const CLI_SYNC_DELAY_MS = 4_000;
type ColorScheme = 'system' | 'light' | 'dark';
function normalizeColorScheme(value: unknown): ColorScheme {
  return value === 'light' || value === 'dark' ? value : 'system';
}
function windowBackground(): string {
  return nativeTheme.shouldUseDarkColors ? WINDOW_BACKGROUND.dark : WINDOW_BACKGROUND.light;
}
function applyColorScheme(): void {
  nativeTheme.themeSource = normalizeColorScheme(settings.colorScheme);
  mainWindow?.setBackgroundColor(windowBackground());
}
let windowStateTimer: ReturnType<typeof setTimeout> | null = null;
function normalizeWindowBounds(value: unknown): WindowBounds | null {
  if (!value || typeof value !== 'object') return null;
  const { x, y, width, height } = value as Record<string, unknown>;
  if (![x, y, width, height].every((entry) => typeof entry === 'number' && Number.isFinite(entry))) return null;
  return { x: x as number, y: y as number, width: width as number, height: height as number };
}
function restorableWindowBounds(): WindowBounds | null {
  const saved = normalizeWindowBounds(settings.windowBounds);
  if (!saved) return null;
  const bounds = { ...saved, width: Math.max(saved.width, WINDOW_MIN.width), height: Math.max(saved.height, WINDOW_MIN.height) };
  const onScreen = screen.getAllDisplays().some(({ workArea }) => bounds.x < workArea.x + workArea.width && bounds.x + bounds.width > workArea.x && bounds.y < workArea.y + workArea.height && bounds.y + bounds.height > workArea.y);
  return onScreen ? bounds : null;
}
function rememberWindowState(): void {
  if (!mainWindow || mainWindow.isDestroyed() || mainWindow.isMinimized()) return;
  const bounds = mainWindow.getNormalBounds();
  settings.windowBounds = { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
  settings.windowMaximized = mainWindow.isMaximized();
  saveSettings();
}
function scheduleWindowStateSave(): void {
  if (windowStateTimer) clearTimeout(windowStateTimer);
  windowStateTimer = setTimeout(() => { windowStateTimer = null; rememberWindowState(); }, 400);
}
function flushWindowStateSave(): void {
  if (windowStateTimer) clearTimeout(windowStateTimer);
  windowStateTimer = null;
  rememberWindowState();
}
function settingsFile(): string { return path.join(app.getPath('userData'), 'settings.json'); }
function loadSettings(): DesktopSettings {
  try { return normalizeSettings(JSON.parse(readFileSync(settingsFile(), 'utf8')) as DesktopSettings); }
  catch { return normalizeSettings({ workspaces: [] }); }
}
function normalizeSettings(loaded: DesktopSettings): DesktopSettings {
  const result = { ...loaded };
  if (result.computerUse === undefined) result.computerUse = true;
  if (result.browserSkillEnabled === undefined) result.browserSkillEnabled = true;
  const list = Array.isArray(result.workspaces) ? result.workspaces.filter((entry): entry is string => typeof entry === 'string') : [];
  if (result.lastWorkspace && !list.includes(result.lastWorkspace)) list.unshift(result.lastWorkspace);
  result.workspaces = list.slice(0, 8);
  return result;
}
function rememberWorkspace(workspace: string): void {
  settings.workspaces = [workspace, ...(settings.workspaces ?? []).filter((entry) => entry !== workspace)].slice(0, 8);
  settings.lastWorkspace = workspace;
  saveSettings();
}
function localDateString(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}
function defaultWorkspacePath(): string { return path.join(os.homedir(), '.core-ai', 'workspaces', localDateString()); }
function defaultWorkspace(): string {
  const workspace = defaultWorkspacePath();
  mkdirSync(workspace, { recursive: true });
  rememberWorkspace(workspace);
  return workspace;
}
function resolveStartupWorkspace(): string | null {
  const last = settings.lastWorkspace;
  if (last && existsSync(last)) return last;
  const workspace = defaultWorkspacePath();
  return existsSync(workspace) ? workspace : null;
}
/** Workspace for IM-created sessions: a binding first, else the last workspace, else today's default. */
function resolveBridgeWorkspace(message?: OcgInboundMessage): string {
  if (message) {
    const bound = resolveBindingWorkspace(settings.channelBindings, message);
    if (bound && existsSync(bound)) return bound;
  }
  const last = settings.lastWorkspace;
  if (last && existsSync(last)) return last;
  return defaultWorkspace();
}
/** The bridge is only a client: OCG's daemon does not survive a reboot, so recover it before polling. */
async function ensureOcgDaemon(): Promise<void> {
  try {
    ocgAdmin ??= new OcgHttpPort();
    const health = await ocgAdmin.health();
    if (health.ok) return;
    console.log(`[channels] OCG daemon is not reachable (${health.error ?? 'unknown'}); starting it`);
    const result = await runOcg(['daemon', 'start'], 150_000);
    if (result.code === 0) {
      console.log('[channels] OCG daemon started');
    } else {
      console.error(`[channels] OCG daemon start failed: ${result.stderr || result.stdout}`);
    }
  } catch (error) {
    console.error(`[channels] OCG daemon start failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}
function sessionsDirOf(workspace: string): string { return path.join(os.homedir(), '.core-ai', 'sessions', path.basename(workspace) || 'root'); }
function firstTextPart(content: unknown): string | null {
  if (!Array.isArray(content)) return null;
  for (const part of content as Array<{ type?: unknown; text?: unknown }>) {
    if (String(part?.type ?? '').toLowerCase() === 'text' && typeof part?.text === 'string' && part.text.trim()) return part.text.trim().slice(0, 80);
  }
  return null;
}
function readSessionTitle(workspace: string, sessionId: string): string | null {
  try {
    const dir = sessionsDirOf(workspace);
    const sidecar = path.join(dir, 'titles.json');
    if (existsSync(sidecar)) {
      const renamed = (JSON.parse(readFileSync(sidecar, 'utf8')) as Record<string, string>)?.[sessionId];
      if (typeof renamed === 'string' && renamed.trim()) return renamed.trim();
    }
    const file = path.join(dir, `${sessionId}.data`);
    if (!existsSync(file) || statSync(file).size > 15 * 1024 * 1024) return null;
    const data = JSON.parse(readFileSync(file, 'utf8')) as { messages?: unknown[]; history?: unknown[] };
    for (const list of [data.history, data.messages]) {
      if (!Array.isArray(list)) continue;
      for (const message of list as Array<{ role?: unknown; content?: unknown }>) {
        if (String(message?.role ?? '').toLowerCase() !== 'user') continue;
        const text = firstTextPart(message.content);
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
    let latestFile: string | null = null;
    for (const name of readdirSync(sessionsDirOf(workspace))) {
      if (!name.endsWith('.data')) continue;
      summary.count += 1;
      const mtime = statSync(path.join(sessionsDirOf(workspace), name)).mtimeMs;
      if (mtime > summary.latestUpdatedAt) {
        summary.latestUpdatedAt = mtime;
        latestFile = name;
      }
    }
    if (latestFile) {
      summary.latestSessionId = latestFile.slice(0, -5);
      summary.latestTitle = readSessionTitle(workspace, summary.latestSessionId);
    }
  } catch {
    // no sessions directory yet
  }
  return summary;
}
function buildWorkspaceSummaries(): WorkspaceSessionSummary[] {
  const workspaces = [...(settings.workspaces ?? [])];
  const fallback = defaultWorkspacePath();
  if (!workspaces.includes(fallback)) workspaces.push(fallback);
  return workspaces.map(summarizeWorkspace);
}
function authStatus(): AuthStatus { return authStatusOf(loadAuthEntries()); }
function listAuthServers(): AuthServerEntry[] { return authServersOf(loadAuthEntries()); }
function saveAuthEntry(serverUrl: string, apiKey: string, userId: string | null, name: string | null, insecure: boolean): void {
  writeAuthEntries(withSavedEntry(loadAuthEntries(), serverUrl, apiKey, userId, name, insecure));
}
/** Flips the per-server TLS opt-in the CLI engine reads; the engine re-reads auth.json per request. */
function setAuthInsecure(serverUrl: string, insecure: boolean): AuthStatus {
  const entries = loadAuthEntries();
  if (!entries.some((entry) => entry.server_url === serverUrl)) throw new Error(`environment is not signed in: ${serverUrl}`);
  writeAuthEntries(withInsecure(entries, serverUrl, insecure));
  return authStatus();
}
function removeAuthEntries(serverUrl: string): void { writeAuthEntries(without(loadAuthEntries(), serverUrl)); }
async function fetchUserProfile(serverUrl: string, apiKey: string, insecure: boolean): Promise<{ id?: string; name?: string } | null> {
  if (insecure) return fetchUserProfileInsecure(serverUrl, apiKey);
  try {
    const response = await fetch(`${serverUrl}/api/user/me`, { headers: { Authorization: `Bearer ${apiKey}` } });
    return response.status === 200 ? await response.json() as { id?: string; name?: string } : null;
  } catch {
    return null;
  }
}
/**
 * A self-signed server never reaches Chromium's fetch — the certificate is rejected before a
 * response exists. The explicit opt-in therefore asks through node:https with verification off.
 */
function fetchUserProfileInsecure(serverUrl: string, apiKey: string): Promise<{ id?: string; name?: string } | null> {
  return new Promise((resolve) => {
    let target: URL;
    try {
      target = new URL(`${serverUrl}/api/user/me`);
    } catch {
      resolve(null);
      return;
    }
    const options = {
      hostname: target.hostname,
      port: target.port || (target.protocol === 'http:' ? 80 : 443),
      path: `${target.pathname}${target.search}`,
      method: 'GET',
      headers: { Authorization: `Bearer ${apiKey}` },
      timeout: 15_000,
    };
    const handler = (response: http.IncomingMessage): void => {
      const chunks: Buffer[] = [];
      response.on('data', (chunk: Buffer) => chunks.push(chunk));
      response.on('end', () => {
        if (response.statusCode !== 200) {
          resolve(null);
          return;
        }
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')) as { id?: string; name?: string });
        } catch {
          resolve(null);
        }
      });
    };
    const request = target.protocol === 'http:'
      ? http.request(options, handler)
      : https.request({ ...options, rejectUnauthorized: false }, handler);
    request.on('timeout', () => request.destroy());
    request.on('error', () => resolve(null));
    request.end();
  });
}
function normalizeServerUrl(value: string): string {
  const trimmed = value.trim().replace(/\/+$/, '');
  return /^https?:\/\//.test(trimmed) ? trimmed : '';
}
function authPage(title: string, message: string, ok: boolean): string {
  const color = ok ? '#4ade80' : '#f87171';
  const mark = ok ? '&#10003;' : '&#10007;';
  return '<!DOCTYPE html><html><head><meta charset="utf-8"><title>' + title + '</title><style>body{font-family:-apple-system,sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#e0e0e0}.box{text-align:center;padding:48px}h1{color:' + color + ';margin-bottom:8px}p{color:#888}</style></head><body><div class="box"><h1>' + mark + ' ' + title + '</h1><p>' + message + '</p></div></body></html>';
}
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
        } else if (apiKey?.trim()) {
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
      void shell.openExternal(`${serverUrl}/login?callback=${encodeURIComponent(`http://127.0.0.1:${port}/callback`)}`);
    });
  });
}
async function loginViaBrowser(serverUrl: string, insecure: boolean): Promise<AuthStatus> {
  const key = await waitForBrowserCallback(serverUrl);
  const profile = await fetchUserProfile(serverUrl, key, insecure);
  saveAuthEntry(serverUrl, key, profile?.id ?? null, profile?.name ?? null, insecure);
  await supervisor?.shutdownAll();
  return authStatus();
}
async function loginWithApiKey(serverUrl: string, apiKey: string, insecure: boolean): Promise<AuthStatus> {
  const profile = await fetchUserProfile(serverUrl, apiKey, insecure);
  if (!profile) {
    throw new Error(insecure
      ? 'API key rejected by the server (GET /api/user/me failed)'
      : 'API key rejected by the server, or its certificate is not trusted — tick "Self-signed certificate" for a local server (GET /api/user/me failed)');
  }
  saveAuthEntry(serverUrl, apiKey, profile.id ?? null, profile.name ?? null, insecure);
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
  if (app.isPackaged) return path.join(process.resourcesPath, 'engine', process.platform === 'win32' ? 'core-ai-cli.exe' : 'core-ai-cli');
  const repoRoot = path.resolve(app.getAppPath(), '..');
  const executable = process.platform === 'win32' ? 'core-ai-cli.bat' : 'core-ai-cli';
  return path.join(repoRoot, 'build', 'core-ai-cli', 'install', 'core-ai-cli', 'bin', executable);
}
function resolveSkillsRoot(): string {
  if (app.isPackaged) return path.join(process.resourcesPath, 'skills');
  return path.resolve(app.getAppPath(), '..', 'docs', 'skills');
}
function resolveSkillSourceDir(): string {
  return path.join(resolveSkillsRoot(), 'gui-automation');
}
/**
 * Installs the skills this launch should own. Always-local: no network, no auth. The gated
 * capabilities only install when their runtime is actually ready, so the model is never told
 * about a daemon that is not there.
 */
function installConfiguredBuiltins(): void {
  if (!builtinSkills) return;
  if (builtinSkills.error) console.error(`[skills] ${builtinSkills.error}`);
  const defaults = builtinSkills.installDefaults((entry) => entry.name !== 'browser-use' || settings.browserSkillEnabled !== false);
  const installs: Array<{ name: string; result: ReturnType<BuiltinSkills['install']> }> = [];
  if (settings.computerUse === true && cuaManager?.readyForSkill()) {
    installs.push({ name: 'gui-automation', result: builtinSkills.install('gui-automation') });
  }
  if (settings.channelsSkillEnabled === true
    || (settings.channelsSkillEnabled !== false && resolveExecutable('ocg'))) {
    installs.push({ name: 'ocg', result: builtinSkills.install('ocg') });
  }
  for (const item of [...defaults.map((result) => ({ name: 'default', result })), ...installs]) {
    if (!item.result.ok) console.error(`[skills] ${item.name}: ${item.result.detail}`);
  }
}
/**
 * Keeps a separately installed CLI in step with the engine this app now bundles: a desktop
 * update swaps resources/engine, but the CLI `--upgrade` writes into ~/.core-ai/bin would
 * otherwise keep the version it was installed with. Runs once per bundled engine version and
 * never downgrades a CLI that is already newer.
 */
async function syncBundledCli(): Promise<void> {
  if (!app.isPackaged) return;
  const enginePath = resolveEnginePath();
  const manifest = readEngineManifest(enginePath);
  if (!manifest || settings.cliSyncedEngineVersion === manifest.version) return;
  const targets = standaloneCliTargets({
    homeDir: os.homedir(),
    searchPath: process.env.PATH ?? '',
    excludeDirs: [path.dirname(enginePath)],
  });
  const outcomes = await syncStandaloneClis({
    bundledBinary: enginePath,
    bundledVersion: manifest.version,
    bundledSha256: manifest.sha256,
    targets,
  });
  for (const outcome of outcomes) {
    if (outcome.status === 'updated') console.log(`[cli] updated ${outcome.path} ${outcome.from} → ${outcome.to}`);
    else if (outcome.status === 'failed') console.error(`[cli] could not update ${outcome.path}: ${outcome.reason}`);
    else if (outcome.status === 'skipped') console.log(`[cli] left ${outcome.path} alone: ${outcome.reason}`);
  }
  // A locked binary (the CLI is still running) is retried on the next launch.
  if (outcomes.some((outcome) => outcome.status === 'failed')) return;
  settings.cliSyncedEngineVersion = manifest.version;
  saveSettings();
}
async function runDesktopInstaller(installerPath: string): Promise<void> {
  await browserManager?.stop();
  await supervisor?.shutdownAll();
  await new Promise<void>((resolve, reject) => {
    const child = spawn(installerPath, installArgs(), { detached: true, stdio: 'ignore' });
    child.once('spawn', () => { child.unref(); resolve(); });
    child.once('error', reject);
  });
  app.exit(0);
}
function send(channel: string, payload: unknown): void { mainWindow?.webContents.send(channel, payload); }

function createWindow(): void {
  const windowIcon = path.join(__dirname, 'logo.png');
  const bounds = restorableWindowBounds();
  const preloadPath = path.join(__dirname, 'preload.js');
  const rendererPath = path.join(__dirname, 'renderer', 'index.html');
  mainWindow = new BrowserWindow({
    ...(bounds ? { x: bounds.x, y: bounds.y } : {}),
    width: bounds?.width ?? WINDOW_DEFAULT.width,
    height: bounds?.height ?? WINDOW_DEFAULT.height,
    minWidth: WINDOW_MIN.width,
    minHeight: WINDOW_MIN.height,
    show: false,
    backgroundColor: windowBackground(),
    title: 'core-ai-desktop',
    icon: existsSync(windowIcon) ? windowIcon : undefined,
    webPreferences: { preload: preloadPath, contextIsolation: true, nodeIntegration: false, plugins: true },
  });
  mainWindow.removeMenu();
  if (settings.windowMaximized) mainWindow.maximize();
  mainWindow.once('ready-to-show', () => mainWindow?.show());
  mainWindow.on('resize', scheduleWindowStateSave);
  mainWindow.on('move', scheduleWindowStateSave);
  mainWindow.on('maximize', scheduleWindowStateSave);
  mainWindow.on('unmaximize', scheduleWindowStateSave);
  mainWindow.on('close', (event) => {
    if (settings.backgroundScheduling && tray && !quitting) {
      event.preventDefault();
      mainWindow?.hide();
      return;
    }
    flushWindowStateSave();
  });
  void mainWindow.loadFile(rendererPath);
  // Model output is rendered as sanitized markdown, and the sanitizer allows unknown protocols so
  // absolute paths (`C:\…`, `/home/…`) survive. Only the transcript rewrites anchors through
  // shell:openExternal, so the window itself must refuse every other top-level navigation: a
  // `file:`/custom-protocol link in an artifact or memory preview must not replace the app.
  const appUrl = pathToFileURL(rendererPath).toString();
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url === appUrl || url.startsWith(`${appUrl}#`)) return;
    event.preventDefault();
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url);
  });
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) void shell.openExternal(url);
    return { action: 'deny' };
  });
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

function stagingRoot(): string {
  return path.join(os.homedir(), '.core-ai', 'app-server', 'staging');
}

const ATTACHMENT_FILTERS = [
  {
    name: 'Images and documents',
    extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf', 'md', 'txt', 'log', 'json', 'yaml', 'yml',
      'toml', 'csv', 'xml', 'html', 'css', 'js', 'ts', 'py', 'java', 'go', 'sh', 'ps1', 'sql'],
  },
  { name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp'] },
  { name: 'Documents', extensions: ['pdf', 'md', 'txt', 'log', 'json', 'yaml', 'yml', 'csv', 'xml'] },
  { name: 'All files', extensions: ['*'] },
];
async function attachPreviews(attachments: StagedAttachment[]): Promise<void> {
  await Promise.all(attachments.map(async (attachment) => {
    if (attachment.kind !== 'image') return;
    try {
      const image = await nativeImage.createThumbnailFromPath(attachment.path, { width: 1024, height: 1024 });
      if (image.isEmpty()) return;
      const jpeg = image.toJPEG(82);
      attachment.preview = jpeg.length ? `data:image/jpeg;base64,${jpeg.toString('base64')}` : image.toDataURL();
    } catch { /* optional preview */ }
  }));
}

function configureBackgroundScheduling(): void {
  if (!settings.backgroundScheduling) {
    scheduleWaker?.stop();
    scheduleWaker = null;
    if (tray) {
      tray.destroy();
      tray = null;
    }
    if (mainWindow?.isVisible() === false) mainWindow.show();
    return;
  }
  if (!tray) {
    const icon = nativeImage.createFromPath(path.join(__dirname, 'logo.png'));
    tray = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon);
    tray.setToolTip('core-ai-desktop — scheduled tasks');
    tray.on('click', () => {
      if (!mainWindow) createWindow();
      mainWindow?.show();
      mainWindow?.focus();
    });
    tray.setContextMenu(Menu.buildFromTemplate([
      {
        label: 'Open core-ai-desktop',
        click: () => {
          if (!mainWindow) createWindow();
          mainWindow?.show();
          mainWindow?.focus();
        },
      },
      {
        label: 'Scheduled tasks',
        click: () => {
          if (!mainWindow) createWindow();
          mainWindow?.show();
          mainWindow?.focus();
          void mainWindow?.webContents.executeJavaScript("window.dispatchEvent(new CustomEvent('coreai-nav-go', { detail: 'schedules' }))");
        },
      },
      { type: 'separator' },
      { label: 'Quit', click: () => app.quit() },
    ]));
  }
  if (!scheduleWaker && supervisor) {
    scheduleWaker = new ScheduleWaker({
      knownWorkspaces: () => [...(settings.workspaces ?? []), defaultWorkspacePath()],
      schedulesFile: path.join(os.homedir(), '.core-ai', 'schedules.json'),
      sessionFileExists: (workspace, sessionId) => existsSync(path.join(sessionsDirOf(workspace), `${sessionId}.data`)),
      isRunning: (workspace) => supervisor?.isRunning(workspace) ?? false,
      ensureRunning: (workspace) => supervisor?.ensureRunning(workspace, settings.approvalPolicy ?? 'ask') ?? Promise.resolve(),
      onError: (message) => console.error(`[schedule-waker] ${message}`),
    });
    scheduleWaker.start();
  }
}
function registerIpc(): void {
  ipcMain.handle('app:info', () => ({
    enginePath: resolveEnginePath(), engineExists: existsSync(resolveEnginePath()), desktopVersion: clientVersion,
    engineVersion: readEngineManifest(resolveEnginePath())?.version ?? null,
    electron: process.versions.electron, node: process.versions.node, userData: app.getPath('userData'), homedir: os.homedir(),
    defaultWorkspacePath: defaultWorkspacePath(), defaultExists: existsSync(defaultWorkspacePath()), startupWorkspace: resolveStartupWorkspace(),
    schedulesFile: path.join(os.homedir(), '.core-ai', 'schedules.json'),
  }));
  ipcMain.handle('settings:get', () => settings);
  ipcMain.handle('settings:set', async (_event, patch: DesktopSettings) => {
    const previousPolicy = settings.approvalPolicy ?? 'ask';
    settings = { ...settings, ...patch };
    if (typeof patch.backgroundScheduling === 'boolean') configureBackgroundScheduling();
    saveSettings();
    applyColorScheme();
    let skillsChanged = false;
    if (typeof patch.browserSkillEnabled === 'boolean') {
      const result = patch.browserSkillEnabled ? builtinSkills?.install('browser-use') : builtinSkills?.remove('browser-use');
      if (result && !result.ok) console.error(`[skills] browser-use: ${result.detail}`);
      skillsChanged = true;
    }
    if (typeof patch.channelsSkillEnabled === 'boolean') {
      const result = patch.channelsSkillEnabled ? builtinSkills?.install('ocg') : builtinSkills?.remove('ocg');
      if (result && !result.ok) console.error(`[skills] ocg: ${result.detail}`);
      skillsChanged = true;
    }
    if (skillsChanged) await supervisor?.shutdownAll();
    const nextPolicy = settings.approvalPolicy ?? 'ask';
    if (settings.lastWorkspace && nextPolicy !== previousPolicy) await supervisor?.restart(settings.lastWorkspace);
    return settings;
  });
  ipcMain.handle('workspace:choose', async () => {
    const result = await dialog.showOpenDialog({ properties: ['openDirectory'] });
    if (result.canceled || !result.filePaths.length) return null;
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
    if (!entries.some((entry) => entry.server_url === normalized)) throw new Error(`environment is not signed in: ${normalized}`);
    writeAuthEntries(withActive(entries, normalized));
    await supervisor?.shutdownAll();
    return authStatus();
  });
  ipcMain.handle('auth:login', async (_event, serverUrl: string, insecure: boolean) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized) throw new Error('invalid server url');
    return loginViaBrowser(normalized, insecure === true);
  });
  ipcMain.handle('auth:loginWithKey', async (_event, serverUrl: string, apiKey: string, insecure: boolean) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized || !apiKey?.trim()) throw new Error('invalid server url or empty api key');
    return loginWithApiKey(normalized, apiKey.trim(), insecure === true);
  });
  ipcMain.handle('auth:setInsecure', (_event, serverUrl: string, insecure: boolean) => {
    const normalized = normalizeServerUrl(serverUrl ?? '');
    if (!normalized) throw new Error('invalid server url');
    return setAuthInsecure(normalized, insecure === true);
  });
  ipcMain.handle('auth:logout', async () => {
    const active = activeAuthEntry(loadAuthEntries());
    if (!active) return authStatus();
    const confirm = await dialog.showMessageBox({
      type: 'question', buttons: ['Sign out', 'Cancel'], defaultId: 1, cancelId: 1,
      message: `Sign out of ${active.server_url}?`, detail: 'The saved credential will be deleted. The CLI and this app share the same sign-in.',
    });
    if (confirm.response !== 0) return authStatus();
    removeAuthEntries(active.server_url);
    await supervisor?.shutdownAll();
    return authStatus();
  });
  ipcMain.handle('engine:capabilities', async (_event, workspace: string) => {
    const target = String(workspace ?? '');
    if (!target || !supervisor) return [];
    try {
      if (!supervisor.isRunning(target)) await supervisor.ensureRunning(target, settings.approvalPolicy ?? 'ask');
      return supervisor.capabilities(target);
    } catch (error) {
      console.error(`[engine] capabilities probe failed: ${error instanceof Error ? error.message : String(error)}`);
      return [];
    }
  });
  ipcMain.handle('engine:restart', async (_event, workspace: string) => {
    if (workspace) await supervisor?.restart(workspace);
  });
  ipcMain.handle('attachments:pick', async () => {
    const preset = process.env.COREAI_TEST_PICK_FILES;
    if (preset) return { paths: preset.split(';').map((entry) => entry.trim()).filter(Boolean) };
    const options = { title: 'Attach files', properties: ['openFile', 'multiSelections'] as Array<'openFile' | 'multiSelections'>, filters: ATTACHMENT_FILTERS };
    const result = mainWindow ? await dialog.showOpenDialog(mainWindow, options) : await dialog.showOpenDialog(options);
    return { paths: result.canceled ? [] : result.filePaths };
  });
  ipcMain.handle('attachments:stage', async (_event, sessionId: string, paths: string[]) => {
    const id = String(sessionId ?? '').trim();
    if (!id) return { attachments: [], skipped: [{ name: '', reason: 'no session is open yet' }] };
    const files = Array.isArray(paths) ? paths.filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0) : [];
    const result = await stageAttachments(stagingRoot(), id, files);
    await attachPreviews(result.attachments);
    return result;
  });
  ipcMain.handle('attachments:stageBuffers', async (_event, sessionId: string, entries: StagedBufferInput[]) => {
    const id = String(sessionId ?? '').trim();
    if (!id) return { attachments: [], skipped: [{ name: '', reason: 'no session is open yet' }] };
    const inputs = Array.isArray(entries) ? entries : [];
    const result = await stageBuffers(stagingRoot(), id, inputs);
    await attachPreviews(result.attachments);
    return result;
  });
  ipcMain.handle('attachments:remove', async (_event, stagedPath: string) => removeStaged(stagingRoot(), String(stagedPath ?? '')));
  ipcMain.handle('browser:start', async () => browserManager?.start() ?? null);
  ipcMain.handle('browser:stop', async () => browserManager?.stop() ?? null);
  ipcMain.handle('browser:status', () => browserManager?.status() ?? null);
  ipcMain.handle('browser:setBounds', (_event, bounds: unknown) => browserManager?.setBounds(isBounds(bounds) ? bounds : null));
  ipcMain.handle('browser:navigate', (_event, url: string) => browserManager?.navigate(String(url ?? '')) ?? null);
  ipcMain.handle('browser:back', () => browserManager?.goBack() ?? null);
  ipcMain.handle('browser:forward', () => browserManager?.goForward() ?? null);
  ipcMain.handle('browser:reload', () => browserManager?.reload() ?? null);
  ipcMain.handle('browser:stopLoading', () => browserManager?.stopLoading() ?? null);
  ipcMain.handle('browser:newTab', (_event, url?: string) => browserManager?.newTab(url) ?? null);
  ipcMain.handle('browser:closeTab', (_event, id: string) => browserManager?.closeTab(String(id ?? '')) ?? null);
  ipcMain.handle('browser:selectTab', (_event, id: string) => browserManager?.selectTab(String(id ?? '')) ?? null);
  ipcMain.handle('computer:status', () => cuaManager?.status(settings.computerUse === true) ?? null);
  ipcMain.handle('computer:installCli', () => cuaManager?.installCli() ?? { ok: false, detail: 'computer use manager unavailable' });
  ipcMain.handle('computer:enable', async () => {
    if (!cuaManager) return { ok: false, steps: [] };
    const result = await cuaManager.enable();
    if (result.ok) {
      settings.computerUse = true;
      saveSettings();
      const skill = builtinSkills?.install('gui-automation');
      if (skill && !skill.ok) console.error(`[skills] gui-automation: ${skill.detail}`);
      await supervisor?.shutdownAll();
    }
    return result;
  });
  ipcMain.handle('computer:disable', async () => {
    settings.computerUse = false;
    saveSettings();
    const skill = builtinSkills?.remove('gui-automation');
    if (skill && !skill.ok) console.error(`[skills] gui-automation: ${skill.detail}`);
    await supervisor?.shutdownAll();
    return cuaManager?.status(false) ?? null;
  });
  ipcMain.handle('computer:removeService', async () => {
    if (!cuaManager) return { ok: false, detail: 'computer use manager unavailable' };
    const result = await cuaManager.removeService();
    if (result.ok) {
      settings.computerUse = false;
      saveSettings();
      const skill = builtinSkills?.remove('gui-automation');
      if (skill && !skill.ok) console.error(`[skills] gui-automation: ${skill.detail}`);
      await supervisor?.shutdownAll();
    }
    return result;
  });
  ipcMain.handle('builtinSkills:list', () => ({
    error: builtinSkills?.error ?? null,
    skills: builtinSkills?.list() ?? [],
  }));
  ipcMain.handle('builtinSkills:install', async (_event, name: string, force?: boolean) => {
    if (!builtinSkills) return { ok: false, changed: false, files: 0, detail: 'built-in skills manager unavailable' };
    const skillName = String(name ?? '');
    const result = force ? builtinSkills.restore(skillName) : builtinSkills.install(skillName);
    if (result.ok && skillName === 'browser-use') {
      settings.browserSkillEnabled = true;
      saveSettings();
    } else if (result.ok && skillName === 'ocg') {
      settings.channelsSkillEnabled = true;
      saveSettings();
    }
    if (result.changed) await supervisor?.shutdownAll();
    return result;
  });
  ipcMain.handle('builtinSkills:remove', async (_event, name: string) => {
    if (!builtinSkills) return { ok: false, changed: false, files: 0, detail: 'built-in skills manager unavailable' };
    const skillName = String(name ?? '');
    const result = builtinSkills.remove(skillName);
    if (result.ok && skillName === 'browser-use') {
      settings.browserSkillEnabled = false;
      saveSettings();
    } else if (result.ok && skillName === 'ocg') {
      settings.channelsSkillEnabled = false;
      saveSettings();
    }
    if (result.changed) await supervisor?.shutdownAll();
    return result;
  });
  ipcMain.handle('browser:toolStatus', () => browserUseStatus());
  ipcMain.handle('browser:installTool', async () => {
    const result = await installBrowserUse();
    if (result.ok) {
      const skill = builtinSkills?.install('browser-use');
      if (skill && !skill.ok) console.error(`[skills] browser-use: ${skill.detail}`);
      await supervisor?.shutdownAll();
    }
    return { ...result, status: await browserUseStatus() };
  });
  ipcMain.handle('channels:status', () => ocgStatus());
  ipcMain.handle('channels:setEnabled', async (_event, enabled: boolean) => {
    const value = enabled === true;
    settings.channelsSkillEnabled = value;
    saveSettings();
    const result = value ? builtinSkills?.install('ocg') : builtinSkills?.remove('ocg');
    if (result && !result.ok) console.error(`[skills] ocg: ${result.detail}`);
    await supervisor?.shutdownAll();
    return { enabled: value, status: await ocgStatus(), skill: result ?? null };
  });
  ipcMain.handle('channels:installTool', async () => {
    const result = await installOcg();
    if (result.ok) {
      const skill = builtinSkills?.install('ocg');
      if (skill && !skill.ok) console.error(`[skills] ocg: ${skill.detail}`);
      await supervisor?.shutdownAll();
    }
    return { ...result, status: await ocgStatus() };
  });
  ipcMain.handle('channels:adminOverview', async () => {
    ocgAdmin ??= new OcgHttpPort();
    const health = await ocgAdmin.health();
    if (!health.ok || (health.apiVersion ?? 0) < 1) {
      return { ok: false, error: health.error ?? 'OCG daemon does not advertise /ocg/v1' };
    }
    const result = await ocgAdmin.channels();
    return result.ok ? { ok: true, channels: result.data?.channels ?? [] } : result;
  });
  ipcMain.handle('channels:adminOp', async (_event, channelId: string, op: string, accountId?: string) => {
    ocgAdmin ??= new OcgHttpPort();
    if (op === 'start') return ocgAdmin.startChannel(channelId, accountId);
    if (op === 'stop') return ocgAdmin.stopChannel(channelId, accountId);
    if (op === 'restart') return ocgAdmin.restartChannel(channelId, accountId);
    return { ok: false, error: `unknown channel operation: ${op}` };
  });
  ipcMain.handle('channels:loginStart', async (_event, channelId: string, accountId?: string) => {
    ocgAdmin ??= new OcgHttpPort();
    const result = await ocgAdmin.startLogin(channelId, accountId ?? 'default');
    if (!result.ok || !result.data) return result;
    let qrImage: string | undefined;
    try {
      qrImage = await QRCode.toDataURL(result.data.qrDataUrl, { width: 280, margin: 1 });
    } catch { /* the caller can still show the raw QR payload */ }
    return { ok: true, ...result.data, ...(qrImage ? { qrImage } : {}) };
  });
  ipcMain.handle('channels:loginStatus', async (_event, channelId: string, loginId: string) => {
    ocgAdmin ??= new OcgHttpPort();
    const result = await ocgAdmin.loginStatus(channelId, loginId);
    return result.ok ? { ok: true, ...result.data } : result;
  });
  ipcMain.handle('channels:loginCancel', async (_event, channelId: string, loginId: string) => {
    ocgAdmin ??= new OcgHttpPort();
    const result = await ocgAdmin.cancelLogin(channelId, loginId);
    return result.ok ? { ok: true, ...result.data } : result;
  });
  ipcMain.handle('channels:bridgeStatus', (): ChannelBridgeState | null => channelBridge?.snapshot() ?? null);
  ipcMain.handle('channels:bridgeSetEnabled', (_event, enabled: boolean): ChannelBridgeState | null => {
    const value = enabled === true;
    settings.channelsBridgeEnabled = value;
    saveSettings();
    if (value) {
      channelBridge?.start();
      void ensureOcgDaemon();
    } else {
      channelBridge?.stop();
    }
    return channelBridge?.snapshot() ?? null;
  });
  ipcMain.handle('update:status', () => updateService?.snapshot() ?? null);
  ipcMain.handle('update:check', async () => updateService?.check() ?? null);
  ipcMain.handle('update:download', async () => updateService?.download() ?? null);
  ipcMain.handle('update:install', async () => updateService?.install() ?? null);
  ipcMain.handle('engine:call', async (_event, workspace: string, method: string, params: Record<string, unknown>): Promise<CallEnvelope> => {
    if (!supervisor) return { ok: false, error: { code: 0, message: 'engine supervisor not ready' } };
    try {
      const result = await supervisor.call(workspace, method, params ?? {}, settings.approvalPolicy ?? 'ask');
      return { ok: true, result };
    } catch (error) {
      if (error instanceof RpcCallError) return { ok: false, error: { code: error.code, message: error.message, data: error.data } };
      return { ok: false, error: { code: 0, message: error instanceof Error ? error.message : String(error) } };
    }
  });
  ipcMain.handle('artifacts:base', (_event, workspace: string) => artifactBase(String(workspace ?? '')));
  // The renderer keeps live artifact entries the engine's scan does not cover yet; this tells it which
  // of those still exist, so a file deleted from the workspace drops out instead of leaving a broken preview.
  ipcMain.handle('artifacts:existing', (_event, workspace: string, relativePaths: string[]) => {
    if (typeof workspace !== 'string' || !workspace || !Array.isArray(relativePaths)) return [];
    return relativePaths.filter((relativePath) => {
      if (typeof relativePath !== 'string' || !relativePath) return false;
      const absolute = resolveInsideWorkspace(workspace, relativePath);
      if (!absolute) return false;
      try {
        return statSync(absolute).isFile();
      } catch {
        return false;
      }
    });
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
  // The artifact panel's Copy action: the OS path is what the user pastes into a terminal or another app.
  ipcMain.handle('shell:copyPath', (_event, workspace: string, relativePath: string) => {
    const absolute = resolveInsideWorkspace(workspace, relativePath);
    if (!absolute) return null;
    try {
      if (!statSync(absolute).isFile()) return null;
    } catch {
      return null;
    }
    clipboard.writeText(absolute);
    return absolute;
  });
  ipcMain.handle('shell:openExternal', async (_event, url: string) => {
    if (typeof url === 'string' && /^https?:\/\//i.test(url)) await shell.openExternal(url);
  });
}
function resolveInsideWorkspace(workspace: string, relativePath: string): string | null {
  const root = path.resolve(workspace);
  const absolute = path.resolve(root, relativePath);
  const relative = path.relative(root, absolute);
  return relative && !relative.startsWith('..') && !path.isAbsolute(relative) ? absolute : null;
}

interface BoundsPayload { x: number; y: number; width: number; height: number }

function isBounds(value: unknown): value is BoundsPayload {
  if (!value || typeof value !== 'object') return false;
  const bounds = value as Record<string, unknown>;
  return ['x', 'y', 'width', 'height'].every((key) => typeof bounds[key] === 'number' && Number.isFinite(bounds[key]));
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();
else {
  app.on('second-instance', () => {
    if (!mainWindow) createWindow();
    if (mainWindow?.isMinimized()) mainWindow.restore();
    mainWindow?.show();
    mainWindow?.focus();
  });
  void app.whenReady().then(() => {
    settings = loadSettings();
    applyColorScheme();
    nativeTheme.on('updated', () => mainWindow?.setBackgroundColor(windowBackground()));
    browserManager = new BrowserManager({
      onState: (status) => send('browser:state', status),
    });
    cuaManager = new CuaManager({
      skillSourceDir: resolveSkillSourceDir(),
      desktopVersion: clientVersion,
      installSkill: () => builtinSkills?.install('gui-automation')
        ?? { ok: false, detail: 'built-in skills manager unavailable' },
      skillStatus: () => {
        const status = builtinSkills?.status('gui-automation');
        if (!status) {
          return {
            installed: false, managed: false, upToDate: false, detail: 'not installed', files: 0,
            targetDir: path.join(os.homedir(), '.core-ai', 'skills', 'gui-automation'),
            sourceDir: resolveSkillSourceDir(),
          };
        }
        return {
          installed: status.installed || Boolean(status.coveredBy),
          managed: status.managed,
          upToDate: status.upToDate,
          detail: status.coveredBy ? `provided by ${status.coveredBy}` : status.detail,
          files: 0,
          targetDir: status.targetDir,
          sourceDir: status.sourceDir,
        };
      },
    });
    builtinSkills = new BuiltinSkills({
      skillsRoot: resolveSkillsRoot(),
      desktopVersion: clientVersion,
      onLog: (message) => console.log(`[skills] ${message}`),
    });
    installConfiguredBuiltins();
    updateService = new UpdateService({
      currentVersion: clientVersion,
      isPackaged: app.isPackaged,
      downloadDir: path.join(app.getPath('temp'), 'core-ai-desktop-updates'),
      onState: (state) => send('update:state', state),
      install: (installerPath) => runDesktopInstaller(installerPath),
      openFile: async (filePath) => {
        await shell.openPath(filePath);
      },
    });
    // Startup update check (design §4.7): a newer release badges the Settings row in the sidebar
    // without the user opening About. Delayed so window paint and engine start-up go first, and
    // skipped when the About panel already triggered a check.
    setTimeout(() => {
      if (updateService?.snapshot().phase === 'idle') void updateService.check();
    }, UPDATE_CHECK_DELAY_MS);
    // A desktop update also brings a new bundled engine; refresh the standalone CLI after the
    // window and engine start-up have had the disk to themselves.
    setTimeout(() => {
      void syncBundledCli().catch((error: Error) => console.error(`[cli] sync failed: ${error.message}`));
    }, CLI_SYNC_DELAY_MS);
    supervisor = new EngineSupervisor({
      enginePath: resolveEnginePath(),
      clientVersion,
      extraEnv: () => {
        const enginePath = resolveEnginePath();
        let env: Record<string, string> = {
          ...(browserManager?.engineEnv() ?? {}),
          ...(cuaManager?.engineEnv(settings.computerUse === true) ?? {}),
          CORE_AI_CLI_BIN: enginePath,
          CORE_AI_BROWSER_TOOL: resolveExecutable('browser-use') ? 'ready' : 'missing',
        };
        env = withExecutableOnPath(env, path.dirname(enginePath));
        // browser-use/ocg may live in ~/.local/bin or the npm global bin, which the app process
        // did not necessarily inherit; the agent's shell needs the same directories on PATH.
        for (const tool of ['browser-use', 'ocg']) {
          const file = resolveExecutable(tool);
          if (file) env = withExecutableOnPath(env, path.dirname(file));
        }
        return env;
      },
      onEvent: (workspace, method, params) => {
        channelBridge?.onEngineEvent(workspace, method, params);
        send('engine:event', { workspace, method, params });
      },
      onLog: (workspace, line) => {
        console.error(`[engine ${workspace}] ${line}`);
        send('engine:log', { workspace, line });
      },
    });
    channelBridge = new ChannelBridge({
      workspace: (message) => resolveBridgeWorkspace(message),
      callEngine: (workspace, method, params) => {
        if (!supervisor) return Promise.reject(new Error('engine supervisor not ready'));
        return supervisor.call(workspace, method, params, settings.approvalPolicy ?? 'ask');
      },
      ocg: createOcgPort({ wait: ocgWait, reply: ocgReply, ack: ocgAck, send: ocgSend }),
      storeFile: path.join(app.getPath('userData'), 'channels', 'bridge.json'),
      consumer: 'core-ai-desktop',
      onState: (state) => send('channels:state', state),
      onSession: (event) => send('channels:session', event),
      log: (line) => console.log(`[channels] ${line}`),
    });
    if (settings.channelsBridgeEnabled) {
      channelBridge.start();
      void ensureOcgDaemon();
    }
    registerIpc();
    registerArtifactProtocol();
    configureBackgroundScheduling();
    void sweepStaging(stagingRoot(), 7 * 24 * 60 * 60 * 1000)
      .then((removed) => {
        if (removed > 0) console.log(`[attachments] swept ${removed} stale staging dir(s)`);
      })
      .catch(() => undefined);
    createWindow();
    browserManager.attachWindow(mainWindow);
    if (process.env.COREAI_BROWSER_AUTOSTART === '1') {
      void browserManager.start().catch((error: Error) => {
        console.error(`[browser] autostart failed: ${error.message}`);
      });
    }
  });
  app.on('window-all-closed', () => {
    if (settings.backgroundScheduling && tray) return;
    app.quit();
  });
  app.on('before-quit', (event) => {
    if (quitting || !supervisor) return;
    event.preventDefault();
    quitting = true;
    void browserManager?.stop();
    scheduleWaker?.stop();
    channelBridge?.stop();
    const failsafe = setTimeout(() => app.exit(0), 8_000);
    void supervisor.shutdownAll().finally(() => {
      clearTimeout(failsafe);
      app.exit(0);
    });
  });
}
