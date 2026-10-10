// Desktop renderer: session-first UI over the local app-server engine. All engine access goes
// through window.coreai (IPC -> main -> engine); this file only renders and routes events.
//
// Layout note: the session list lives in the sidebar under the "Sessions" entry (10 per page,
// collapsible), so the content area stays free for the conversation and the upcoming side-by-side
// browser panel.
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import { marked } from 'marked';

marked.setOptions({ gfm: true, breaks: true });

/** Assistant output is markdown; sanitize before it touches innerHTML. */
function sanitizeMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string);
}

/** Renders sanitized markdown and syntax-highlights fenced code blocks (CLI palette). */
function renderMarkdownInto(body: HTMLElement, text: string): void {
  body.innerHTML = sanitizeMarkdown(text);
  for (const block of body.querySelectorAll('pre code')) {
    const match = /language-([\w-]+)/.exec(block.className);
    if (match && hljs.getLanguage(match[1])) {
      hljs.highlightElement(block as HTMLElement);
    }
  }
}

interface Envelope {
  ok: boolean;
  result?: any;
  error?: { code: number; message: string; data?: Record<string, unknown> };
}

interface WorkspaceSummary {
  path: string;
  count: number;
  latestSessionId: string | null;
  latestTitle: string | null;
  latestUpdatedAt: number;
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

interface BrowserTabInfo {
  id: string;
  title: string;
  url: string;
  active: boolean;
}

interface BrowserStatus {
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

interface BrowserBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

interface CoreAiBridge {
  call(workspace: string, method: string, params: Record<string, unknown>): Promise<Envelope>;
  chooseWorkspace(): Promise<string | null>;
  defaultWorkspace(): Promise<string>;
  workspaceSummaries(): Promise<WorkspaceSummary[]>;
  authStatus(): Promise<AuthStatus>;
  authServers(): Promise<AuthServerEntry[]>;
  authSwitch(serverUrl: string): Promise<AuthStatus>;
  authLogin(serverUrl: string): Promise<AuthStatus>;
  authLoginWithKey(serverUrl: string, apiKey: string): Promise<AuthStatus>;
  authLogout(): Promise<AuthStatus>;
  restartEngine(workspace: string): Promise<void>;
  browserStart(): Promise<BrowserStatus | null>;
  browserStop(): Promise<BrowserStatus | null>;
  browserStatus(): Promise<BrowserStatus | null>;
  browserSetBounds(bounds: BrowserBounds | null): Promise<void>;
  browserNavigate(url: string): Promise<BrowserStatus | null>;
  browserBack(): Promise<BrowserStatus | null>;
  browserForward(): Promise<BrowserStatus | null>;
  browserReload(): Promise<BrowserStatus | null>;
  browserStopLoading(): Promise<BrowserStatus | null>;
  browserNewTab(url?: string): Promise<BrowserStatus | null>;
  browserCloseTab(id: string): Promise<BrowserStatus | null>;
  browserSelectTab(id: string): Promise<BrowserStatus | null>;
  onBrowserState(callback: (status: BrowserStatus) => void): () => void;
  onBrowserNeedPanel(callback: () => void): () => void;
  info(): Promise<any>;
  getSettings(): Promise<any>;
  setSettings(patch: Record<string, unknown>): Promise<any>;
  openPath(workspace: string, relativePath: string): Promise<string | null>;
  revealPath(workspace: string, relativePath: string): Promise<void>;
  openExternal(url: string): Promise<void>;
  onEvent(callback: (payload: any) => void): () => void;
  onLog(callback: (payload: any) => void): () => void;
}

declare global {
  interface Window {
    coreai: CoreAiBridge;
  }
}

type NavKey = 'sessions' | 'artifacts' | 'skills' | 'memory' | 'settings';

interface SessionRow { sessionId: string; title?: string; updatedAt?: string; status?: string }
interface UiTool {
  callId: string;
  name: string;
  status: string;
  node?: HTMLElement;
  statusNode?: HTMLElement;
  resultNode?: HTMLElement;
  durationNode?: HTMLElement;
}

const SECTION_PAGE_SIZE = 10;
const COLLAPSE_KEY = 'coreai.sessionsCollapsed';

const NAV: Array<{ key: NavKey; label: string; icon: keyof typeof ICONS }> = [
  { key: 'sessions', label: 'Sessions', icon: 'sessions' },
  { key: 'artifacts', label: 'Artifacts', icon: 'artifacts' },
  { key: 'skills', label: 'Skills', icon: 'skills' },
  { key: 'memory', label: 'Memory', icon: 'memory' },
  { key: 'settings', label: 'Settings', icon: 'settings' },
];

const state: {
  info: any;
  approvalPolicy: string;
  workspace: string | null;
  workspaces: string[];
  summaries: WorkspaceSummary[];
  auth: AuthStatus | null;
  defaultExists: boolean;
  nav: NavKey;
  sessions: SessionRow[];
  sessionCursor: string | null;
  hasMoreSessions: boolean;
  sessionsCollapsed: boolean;
  sessionId: string | null;
  sessionTitle: string;
  toolMap: Map<string, UiTool>;
  pendingApproval: { callId: string; toolName: string } | null;
  status: string;
  models: Array<{ model: string; provider?: string; displayName?: string; reasoningEfforts?: string[] }>;
  modelSource: string | null;
  currentModel: string | null;
  thinkingLevel: string;
  pendingEngineRestart: boolean;
  browserStatus: BrowserStatus | null;
  browserPanelOpen: boolean;
  browserStarting: boolean;
  sidebarCollapsed: boolean;
  sidebarAutoCollapsed: boolean;
  colorScheme: string;
} = {
  info: null,
  approvalPolicy: 'ask',
  workspace: null,
  workspaces: [],
  summaries: [],
  auth: null,
  defaultExists: false,
  nav: 'sessions',
  sessions: [],
  sessionCursor: null,
  hasMoreSessions: false,
  sessionsCollapsed: localStorage.getItem(COLLAPSE_KEY) === '1',
  sessionId: null,
  sessionTitle: '',
  toolMap: new Map(),
  pendingApproval: null,
  status: 'idle',
  models: [],
  modelSource: null,
  currentModel: null,
  thinkingLevel: 'off',
  pendingEngineRestart: false,
  browserStatus: null,
  browserPanelOpen: false,
  browserStarting: false,
  sidebarCollapsed: false,
  sidebarAutoCollapsed: false,
  colorScheme: 'system',
};

let chatView: HTMLElement | null = null;
let sessionItemsEl: HTMLElement | null = null;
let transcriptEl: HTMLElement | null = null;
let composerInput: HTMLTextAreaElement | null = null;
let composerControlsEl: HTMLElement | null = null;
let composerAutoGrow: (() => void) | null = null;
let chatTitleEl: HTMLElement | null = null;
let statusChip: HTMLElement | null = null;
let stopButton: HTMLButtonElement | null = null;
let browserButton: HTMLButtonElement | null = null;
let browserPanelEl: HTMLElement | null = null;
let browserBodyEl: HTMLElement | null = null;
let browserAddressEl: HTMLInputElement | null = null;
let browserTabsEl: HTMLElement | null = null;
let browserDotEl: HTMLElement | null = null;
let browserHintEl: HTMLElement | null = null;
let browserBackEl: HTMLButtonElement | null = null;
let browserForwardEl: HTMLButtonElement | null = null;
let browserReloadEl: HTMLButtonElement | null = null;
let browserOpenExternalEl: HTMLButtonElement | null = null;
let browserBoundsTimer: number | null = null;
let browserBoundsValue: string | null = null;
let browserResizeObserver: ResizeObserver | null = null;
let sidebarToggleEl: HTMLButtonElement | null = null;
let browserPanelWasOpen = false;
let approvalBar: HTMLElement | null = null;
interface AssistToggle {
  chip: HTMLButtonElement;
  panel: HTMLElement;
  setLabel(label: string): void;
}

interface MemoryRun {
  runId: string;
  trigger: string;
  running: boolean;
  durationMs: number | null;
  cursor: number | null;
  added: string[];
  updated: string[];
  note: string | null;
}

interface MemoryUi {
  toggle: AssistToggle;
  list: HTMLElement;
  runs: MemoryRun[];
}

/** Memory chips live per message: extraction completes long after the turn that triggered it ended. */
const memoryUi = new WeakMap<HTMLElement, MemoryUi>();
let pendingMemoryRun: { wrapper: HTMLElement; ui: MemoryUi; run: MemoryRun } | null = null;

let streaming: {
  text: string;
  bubble: HTMLElement;
  body: HTMLElement;
  reasoning: string;
  reasoningPre: HTMLElement | null;
  reasoningToggle: AssistToggle | null;
  reasoningTick: ReturnType<typeof setInterval> | null;
  reasoningLiveStart: number | null;
  chips: HTMLElement | null;
  panels: HTMLElement | null;
  toolsPanel: HTMLElement | null;
  toolsToggle: AssistToggle | null;
  skillsToggle: AssistToggle | null;
  skillsPanel: HTMLElement | null;
  skillUses: Map<string, { node: HTMLElement; count: number }> | null;
} | null = null;

/** Live-only ticking while the model reasons; the final label is frozen from the engine duration. */
function startReasoningTick(current: NonNullable<typeof streaming>): void {
  if (current.reasoningTick != null) return;
  current.reasoningToggle?.chip.classList.add('busy');
  current.reasoningTick = setInterval(() => {
    if (current.reasoningLiveStart == null || !current.reasoningToggle) return;
    current.reasoningToggle.chip.classList.add('busy');
    const seconds = Math.max(1, Math.round((Date.now() - current.reasoningLiveStart) / 1000));
    current.reasoningToggle.setLabel(`Thinking · ${seconds}s`);
  }, 1000);
}

function stopReasoningTick(current: NonNullable<typeof streaming>): void {
  if (current.reasoningTick != null) {
    clearInterval(current.reasoningTick);
    current.reasoningTick = null;
  }
  current.reasoningToggle?.chip.classList.remove('busy');
  current.reasoningLiveStart = null;
}

function resetStreaming(): void {
  if (streaming) stopReasoningTick(streaming);
  stopTurnClock();
  streaming = null;
}

interface TurnClock {
  startedAt: number;
  tick: ReturnType<typeof setInterval> | null;
  chip: HTMLElement | null;
}

/** One clock per turn: started when the message is sent, so waits before the first event count too. */
let turnClock: TurnClock | null = null;

function activeTurnClock(): TurnClock {
  if (!turnClock) turnClock = { startedAt: Date.now(), tick: null, chip: null };
  return turnClock;
}

function startTurnTick(chip: HTMLElement, clock: TurnClock): void {
  if (clock.tick != null) return;
  const render = () => {
    chip.textContent = `Running · ${formatElapsed(Date.now() - clock.startedAt)}`;
  };
  render();
  clock.tick = setInterval(render, 1000);
}

/** Freezes the chip on the same total the turn summary reports. */
function finishTurnStatus(event: any): void {
  const clock = turnClock;
  stopTurnClock();
  if (!clock?.chip) return;
  const elapsed = typeof event?.duration_ms === 'number' ? event.duration_ms : Date.now() - clock.startedAt;
  const cancelled = Boolean(event?.cancelled);
  clock.chip.textContent = `${cancelled ? 'Cancelled' : 'Done'} · ${formatElapsed(elapsed)}`;
  clock.chip.classList.remove('busy');
  clock.chip.classList.add(cancelled ? 'cancelled' : 'done');
}

function stopTurnClock(): void {
  if (turnClock?.tick != null) clearInterval(turnClock.tick);
  turnClock = null;
}

// ---------- small helpers ----------

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

/** Inline SVG set (feather-style, 24px grid, stroke = currentColor) for the nav and sidebar chrome. */
const ICONS = {
  sessions: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  artifacts: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/>',
  skills: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
  memory: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  'chevron-left': '<polyline points="15 18 9 12 15 6"/>',
  'chevron-right': '<polyline points="9 18 15 12 9 6"/>',
  'chevron-down': '<polyline points="6 9 12 15 18 9"/>',
  'arrow-left': '<line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>',
  'arrow-right': '<line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/>',
  refresh: '<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>',
  'arrow-up-right': '<line x1="7" y1="17" x2="17" y2="7"/><polyline points="7 7 17 7 17 17"/>',
  x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
  cpu: '<rect x="4" y="4" width="16" height="16" rx="2" ry="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/>',
  lightbulb: '<circle cx="12" cy="9.5" r="5"/><path d="M9.8 15h4.4"/><path d="M10.3 18h3.4"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
};

function icon(name: keyof typeof ICONS): SVGElement {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('ic');
  svg.innerHTML = ICONS[name];
  return svg;
}

function fmtBytes(size: number): string {
  if (!size && size !== 0) return '';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function fmtTime(iso?: string): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString();
}

function prettyJson(raw: unknown): string {
  if (typeof raw !== 'string') return JSON.stringify(raw, null, 2);
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function displayPath(path: string): string {
  const home: string | undefined = state.info?.homedir;
  return home && path.startsWith(home) ? `~${path.slice(home.length)}` : path;
}

function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function isDatedDefaultWorkspace(workspace: string | null): boolean {
  return Boolean(workspace && /[\\/]workspaces[\\/]\d{4}-\d{2}-\d{2}$/.test(workspace));
}

/** The dated default workspace reads as plain "default"; older ones keep their date. */
function workspaceLabel(workspace: string | null): string {
  if (!workspace) return 'default';
  if (isDatedDefaultWorkspace(workspace)) {
    const date = workspace.replace(/.*[\\/]/, '');
    return date === todayIso() ? 'default' : `default (${date})`;
  }
  return displayPath(workspace);
}

// ---------- engine access ----------

async function call(method: string, params: Record<string, unknown> = {}): Promise<any | null> {
  if (!state.workspace) {
    toast('The default workspace is not created yet — it appears with your first message, or pick a folder below');
    return null;
  }
  const envelope = await window.coreai.call(state.workspace, method, params);
  if (envelope.ok) return envelope.result ?? {};
  const message = envelope.error?.message ?? 'engine call failed';
  toast(`✗ ${method}: ${message}`, 'error');
  return null;
}

function toast(text: string, kind = ''): void {
  const box = document.getElementById('toast') ?? (() => {
    const node = el('div', 'toast');
    node.id = 'toast';
    document.body.append(node);
    return node;
  })();
  box.textContent = text;
  box.className = `toast ${kind} show`;
  window.setTimeout(() => box.classList.remove('show'), 2600);
}

// ---------- shell ----------

function renderSidebar(): void {
  const nav = document.getElementById('nav') as HTMLElement;
  nav.replaceChildren();
  sessionItemsEl = null;

  for (const item of NAV) {
    if (item.key === 'sessions') {
      nav.append(buildSessionsNavRow());
      if (!state.sessionsCollapsed) {
        const sub = el('div', 'session-sub');
        sessionItemsEl = sub;
        nav.append(sub);
        renderSessionItems();
      }
    } else {
      const button = el('button', `nav-item${state.nav === item.key ? ' active' : ''}`);
      button.append(icon(item.icon), el('span', 'nav-text', item.label));
      button.title = item.label;
      button.onclick = () => {
        state.nav = item.key;
        renderSidebar();
        renderContent();
      };
      nav.append(button);
    }
  }

  ensureSidebarToggle();
  renderSidebarFooter();
}

function renderSidebarFooter(): void {
  const footer = document.getElementById('sidebar-footer') as HTMLElement;
  footer.replaceChildren();
  const workspace = el('div', 'footer-workspace', state.workspace ? workspaceLabel(state.workspace) : 'default');
  workspace.title = state.workspace ?? (state.info?.defaultWorkspacePath ?? '');
  footer.append(workspace);

  const auth = state.auth;
  const loggedIn = Boolean(auth?.loggedIn);
  const display = loggedIn ? (auth?.name ?? auth?.userId ?? '?') : '?';
  const row = el('button', 'profile-row');
  row.append(el('span', loggedIn ? 'avatar' : 'avatar off', display.trim().charAt(0).toUpperCase() || '?'));
  const box = el('span', 'profile-box');
  box.append(el('span', 'profile-name', loggedIn ? (auth?.name ?? auth?.userId ?? '(authenticated)') : 'Not signed in'));
  box.append(el('span', 'profile-server', loggedIn ? hostOf(auth?.serverUrl) : 'Sign in to core-ai-server'));
  row.append(box);
  row.title = loggedIn
    ? `${auth?.userId ?? ''} @ ${auth?.serverUrl ?? ''}`
    : 'Sign in to core-ai-server (shared with the CLI)';
  row.onclick = (event) => {
    event.stopPropagation();
    if (state.auth?.loggedIn) openProfileMenu(row);
    else openLoginDialog();
  };
  footer.append(row);
}

function hostOf(url: string | null | undefined): string {
  if (!url) return '';
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

// ---------- profile & server auth ----------

async function refreshAuth(): Promise<void> {
  let next: AuthStatus | null = null;
  try {
    next = await window.coreai.authStatus();
  } catch {
    next = null;
  }
  if (JSON.stringify(next) !== JSON.stringify(state.auth)) {
    state.auth = next;
    renderSidebar();
  }
}

function applyAuth(next: AuthStatus | null): void {
  state.auth = next;
  renderSidebar();
}

function cleanIpcError(error: unknown): string {
  const message = error instanceof Error ? error.message : String((error as any)?.message ?? error);
  return message.replace(/^Error invoking remote method '[^']*':\s*(Error:\s*)?/, '');
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

let profileMenu: HTMLElement | null = null;
let disposeProfileMenu: (() => void) | null = null;

function closeProfileMenu(): void {
  profileMenu?.remove();
  profileMenu = null;
  disposeProfileMenu?.();
  disposeProfileMenu = null;
}

interface SchemeItem {
  value: string;
  dot: HTMLElement;
  item: HTMLElement;
}

const COLOR_SCHEMES: Array<[string, string]> = [
  ['system', 'System'],
  ['light', 'Light'],
  ['dark', 'Dark'],
];

function normalizeColorScheme(value: unknown): string {
  return value === 'light' || value === 'dark' ? value : 'system';
}

/** The main process pins the palette via nativeTheme; the renderer only records and reflects it. */
async function pickColorScheme(value: string, items: SchemeItem[]): Promise<void> {
  if (value === state.colorScheme) return;
  try {
    await window.coreai.setSettings({ colorScheme: value });
  } catch (error) {
    toast(`Appearance switch failed: ${cleanIpcError(error)}`);
    return;
  }
  state.colorScheme = value;
  for (const entry of items) {
    const active = entry.value === value;
    entry.dot.textContent = active ? '●' : '○';
    entry.item.classList.toggle('active', active);
  }
}

async function openProfileMenu(anchor: HTMLElement): Promise<void> {
  closeProfileMenu();
  const auth = state.auth;
  const menu = el('div', 'profile-menu');
  menu.append(el('div', 'profile-menu-title', auth?.name ?? auth?.userId ?? '(authenticated)'));
  if (auth?.name && auth?.userId) menu.append(el('div', 'profile-menu-sub', auth.userId));
  if (auth?.serverUrl) menu.append(el('div', 'profile-menu-sub', auth.serverUrl));
  if (auth?.loginAt) menu.append(el('div', 'profile-menu-sub', `Signed in ${formatWhen(auth.loginAt)}`));
  menu.append(el('div', 'profile-menu-sep'));

  const servers = await window.coreai.authServers().catch(() => [] as AuthServerEntry[]);
  if (servers.length > 1) {
    menu.append(el('div', 'profile-menu-sub', 'Environments'));
    for (const entry of servers) {
      const item = el('button', `profile-menu-item env${entry.active ? ' active' : ''}`);
      item.append(el('span', undefined, entry.active ? '●' : '○'));
      item.append(el('span', undefined, entry.name && entry.name !== auth?.name ? `${hostOf(entry.serverUrl)} · ${entry.name}` : hostOf(entry.serverUrl)));
      item.title = entry.serverUrl;
      if (!entry.active) {
        item.onclick = () => void switchEnvironment(entry.serverUrl);
      }
      menu.append(item);
    }
    const add = el('button', 'profile-menu-item', '＋ Add environment…');
    add.onclick = () => {
      closeProfileMenu();
      openLoginDialog();
    };
    menu.append(add);
    menu.append(el('div', 'profile-menu-sep'));
  }

  menu.append(el('div', 'profile-menu-sub', 'Appearance'));
  const schemeItems: SchemeItem[] = [];
  for (const [value, label] of COLOR_SCHEMES) {
    const active = state.colorScheme === value;
    const item = el('button', `profile-menu-item choice${active ? ' active' : ''}`);
    const dot = el('span', undefined, active ? '●' : '○');
    item.append(dot, el('span', undefined, label));
    item.title = value === 'system' ? 'Follow the system light/dark setting' : `Always use the ${value} palette`;
    item.onclick = () => void pickColorScheme(value, schemeItems);
    schemeItems.push({ value, dot, item });
    menu.append(item);
  }
  menu.append(el('div', 'profile-menu-sep'));

  const relogin = el('button', 'profile-menu-item', '⇄ Sign in again');
  relogin.onclick = () => {
    closeProfileMenu();
    openLoginDialog();
  };
  const logout = el('button', 'profile-menu-item danger', '⏻ Sign out');
  logout.onclick = async () => {
    closeProfileMenu();
    try {
      applyAuth(await window.coreai.authLogout());
      toast('Signed out');
    } catch (error) {
      toast(`Sign-out failed: ${cleanIpcError(error)}`);
    }
  };
  menu.append(relogin, logout);

  document.body.append(menu);
  const rect = anchor.getBoundingClientRect();
  menu.style.left = `${Math.max(8, rect.left)}px`;
  menu.style.bottom = `${Math.max(8, window.innerHeight - rect.top + 6)}px`;
  profileMenu = menu;

  const onDown = (event: MouseEvent) => {
    if (!menu.contains(event.target as Node)) closeProfileMenu();
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') closeProfileMenu();
  };
  document.addEventListener('mousedown', onDown);
  document.addEventListener('keydown', onKey);
  disposeProfileMenu = () => {
    document.removeEventListener('mousedown', onDown);
    document.removeEventListener('keydown', onKey);
  };
}

/** Environments share the local workspace/sessions; hub, models, proxy and identity follow the active one. */
async function switchEnvironment(serverUrl: string): Promise<void> {
  closeProfileMenu();
  try {
    const next = await window.coreai.authSwitch(serverUrl);
    applyAuth(next);
    toast(`Switched to ${hostOf(serverUrl)}`);
    const sessionId = state.sessionId;
    if (sessionId && state.workspace) {
      const resumed = await call('session/resume', { sessionId });
      if (resumed) setStatus(String(resumed.status ?? 'idle'));
    }
    void refreshModelState();
  } catch (error) {
    toast(`Switch failed: ${cleanIpcError(error)}`);
  }
}

function openLoginDialog(): void {
  closeProfileMenu();
  const overlay = el('div', 'overlay');
  const dialog = el('div', 'login-dialog');
  dialog.append(el('div', 'login-title', 'Sign in to core-ai-server'));
  dialog.append(el('div', 'login-hint', 'Use your identity for hub tools, remote agents and trace uploads. The CLI shares this sign-in.'));

  dialog.append(el('div', 'login-label', 'Server URL'));
  const serverInput = el('input', 'login-input') as HTMLInputElement;
  serverInput.value = state.auth?.serverUrl ?? 'https://core-ai-server.connexup-uat.net';
  dialog.append(serverInput);

  const status = el('div', 'login-status');
  const setStatus = (text: string, isError = false) => {
    status.textContent = text;
    status.className = isError ? 'login-status error' : 'login-status';
  };

  const browserButton = el('button', 'btn primary', 'Continue in browser');
  const keyLink = el('button', 'linkish', 'or paste an API key');
  const keyRow = el('div', 'login-key-row');
  const keyInput = el('input', 'login-input') as HTMLInputElement;
  keyInput.type = 'password';
  keyInput.placeholder = 'API Key';
  const keyButton = el('button', 'btn', 'Sign in with key');
  keyRow.append(keyInput, keyButton);
  keyRow.style.display = 'none';
  keyLink.onclick = () => {
    const show = keyRow.style.display === 'none';
    keyRow.style.display = show ? 'flex' : 'none';
    if (show) keyInput.focus();
  };

  const actions = el('div', 'login-actions');
  const closeButton = el('button', 'btn', 'Close');
  actions.append(closeButton);
  dialog.append(browserButton, keyLink, keyRow, status, actions);

  overlay.append(dialog);
  document.body.append(overlay);

  const close = () => {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') close();
  };
  document.addEventListener('keydown', onKey);
  overlay.addEventListener('mousedown', (event) => {
    if (event.target === overlay) close();
  });
  closeButton.onclick = close;

  const runLogin = async (kind: 'browser' | 'key') => {
    const serverUrl = serverInput.value.trim().replace(/\/+$/, '');
    if (!/^https?:\/\//.test(serverUrl)) {
      setStatus('Server URL must start with http:// or https://', true);
      return;
    }
    browserButton.disabled = true;
    keyButton.disabled = true;
    setStatus(kind === 'browser' ? 'Browser opened — finish signing in there (times out in 120s)…' : 'Validating the API key…');
    try {
      const next = kind === 'browser'
        ? await window.coreai.authLogin(serverUrl)
        : await window.coreai.authLoginWithKey(serverUrl, keyInput.value.trim());
      applyAuth(next);
      toast(`Signed in as ${next?.name ?? next?.userId ?? serverUrl}`);
      close();
    } catch (error) {
      browserButton.disabled = false;
      keyButton.disabled = false;
      setStatus(`Sign-in failed: ${cleanIpcError(error)}`, true);
    }
  };
  browserButton.onclick = () => void runLogin('browser');
  keyButton.onclick = () => void runLogin('key');
  serverInput.onkeydown = (event) => {
    if (event.key === 'Enter') void runLogin('browser');
  };
  serverInput.focus();
}

function buildSessionsNavRow(): HTMLElement {
  const row = el('div', `nav-sessions-row${state.nav === 'sessions' ? ' active' : ''}`);

  const toggle = el('button', 'collapse-toggle');
  toggle.title = state.sessionsCollapsed ? 'Expand sessions' : 'Collapse sessions';
  toggle.append(icon(state.sessionsCollapsed ? 'chevron-right' : 'chevron-down'));
  toggle.onclick = (event) => {
    event.stopPropagation();
    state.sessionsCollapsed = !state.sessionsCollapsed;
    localStorage.setItem(COLLAPSE_KEY, state.sessionsCollapsed ? '1' : '0');
    renderSidebar();
  };

  const label = el('button', `nav-label${state.nav === 'sessions' ? ' active' : ''}`);
  label.title = 'Sessions';
  label.append(icon('sessions'), el('span', 'nav-text', 'Sessions'));
  label.onclick = () => {
    state.nav = 'sessions';
    renderSidebar();
    renderContent();
  };

  const add = el('button', 'new-session');
  add.title = 'New session';
  add.append(icon('plus'), el('span', 'new-session-text', 'New'));
  add.onclick = (event) => {
    event.stopPropagation();
    if (state.sessionsCollapsed) {
      state.sessionsCollapsed = false;
      localStorage.setItem(COLLAPSE_KEY, '0');
      renderSidebar();
    }
    void startNewConversation();
  };

  row.append(label, add, toggle);
  return row;
}

async function loadSummaries(): Promise<void> {
  try {
    state.summaries = (await window.coreai.workspaceSummaries()) ?? [];
  } catch {
    state.summaries = [];
  }
  renderSessionItems();
}

function summaryCount(workspace: string): number {
  return state.summaries.find((summary) => summary.path === workspace)?.count ?? 0;
}

/** Model registry + current model / thinking level, straight from the engine. */
async function refreshModelState(): Promise<void> {
  const listed = await call('model/list', {});
  if (listed?.models) {
    const seen = new Set<string>();
    const models: typeof state.models = [];
    for (const entry of listed.models as Array<{ model?: string; provider?: string; displayName?: string; reasoningEfforts?: string[] }>) {
      if (!entry?.model || seen.has(entry.model)) continue;
      seen.add(entry.model);
      models.push({ model: entry.model, provider: entry.provider, displayName: entry.displayName, reasoningEfforts: entry.reasoningEfforts });
    }
    state.models = models;
    state.modelSource = typeof listed.source === 'string' ? listed.source : null;
  }
  const current = await call('model/get', state.sessionId ? { sessionId: state.sessionId } : {});
  if (current?.model) state.currentModel = String(current.model);
  const thinking = await call('thinking/get', {});
  if (thinking?.level) state.thinkingLevel = String(thinking.level);
  renderComposerControls();
}

/** Reasoning effort only takes effect in a fresh engine process: dispose + lazily respawn. */
async function restartWorkspaceEngine(): Promise<void> {
  const workspace = state.workspace;
  if (!workspace) return;
  const sessionId = state.sessionId;
  await window.coreai.restartEngine(workspace);
  if (sessionId && sessionId === state.sessionId) {
    const resumed = await call('session/resume', { sessionId });
    if (resumed) setStatus(String(resumed.status ?? 'idle'));
  }
}

function withCount(label: string, workspace: string | null): string {
  const count = workspace ? summaryCount(workspace) : 0;
  return count > 0 ? `${label} · ${count}` : label;
}

function otherWorkspacesWithSessions(): WorkspaceSummary[] {
  return state.summaries
    .filter((summary) => summary.count > 0 && summary.path !== state.workspace)
    .sort((a, b) => b.latestUpdatedAt - a.latestUpdatedAt);
}

/** Most recently used session across the other workspaces — the "resume last" rescue. */
function resumeTarget(): WorkspaceSummary | null {
  let best: WorkspaceSummary | null = null;
  for (const summary of otherWorkspacesWithSessions()) {
    if (!summary.latestSessionId) continue;
    if (!best || summary.latestUpdatedAt > best.latestUpdatedAt) best = summary;
  }
  return best;
}

async function resumeLastSession(summary: WorkspaceSummary): Promise<void> {
  if (!summary.latestSessionId) return;
  await connect(summary.path);
  await openSession(summary.latestSessionId);
}

function renderSessionItems(): void {
  if (!sessionItemsEl) return;
  sessionItemsEl.replaceChildren();
  if (state.sessions.length === 0) {
    sessionItemsEl.append(el('div', 'session-sub-empty', 'No sessions in this workspace'));
    const resume = resumeTarget();
    if (resume) {
      const title = resume.latestTitle ? `“${resume.latestTitle}”` : 'last session';
      const button = el('button', 'session-sub-resume', `▶ Resume last: ${title} · ${workspaceLabel(resume.path)}`);
      button.title = `${resume.path} (${resume.latestSessionId})`;
      button.onclick = () => void resumeLastSession(resume);
      sessionItemsEl.append(button);
    }
    const others = otherWorkspacesWithSessions().slice(0, 3);
    if (others.length) {
      const row = el('div', 'session-sub-ws');
      row.append(el('span', undefined, 'Other workspaces: '));
      for (const summary of others) {
        const chip = el('button', undefined, `${workspaceLabel(summary.path)} · ${summary.count}`);
        chip.title = summary.path;
        chip.onclick = () => void connect(summary.path);
        row.append(chip);
      }
      sessionItemsEl.append(row);
    }
  }
  for (const session of state.sessions) {
    const item = el('button', `session-sub-item${session.sessionId === state.sessionId ? ' active' : ''}`);
    if (session.status === 'running') {
      item.append(el('span', 'session-spin'));
    }
    item.append(el('span', 'session-title', session.title || session.sessionId));
    item.title = `${session.sessionId}\n${fmtTime(session.updatedAt)}${session.status === 'running' ? '\nrunning' : ''}`;
    item.onclick = () => {
      if (session.sessionId !== state.sessionId) void openSession(session.sessionId);
      else void showConversationView();
    };
    sessionItemsEl.append(item);
  }
  if (state.hasMoreSessions) {
    const more = el('button', 'session-sub-more', 'More…');
    more.onclick = () => void loadMoreSessions();
    sessionItemsEl.append(more);
  }
}

let composerMenu: HTMLElement | null = null;
let disposeComposerMenu: (() => void) | null = null;

function closeComposerMenu(): void {
  composerMenu?.remove();
  composerMenu = null;
  disposeComposerMenu?.();
  disposeComposerMenu = null;
}

/** Panel mode collapses each select to an icon button; clicking it opens the same options as a menu. */
function iconMenuButton(iconName: keyof typeof ICONS, select: HTMLSelectElement, label: string): HTMLButtonElement {
  const button = el('button', 'composer-icon-btn');
  button.append(icon(iconName));
  button.title = `${label}: ${select.options[select.selectedIndex]?.textContent ?? ''}`;
  button.onclick = (event) => {
    event.stopPropagation();
    openComposerMenu(button, select, label);
  };
  return button;
}

function openComposerMenu(anchor: HTMLElement, select: HTMLSelectElement, label: string): void {
  closeComposerMenu();
  const menu = el('div', 'composer-menu');
  for (const option of Array.from(select.options)) {
    const item = el('button', `composer-menu-item${option.value === select.value ? ' active' : ''}`, option.textContent ?? option.value);
    item.onclick = () => {
      closeComposerMenu();
      anchor.title = `${label}: ${option.textContent ?? option.value}`;
      select.value = option.value;
      select.dispatchEvent(new Event('change'));
    };
    menu.append(item);
  }
  document.body.append(menu);
  const rect = anchor.getBoundingClientRect();
  menu.style.left = `${Math.max(8, rect.left)}px`;
  menu.style.bottom = `${Math.max(8, window.innerHeight - rect.top + 6)}px`;
  composerMenu = menu;

  const onDown = (event: MouseEvent) => {
    if (!menu.contains(event.target as Node)) closeComposerMenu();
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') closeComposerMenu();
  };
  document.addEventListener('mousedown', onDown);
  document.addEventListener('keydown', onKey);
  disposeComposerMenu = () => {
    document.removeEventListener('mousedown', onDown);
    document.removeEventListener('keydown', onKey);
  };
}

function renderComposerControls(): void {
  closeComposerMenu();
  if (!composerControlsEl) return;
  composerControlsEl.replaceChildren();

  const workspacePair = el('span', 'control-pair');
  workspacePair.append(el('span', 'composer-label', 'Workspace'));
  const workspace = el('select');
  workspace.className = 'workspace-select';

  const defaultOption = el('option', undefined, withCount('default', state.info?.defaultWorkspacePath ?? null));
  defaultOption.value = 'default';
  if (state.info?.defaultWorkspacePath) {
    defaultOption.title = `${state.info.defaultWorkspacePath}${state.defaultExists ? '' : ' (created with your first message)'}`;
  }
  workspace.append(defaultOption);
  if (state.workspace && state.workspace !== state.info?.defaultWorkspacePath) {
    const current = el('option', undefined, withCount(workspaceLabel(state.workspace), state.workspace));
    current.value = state.workspace;
    current.title = state.workspace;
    workspace.append(current);
  }
  for (const known of state.workspaces) {
    if (known === state.workspace) continue;
    if (known === state.info?.defaultWorkspacePath) continue;
    const option = el('option', undefined, withCount(workspaceLabel(known), known));
    option.value = known;
    option.title = known;
    workspace.append(option);
  }
  const choose = el('option', undefined, '＋ Choose folder…');
  choose.value = '__choose__';
  workspace.append(choose);
  workspace.value = !state.workspace || state.workspace === state.info?.defaultWorkspacePath
    ? 'default'
    : state.workspace;
  workspace.onchange = async () => {
    const value = workspace.value;
    if (value === '__choose__') {
      const chosen = await window.coreai.chooseWorkspace();
      if (chosen) await connect(chosen);
      else renderComposerControls();
      return;
    }
    if (value === 'default') {
      if (state.workspace && isDatedDefaultWorkspace(state.workspace)) return;
      if (state.defaultExists) {
        await connect(state.info.defaultWorkspacePath);
      } else if (state.workspace) {
        await enterDefaultMode();
        toast('Back to default — created with your first message');
      } else {
        toast('The default workspace is created with your first message');
      }
      return;
    }
    if (value !== state.workspace) {
      await connect(value);
    }
  };
  workspacePair.prepend(iconMenuButton('folder', workspace, 'Workspace'));
  workspacePair.append(workspace);
  composerControlsEl.append(workspacePair);

  const modelPair = el('span', 'control-pair');
  modelPair.append(el('span', 'composer-label', 'Model'));
  const modelSelect = el('select', 'model-select') as HTMLSelectElement;
  const modelOptions = state.models.length ? state.models : (state.currentModel ? [{ model: state.currentModel }] : []);
  for (const entry of modelOptions) {
    const option = el('option', undefined, entry.displayName && entry.displayName !== entry.model ? entry.displayName : entry.model);
    option.value = entry.model;
    option.title = entry.model;
    modelSelect.append(option);
  }
  if (state.currentModel) modelSelect.value = state.currentModel;
  modelSelect.disabled = modelOptions.length === 0 || !state.workspace;
  modelSelect.title = state.modelSource === 'server'
    ? 'Models from the server hub (the same list the gateway will route)'
    : 'Models for this workspace (local registry)';
  modelSelect.onchange = async () => {
    const picked = modelSelect.value;
    const applied = await call('model/set', { model: picked });
    if (!applied) {
      modelSelect.value = state.currentModel ?? '';
      return;
    }
    state.currentModel = String(applied.model ?? picked);
    toast(`Model: ${state.currentModel}`);
  };
  modelPair.prepend(iconMenuButton('cpu', modelSelect, 'Model'));
  modelPair.append(modelSelect);
  composerControlsEl.append(modelPair);

  const thinkingPair = el('span', 'control-pair');
  thinkingPair.append(el('span', 'composer-label', 'Thinking'));
  const thinkingSelect = el('select', 'thinking-select') as HTMLSelectElement;
  const currentEntry = state.models.find((entry) => entry.model === state.currentModel);
  const efforts = currentEntry?.reasoningEfforts ?? [];
  const levels = efforts.length ? [...efforts] : ['none', 'low', 'high', 'max'];
  if (state.thinkingLevel !== 'off' && !levels.includes(state.thinkingLevel)) levels.unshift(state.thinkingLevel);
  levels.push('off');
  for (const level of levels) {
    const option = el('option', undefined, level === 'off' ? 'off (provider default)' : level);
    option.value = level;
    thinkingSelect.append(option);
  }
  thinkingSelect.value = state.thinkingLevel;
  thinkingSelect.disabled = !state.workspace;
  thinkingSelect.title = efforts.length
    ? 'Reasoning effort — levels this model declares; takes effect after the engine reloads'
    : 'Reasoning effort — takes effect after the engine reloads';
  thinkingSelect.onchange = async () => {
    const picked = thinkingSelect.value;
    const applied = await call('thinking/set', { level: picked });
    if (!applied) {
      thinkingSelect.value = state.thinkingLevel;
      return;
    }
    state.thinkingLevel = String(applied.level ?? picked);
    if (state.status === 'running') {
      state.pendingEngineRestart = true;
      toast(`Thinking effort: ${state.thinkingLevel} — applies when this turn ends (engine reload)`);
      return;
    }
    toast(`Thinking effort: ${state.thinkingLevel} — reloading engine…`);
    await restartWorkspaceEngine();
  };
  thinkingPair.prepend(iconMenuButton('lightbulb', thinkingSelect, 'Thinking'));
  thinkingPair.append(thinkingSelect);
  composerControlsEl.append(thinkingPair);

  composerControlsEl.append(el('span', 'spacer'));

  const policyPair = el('span', 'control-pair');
  policyPair.append(el('span', 'composer-label', 'Permissions'));
  const policy = el('select', 'policy-select');
  for (const value of ['ask', 'workspace-auto', 'full']) {
    const option = el('option', undefined, value);
    option.value = value;
    policy.append(option);
  }
  policy.value = state.approvalPolicy;
  policy.onchange = async () => {
    state.approvalPolicy = policy.value;
    await window.coreai.setSettings({ approvalPolicy: policy.value });
    toast(`Approval policy set to ${policy.value} (the engine restarts to apply it)`);
  };
  policyPair.prepend(iconMenuButton('shield', policy, 'Permissions'));
  policyPair.append(policy);
  composerControlsEl.append(policyPair);

  stopButton = el('button', 'btn danger small', 'Stop') as HTMLButtonElement;
  stopButton.title = 'Cancel the running turn';
  stopButton.disabled = state.status !== 'running';
  stopButton.onclick = async () => {
    if (state.sessionId) await call('session/cancel', { sessionId: state.sessionId });
  };
  composerControlsEl.append(stopButton);

  browserButton = el('button', 'btn small', 'Browser');
  browserButton.title = 'Embedded browser — click, scroll and sign in here; the agent drives the same tabs';
  browserButton.onclick = () => void toggleBrowser();
  composerControlsEl.append(browserButton);
  statusChip = el('span', `composer-status ${state.status}`, state.status);
  composerControlsEl.append(statusChip);
  updateBrowserButton();
}

async function chooseWorkspace(): Promise<void> {
  const chosen = await window.coreai.chooseWorkspace();
  if (chosen) await connect(chosen);
}

async function connect(workspace: string): Promise<void> {
  state.workspace = workspace;
  if (workspace === state.info?.defaultWorkspacePath) state.defaultExists = true;
  state.sessionId = null;
  state.sessionTitle = '';
  state.sessions = [];
  state.sessionCursor = null;
  state.hasMoreSessions = false;
  state.toolMap.clear();
  resetStreaming();
  chatView = null;
  transcriptEl = null;
  chatTitleEl = null;
  removeApprovalBar();
  setStatus('idle');
  if (!state.workspaces.includes(workspace)) {
    state.workspaces = [workspace, ...state.workspaces].slice(0, 8);
  }
  const settings = await window.coreai.getSettings();
  state.workspaces = settings?.workspaces ?? state.workspaces;
  renderSidebar();
  renderComposerControls();
  await loadSummaries();
  await loadSessions(true);
  await refreshModelState();
  renderContent();
}

async function enterDefaultMode(): Promise<void> {
  state.workspace = null;
  state.sessionId = null;
  state.sessionTitle = '';
  state.sessions = [];
  state.sessionCursor = null;
  state.hasMoreSessions = false;
  state.toolMap.clear();
  resetStreaming();
  chatView = null;
  transcriptEl = null;
  chatTitleEl = null;
  removeApprovalBar();
  setStatus('idle');
  renderSidebar();
  renderComposerControls();
  await renderContent();
}

/** The dated default workspace is created here — the first time a session actually starts. */
async function materializeDefaultWorkspace(): Promise<void> {
  const workspace = await window.coreai.defaultWorkspace();
  state.defaultExists = true;
  await connect(workspace);
}

// ---------- sessions ----------

async function loadSessions(reset: boolean): Promise<void> {
  const params: Record<string, unknown> = { limit: SECTION_PAGE_SIZE };
  if (!reset && state.sessionCursor) params.cursor = state.sessionCursor;
  const listed = await call('session/list', params);
  if (!listed) return;
  const rows: SessionRow[] = listed.sessions ?? [];
  state.sessions = reset ? rows : [...state.sessions, ...rows];
  state.sessionCursor = listed.nextCursor ?? null;
  state.hasMoreSessions = Boolean(listed.nextCursor);
  renderSessionItems();
}

async function loadMoreSessions(): Promise<void> {
  await loadSessions(false);
}

async function refreshSessionList(): Promise<void> {
  await loadSessions(true);
}

/**
 * +New opens a blank conversation window only. Sessions are created lazily on the first
 * message, so clicking +New and walking away leaves no empty session behind.
 */
async function startNewConversation(): Promise<void> {
  state.sessionId = null;
  state.sessionTitle = '';
  state.toolMap.clear();
  resetStreaming();
  removeApprovalBar();
  setStatus('idle');
  renderSessionItems();
  updateChatHeader();
  await showConversationView();
  if (transcriptEl) {
    transcriptEl.replaceChildren();
    showBlankHint();
  }
  composerInput?.focus();
}

/** The first message of a blank window creates the session (and, in `default`, the workspace). */
async function startNewSession(firstMessage: string): Promise<boolean> {
  if (!state.workspace) {
    await materializeDefaultWorkspace();
    if (!state.workspace) return false;
  }
  const created = await call('session/create', {});
  if (!created) return false;
  const createdId = created.sessionId as string;
  await refreshSessionList();
  // The engine persists a session only once it has content (empty sessions stay off disk),
  // so keep the just-started conversation visible until its first turn is saved.
  if (!state.sessions.some((session) => session.sessionId === createdId)) {
    state.sessions = [{ sessionId: createdId, title: firstMessage }, ...state.sessions];
  }
  state.sessionId = createdId;
  state.sessionTitle = state.sessions.find((session) => session.sessionId === createdId)?.title ?? firstMessage;
  state.toolMap.clear();
  resetStreaming();
  removeApprovalBar();
  renderSessionItems();
  updateChatHeader();
  if (transcriptEl) transcriptEl.replaceChildren();
  return true;
}

/** A sidebar session action means "show the conversation" — a panel must give the chat view back. */
async function showConversationView(): Promise<void> {
  if (state.nav === 'sessions') return;
  state.nav = 'sessions';
  renderSidebar();
  await renderContent();
}

async function openSession(sessionId: string): Promise<void> {
  state.sessionId = sessionId;
  state.sessionTitle = state.sessions.find((session) => session.sessionId === sessionId)?.title ?? sessionId;
  state.toolMap.clear();
  resetStreaming();
  removeApprovalBar();
  renderSessionItems();
  updateChatHeader();
  await showConversationView();
  if (!transcriptEl) return;
  transcriptEl.replaceChildren();

  const resumed = await call('session/resume', { sessionId });
  setStatus(String(resumed?.status ?? 'idle'));
  const history = await call('session/history', { sessionId, limit: 300 });
  if (history?.messages) {
    for (const message of history.messages) {
      addMessageBubble(message.role === 'user' ? 'user' : 'assistant', message.text ?? '');
    }
  }
  scrollTranscript(true);
  void refreshModelState();
}

function updateChatHeader(): void {
  if (chatTitleEl) chatTitleEl.textContent = state.sessionTitle || 'new session';
}

function showBlankHint(): void {
  if (!state.workspace) {
    showDefaultHint();
    return;
  }
  if (!transcriptEl || state.sessionId || transcriptEl.childElementCount > 0) return;
  const hint = el('div', 'default-hint');
  hint.append(el('div', 'big', 'new session'));
  hint.append(el('div', undefined, 'Created with your first message; it then shows up in the session list.'));
  transcriptEl.append(hint);
}

function showDefaultHint(): void {
  if (!transcriptEl || state.sessionId || transcriptEl.childElementCount > 0) return;
  const hint = el('div', 'default-hint');
  hint.append(el('div', 'big', 'default workspace'));
  hint.append(el('div', undefined, 'No workspace folder yet. It is created with your first message:'));
  hint.append(el('div', 'mono', state.info?.defaultWorkspacePath ?? ''));
  const others = otherWorkspacesWithSessions().slice(0, 3);
  if (others.length) {
    const list = others.map((summary) => `${workspaceLabel(summary.path)} · ${summary.count}`).join(', ');
    hint.append(el('div', undefined, `Other workspaces still have sessions: ${list} — switch from the Workspace menu below the composer.`));
  } else {
    hint.append(el('div', undefined, 'Or pick an existing folder from the Workspace menu below the composer.'));
  }
  transcriptEl.append(hint);
}

function virtualDefaultPanel(): HTMLElement {
  const label = NAV.find((item) => item.key === state.nav)?.label ?? state.nav;
  const { root, body } = panel(label, 'The default workspace is not created yet');
  body.append(el('div', 'notice', 'This panel needs an engine connection: send your first message to create the default workspace, or pick a folder from the Workspace menu below the composer.'));
  const button = el('button', 'btn small', 'Choose folder…');
  button.style.marginTop = '10px';
  button.onclick = chooseWorkspace;
  body.append(button);
  return root;
}

// ---------- chat view ----------

function buildChatView(): void {
  chatView = el('div', 'chat');

  const header = el('div', 'chat-header');
  chatTitleEl = el('span', 'chat-title', 'no session selected');
  header.append(chatTitleEl);

  const transcript = el('div', 'transcript');
  transcriptEl = transcript;
  transcriptStick = true;
  transcript.addEventListener('scroll', () => {
    const distance = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight;
    transcriptStick = distance < 60;
  });
  transcript.addEventListener('click', (event) => {
    const anchor = (event.target as HTMLElement).closest('a');
    if (!anchor) return;
    event.preventDefault();
    void window.coreai.openExternal(anchor.href);
  });
  const chatMain = el('div', 'chat-main');
  chatMain.append(header, transcript, buildComposer());
  chatView.append(chatMain, buildBrowserPanel());
}

function buildComposer(): HTMLElement {
  const composer = el('div', 'composer');
  const card = el('div', 'composer-card');
  composerInput = el('textarea');
  composerInput.rows = 1;
  composerInput.placeholder = 'Type a message — Enter to send, Shift+Enter for a new line';
  composerInput.onkeydown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void sendCurrent();
    }
  };
  composerAutoGrow = () => {
    if (!composerInput) return;
    composerInput.style.height = 'auto';
    composerInput.style.height = `${Math.min(composerInput.scrollHeight, 360)}px`;
  };
  composerInput.oninput = () => composerAutoGrow?.();
  composerControlsEl = el('div', 'composer-controls');
  card.append(composerInput, composerControlsEl);
  composer.append(card);
  composerAutoGrow();
  renderComposerControls();
  return composer;
}

// --- Embedded browser (design §4.9 P2): the panel IS a real Chromium view (a WebContentsView
// the main process floats over `.browser-frame`). The user clicks/types the page directly; the
// agent drives the same tabs through the desktop's controlled CDP bridge. This file only
// reports the placeholder rect and renders the toolbar/tabs from state.

function buildBrowserPanel(): HTMLElement {
  const panel = el('div', 'browser-panel');
  browserPanelEl = panel;

  const toolbar = el('div', 'browser-toolbar');
  browserBackEl = browserToolButton('arrow-left', 'Back', () => browserAction(window.coreai.browserBack));
  browserForwardEl = browserToolButton('arrow-right', 'Forward', () => browserAction(window.coreai.browserForward));
  browserReloadEl = browserToolButton('refresh', 'Reload', () => browserAction(() => {
    return state.browserStatus?.loading ? window.coreai.browserStopLoading() : window.coreai.browserReload();
  }));
  browserAddressEl = el('input', 'browser-address') as HTMLInputElement;
  browserAddressEl.spellcheck = false;
  browserAddressEl.placeholder = 'Search or enter address';
  browserAddressEl.onkeydown = (event) => {
    if (!browserAddressEl) return;
    if (event.key === 'Enter') {
      event.preventDefault();
      const value = browserAddressEl.value;
      browserAddressEl.blur();
      void browserAction(() => window.coreai.browserNavigate(value));
    } else if (event.key === 'Escape') {
      browserAddressEl.value = state.browserStatus?.url ?? '';
      browserAddressEl.blur();
    }
  };
  browserAddressEl.onfocus = () => browserAddressEl?.select();
  browserOpenExternalEl = browserToolButton('arrow-up-right', 'Open in the system browser', () => {
    const url = state.browserStatus?.url;
    if (url && /^https?:/i.test(url)) void window.coreai.openExternal(url);
  });
  browserDotEl = el('span', 'browser-dot');
  const stop = el('button', 'btn small', 'Stop');
  stop.title = 'Close the embedded browser';
  stop.onclick = () => void stopBrowser();
  toolbar.append(browserBackEl, browserForwardEl, browserReloadEl, browserAddressEl, browserOpenExternalEl, browserDotEl, stop);

  browserTabsEl = el('div', 'browser-tabs');

  const body = el('div', 'browser-body');
  const frame = el('div', 'browser-frame');
  browserBodyEl = frame;
  const hint = el('div', 'browser-hint');
  const start = el('button', 'btn small', 'Start browser');
  start.onclick = () => void startBrowser();
  hint.append(el('div', undefined, 'No embedded browser running.'), start);
  browserHintEl = hint;
  body.append(frame, hint);

  panel.append(toolbar, browserTabsEl, body);
  browserResizeObserver?.disconnect();
  browserResizeObserver = new ResizeObserver(() => scheduleBrowserBounds());
  browserResizeObserver.observe(frame);
  renderBrowserPanel();
  scheduleBrowserBounds();
  return panel;
}

function browserToolButton(iconName: keyof typeof ICONS, title: string, action: () => void): HTMLButtonElement {
  const button = el('button', 'browser-tool') as HTMLButtonElement;
  button.title = title;
  button.append(icon(iconName));
  button.onclick = action;
  return button;
}

/** Runs a browser IPC call and applies the returned state; failures surface as toasts. */
async function browserAction(action: () => Promise<BrowserStatus | null>): Promise<void> {
  try {
    applyBrowserStatus(await action());
  } catch (error) {
    toast(`Browser: ${cleanIpcError(error)}`);
  }
}

/**
 * The native view floats above the DOM, so main needs the placeholder rect in window
 * coordinates. Debounced through requestAnimationFrame and deduplicated; a hidden panel or a
 * stopped browser sends null once to hide the view.
 */
function scheduleBrowserBounds(): void {
  if (browserBoundsTimer !== null) window.cancelAnimationFrame(browserBoundsTimer);
  browserBoundsTimer = window.requestAnimationFrame(() => {
    browserBoundsTimer = null;
    const frame = browserBodyEl;
    const running = Boolean(state.browserStatus?.running);
    if (!browserPanelEl || browserPanelEl.hidden || !running || !frame) {
      if (browserBoundsValue !== null) {
        browserBoundsValue = null;
        void window.coreai.browserSetBounds(null).catch(() => undefined);
      }
      return;
    }
    const rect = frame.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) return;
    const bounds: BrowserBounds = {
      x: Math.round(rect.left),
      y: Math.round(rect.top),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    };
    const serialized = `${bounds.x},${bounds.y},${bounds.width},${bounds.height}`;
    if (serialized === browserBoundsValue) return;
    browserBoundsValue = serialized;
    void window.coreai.browserSetBounds(bounds).catch(() => undefined);
  });
}

function renderBrowserPanel(): void {
  if (!browserPanelEl) return;
  const status = state.browserStatus;
  const running = Boolean(status?.running);
  browserPanelEl.hidden = !state.browserPanelOpen;
  syncSidebarWithBrowserPanel();
  if (browserDotEl) browserDotEl.className = running ? 'browser-dot on' : 'browser-dot';
  if (browserBackEl) browserBackEl.disabled = !running || !status?.canGoBack;
  if (browserForwardEl) browserForwardEl.disabled = !running || !status?.canGoForward;
  if (browserReloadEl) browserReloadEl.title = status?.loading ? 'Stop loading' : 'Reload';
  if (browserOpenExternalEl) browserOpenExternalEl.disabled = !running || !/^https?:/i.test(status?.url ?? '');
  if (browserAddressEl) {
    browserAddressEl.disabled = !running;
    if (document.activeElement !== browserAddressEl) {
      browserAddressEl.value = running ? (status?.url ?? '') : '';
    }
  }
  renderBrowserTabs();
  if (browserHintEl) browserHintEl.hidden = running;
  updateBrowserButton();
  scheduleBrowserBounds();
}

function renderBrowserTabs(): void {
  if (!browserTabsEl) return;
  const status = state.browserStatus;
  const tabs = status?.tabs ?? [];
  browserTabsEl.hidden = !status?.running || tabs.length === 0;
  browserTabsEl.replaceChildren();
  for (const tab of tabs) {
    const node = el('div', tab.active ? 'browser-tab active' : 'browser-tab');
    const title = el('span', 'browser-tab-title', tab.title || hostOf(tab.url) || 'New tab');
    title.title = `${tab.title ? `${tab.title}\n` : ''}${tab.url}`;
    const close = el('button', 'browser-tab-close');
    close.title = 'Close tab';
    close.append(icon('x'));
    close.onclick = (event) => {
      event.stopPropagation();
      void browserAction(() => window.coreai.browserCloseTab(tab.id));
    };
    node.onclick = () => void browserAction(() => window.coreai.browserSelectTab(tab.id));
    node.append(title, close);
    browserTabsEl.append(node);
  }
  const add = el('button', 'browser-tab-new');
  add.title = 'New tab';
  add.append(icon('plus'));
  add.onclick = () => void browserAction(() => window.coreai.browserNewTab());
  browserTabsEl.append(add);
}

function updateBrowserButton(): void {
  if (!browserButton) return;
  const running = Boolean(state.browserStatus?.running);
  browserButton.textContent = running ? 'Browser ●' : 'Browser';
  browserButton.classList.toggle('browser-on', running);
}

/** Opening the panel narrows the chat column, so the sidebar yields automatically. */
function syncSidebarWithBrowserPanel(): void {
  const open = state.browserPanelOpen;
  if (open === browserPanelWasOpen) return;
  browserPanelWasOpen = open;
  if (open) {
    if (!state.sidebarCollapsed) {
      state.sidebarAutoCollapsed = true;
      setSidebarCollapsed(true);
    }
  } else if (state.sidebarAutoCollapsed) {
    state.sidebarAutoCollapsed = false;
    setSidebarCollapsed(false);
  }
}

function setSidebarCollapsed(collapsed: boolean, options: { manual?: boolean } = {}): void {
  if (options.manual) state.sidebarAutoCollapsed = false;
  state.sidebarCollapsed = collapsed;
  document.getElementById('app')?.classList.toggle('sidebar-collapsed', collapsed);
  updateSidebarToggle();
}

/** The collapse control lives in the sidebar brand row; in rail mode it is the only way back. */
function ensureSidebarToggle(): void {
  if (sidebarToggleEl) return;
  const brand = document.querySelector('#sidebar .brand');
  if (!(brand instanceof HTMLElement)) return;
  sidebarToggleEl = el('button', 'sidebar-toggle');
  sidebarToggleEl.onclick = () => setSidebarCollapsed(!state.sidebarCollapsed, { manual: true });
  brand.append(sidebarToggleEl);
  updateSidebarToggle();
}

function updateSidebarToggle(): void {
  if (!sidebarToggleEl) return;
  const collapsed = state.sidebarCollapsed;
  sidebarToggleEl.title = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
  sidebarToggleEl.replaceChildren(icon(collapsed ? 'chevron-right' : 'chevron-left'));
}

function applyBrowserStatus(status: BrowserStatus | null): void {
  state.browserStatus = status;
  renderBrowserPanel();
}

async function toggleBrowser(): Promise<void> {
  if (state.browserStatus?.running) {
    state.browserPanelOpen = !state.browserPanelOpen;
    renderBrowserPanel();
    return;
  }
  await startBrowser();
}

async function startBrowser(): Promise<void> {
  if (state.browserStarting) return;
  state.browserStarting = true;
  toast('Starting the embedded browser…');
  try {
    applyBrowserStatus(await window.coreai.browserStart());
    state.browserPanelOpen = true;
    renderBrowserPanel();
    toast('Browser ready — the agent attaches to this pane');
  } catch (error) {
    toast(`Browser start failed: ${cleanIpcError(error)}`);
  } finally {
    state.browserStarting = false;
  }
}

async function stopBrowser(): Promise<void> {
  try {
    applyBrowserStatus(await window.coreai.browserStop());
    state.browserPanelOpen = false;
    renderBrowserPanel();
    toast('Browser stopped');
  } catch (error) {
    toast(`Browser stop failed: ${cleanIpcError(error)}`);
  }
}

/**
 * The harness retries BU_CDP_URL for 30s, so the bridge only needs to be listening by the time
 * a browser-use tool starts; starting the embedded browser here also opens the panel so the
 * user can watch (and take over) the pages the agent drives.
 */
function maybeAutoStartBrowser(event: Record<string, unknown>): void {
  const args = typeof event.arguments === 'string' ? event.arguments : '';
  if (!args.includes('browser-use')) return;
  if (state.browserStatus?.running) {
    if (!state.browserPanelOpen) {
      state.browserPanelOpen = true;
      renderBrowserPanel();
    }
    return;
  }
  if (state.browserStarting) return;
  void startBrowser();
}

/** Engine-injected `<system-reminder>` messages render as a centered gray note, not a user bubble. */
function systemReminderText(text: string): string | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith('<system-reminder>')) return null;
  const matches = [...trimmed.matchAll(/<system-reminder>([\s\S]*?)<\/system-reminder>/g)];
  if (!matches.length) return null;
  const leftover = trimmed.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim();
  if (leftover) return null;
  return matches.map((match) => match[1].trim()).filter(Boolean).join('\n');
}

function addMessageBubble(role: 'user' | 'assistant', text: string): HTMLElement {
  if (role === 'user') {
    const reminder = systemReminderText(text);
    if (reminder !== null) {
      const note = el('div', 'system-note', reminder);
      transcriptEl?.append(note);
      return note;
    }
  }
  const wrapper = el('div', `msg ${role}`);
  wrapper.append(el('div', 'role', role));
  const bubble = el('div', 'bubble');
  if (role === 'assistant') {
    bubble.classList.add('md');
    const body = el('div', 'md-body');
    renderMarkdownInto(body, text);
    bubble.append(body);
  } else {
    bubble.textContent = text;
  }
  wrapper.append(bubble);
  transcriptEl?.append(wrapper);
  return wrapper;
}

let transcriptStick = true;

/**
 * Pins the transcript to the bottom while content streams in. Stops following when the user
 * scrolls up to read (stick turns off), resumes when they return to the bottom; `force` is for
 * explicit user actions like sending a message.
 */
function scrollTranscript(force = false): void {
  if (!transcriptEl) return;
  if (force) transcriptStick = true;
  if (!transcriptStick) return;
  transcriptEl.scrollTop = transcriptEl.scrollHeight;
}

function appendNotice(text: string, kind = '', parent?: HTMLElement): void {
  const notice = el('div', `notice ${kind}`, text);
  (parent ?? transcriptEl)?.append(notice);
}

async function sendCurrent(): Promise<void> {
  const text = composerInput?.value ?? '';
  if (!text.trim()) return;
  if (!state.sessionId && !(await startNewSession(text.trim()))) return;
  composerInput!.value = '';
  composerAutoGrow?.();
  addMessageBubble('user', text.trim());
  scrollTranscript(true);
  resetStreaming();
  turnClock = { startedAt: Date.now(), tick: null, chip: null };
  const sent = await call('session/send', { sessionId: state.sessionId, parts: [{ type: 'text', text: text.trim() }] });
  if (!sent) stopTurnClock();
}

// ---------- live events ----------

function ensureStreaming(): NonNullable<typeof streaming> {
  if (!streaming || !transcriptEl) {
    const wrapper = el('div', 'msg assistant');
    wrapper.append(el('div', 'role', 'assistant'));
    const bubble = el('div', 'bubble md');
    const body = el('div', 'md-body');
    bubble.append(body);
    wrapper.append(bubble);
    transcriptEl?.append(wrapper);
    streaming = { text: '', bubble, body, reasoning: '', reasoningPre: null, reasoningToggle: null, reasoningTick: null, reasoningLiveStart: null, chips: null, panels: null, toolsPanel: null, toolsToggle: null, skillsToggle: null, skillsPanel: null, skillUses: null };
    ensureAssistBlock(streaming);
    scrollTranscript();
  }
  return streaming;
}

let streamingRenderQueued = false;

/** Re-render the markdown body at most once per frame while text streams in. */
function renderStreamingMarkdown(current: NonNullable<typeof streaming>): void {
  if (streamingRenderQueued) return;
  streamingRenderQueued = true;
  requestAnimationFrame(() => {
    streamingRenderQueued = false;
    renderMarkdownInto(current.body, current.text);
    scrollTranscript();
  });
}

/**
 * Tools chip spins while any tool in this message still runs. Recomputed from the DOM so it
 * stays right across turns, cancellations, and interleaved text.
 */
function refreshToolsBusy(sample: HTMLElement | null | undefined): void {
  const msg = sample?.closest('.msg');
  const chip = msg?.querySelector('.tools-chip');
  if (!chip) return;
  chip.classList.toggle('busy', Boolean(msg?.querySelector('.tool-entry .tool-status.running')));
}

/** A tool still "running" at turn end was interrupted (cancel/deny) — no result will arrive. */
function resolveStaleToolStatuses(wrapper: HTMLElement | null): void {
  if (!wrapper) return;
  for (const status of wrapper.querySelectorAll('.tool-status.running')) {
    status.textContent = 'cancelled';
    status.className = 'tool-status';
  }
  refreshToolsBusy(wrapper);
}

/** The chips row stays pinned at the top of the bubble; expanded content renders below it. */
function ensureAssistBlock(current: NonNullable<typeof streaming>): { chips: HTMLElement; panels: HTMLElement } {
  if (!current.chips || !current.panels) {
    const bubble = current.bubble;
    current.chips = el('div', 'assist-chips');
    current.panels = el('div', 'assist-panels');
    bubble.prepend(current.panels);
    bubble.prepend(current.chips);
    ensureTurnStatusChip(current);
  }
  return { chips: current.chips, panels: current.panels };
}

/** The turn status chip sits first in the row: spins while the whole turn runs, freezes with the total. */
function ensureTurnStatusChip(current: NonNullable<typeof streaming>): void {
  const clock = activeTurnClock();
  if (clock.chip) return;
  const chip = el('span', 'assist-chip turn-status busy');
  clock.chip = chip;
  current.chips?.prepend(chip);
  startTurnTick(chip, clock);
}

function createAssistToggle(label: string): AssistToggle {
  const chip = el('button', 'assist-chip') as HTMLButtonElement;
  const panel = el('div', 'assist-panel');
  panel.hidden = true;
  let base = label;
  const render = () => {
    chip.textContent = `${panel.hidden ? '▸' : '▾'} ${base}`;
  };
  chip.onclick = () => {
    panel.hidden = !panel.hidden;
    chip.classList.toggle('active', !panel.hidden);
    render();
  };
  const setLabel = (next: string) => {
    base = next;
    render();
  };
  setLabel(label);
  return { chip, panel, setLabel };
}

function formatSeconds(ms: number): string {
  const seconds = ms / 1000;
  return seconds >= 10 ? `${Math.round(seconds)}s` : `${Math.round(seconds * 10) / 10}s`;
}

/**
 * Desktop stays shallow: tool calls accumulate behind one "Tool use (n)" chip next to the
 * thinking chip instead of one transcript row per call; each tool is a single expandable line.
 */
function ensureToolPanel(current: NonNullable<typeof streaming>): HTMLElement {
  if (!current.toolsPanel) {
    const { chips, panels } = ensureAssistBlock(current);
    const toggle = createAssistToggle('Tool use');
    toggle.chip.classList.add('tools-chip');
    current.toolsToggle = toggle;
    current.toolsPanel = toggle.panel;
    chips.append(toggle.chip);
    panels.append(toggle.panel);
  }
  return current.toolsPanel;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return ms >= 10_000 ? `${Math.round(ms / 1000)}s` : `${(ms / 1000).toFixed(1)}s`;
}

/** Collapsed-line summary so the user can tell what a tool call did without expanding it. */
function toolSummary(argumentsText: unknown): string {
  try {
    const parsed = typeof argumentsText === 'string' ? JSON.parse(argumentsText) : argumentsText;
    if (parsed && typeof parsed === 'object') {
      const args = parsed as Record<string, unknown>;
      if (typeof args.description === 'string' && args.description.trim()) return args.description.trim().slice(0, 120);
      for (const [key, value] of Object.entries(args)) {
        if (typeof value === 'string' && value.trim()) return `${key}: ${value.trim().slice(0, 100)}`;
        if (typeof value === 'number' || typeof value === 'boolean') return `${key}: ${String(value)}`;
      }
    }
  } catch {
    return '';
  }
  return '';
}

function appendToolStart(event: any): void {
  const current = ensureStreaming();
  const panel = ensureToolPanel(current);
  const tool: UiTool = { callId: event.callId, name: event.toolName ?? 'tool', status: 'running' };
  const entry = el('div', 'tool-entry');
  const head = el('button', 'tool-entry-head') as HTMLButtonElement;
  const chevron = el('span', 'tool-chevron', '▸');
  head.append(chevron, el('span', 'tool-name', tool.name), el('span', 'tool-desc', toolSummary(event.arguments)));
  tool.durationNode = el('span', 'tool-duration');
  tool.statusNode = el('span', 'tool-status running', 'running');
  head.append(tool.durationNode, tool.statusNode);
  const body = el('div', 'tool-entry-body');
  body.hidden = true;
  body.append(el('pre', undefined, prettyJson(event.arguments)));
  tool.resultNode = el('pre');
  tool.resultNode.style.display = 'none';
  body.append(tool.resultNode);
  head.onclick = () => {
    body.hidden = !body.hidden;
    chevron.textContent = body.hidden ? '▸' : '▾';
  };
  entry.append(head, body);
  tool.node = entry;
  panel.append(entry);
  refreshToolsBusy(entry);
  current.toolsToggle?.setLabel(`Tool use (${panel.childElementCount})`);
  if (tool.name === 'use_skill') {
    recordSkillUse(current, event.arguments);
  }
  state.toolMap.set(tool.callId, tool);
  scrollTranscript();
}

function skillNameFrom(argumentsText: unknown): string | null {
  try {
    const parsed = typeof argumentsText === 'string' ? JSON.parse(argumentsText) : argumentsText;
    if (parsed && typeof parsed === 'object') {
      const value = (parsed as Record<string, unknown>).name;
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
  } catch {
    return null;
  }
  return null;
}

/** "Skill use (n)" chip next to thinking / tool use; expanding lists which skills were loaded. */
function recordSkillUse(current: NonNullable<typeof streaming>, argumentsText: unknown): void {
  const skill = skillNameFrom(argumentsText);
  if (!skill) return;
  if (!current.skillsPanel) {
    const { chips, panels } = ensureAssistBlock(current);
    const toggle = createAssistToggle('Skill use');
    toggle.chip.classList.add('skills-chip');
    current.skillsToggle = toggle;
    current.skillsPanel = toggle.panel;
    chips.append(toggle.chip);
    panels.append(toggle.panel);
    current.skillUses = new Map();
  }
  const uses = current.skillUses as Map<string, { node: HTMLElement; count: number }>;
  const existing = uses.get(skill);
  if (existing) {
    existing.count += 1;
    existing.node.textContent = `${skill} · ×${existing.count}`;
  } else {
    const node = el('div', 'skill-row', skill);
    current.skillsPanel.append(node);
    uses.set(skill, { node, count: 1 });
  }
  current.skillsToggle?.setLabel(`Skill use (${uses.size})`);
}

/** Where a memory report lands: the live message, else the newest assistant message on screen. */
function memoryWrapper(): HTMLElement | null {
  if (streaming) return streaming.bubble.parentElement;
  const messages = transcriptEl?.querySelectorAll('.msg.assistant');
  return messages && messages.length ? (messages[messages.length - 1] as HTMLElement) : null;
}

/** Creates the chips row and panel for a message that has none yet (history-loaded messages). */
function ensureMemoryUi(wrapper: HTMLElement): MemoryUi {
  const existing = memoryUi.get(wrapper);
  if (existing) return existing;
  let chips = wrapper.querySelector('.assist-chips') as HTMLElement | null;
  let panels = wrapper.querySelector('.assist-panels') as HTMLElement | null;
  if (!chips || !panels) {
    const bubble = (wrapper.querySelector('.bubble') as HTMLElement | null) ?? wrapper;
    panels = el('div', 'assist-panels');
    chips = el('div', 'assist-chips');
    bubble.prepend(panels);
    bubble.prepend(chips);
  }
  const toggle = createAssistToggle('Memory');
  toggle.chip.classList.add('memory-chip');
  chips.append(toggle.chip);
  panels.append(toggle.panel);
  const list = el('div', 'memory-runs');
  toggle.panel.append(list);
  const ui: MemoryUi = { toggle, list, runs: [] };
  memoryUi.set(wrapper, ui);
  return ui;
}

function memoryRunMeta(run: MemoryRun): string {
  const parts: string[] = [run.trigger === 'EXPLICIT' ? 'requested' : 'automatic'];
  if (run.running) {
    parts.push('running…');
  } else if (run.durationMs != null) {
    parts.push(formatDuration(run.durationMs));
  }
  if (!run.running && run.cursor != null) parts.push(`cursor ${run.cursor}`);
  return `Memory extraction · ${parts.join(' · ')}`;
}

function memoryRow(action: 'added' | 'updated', path: string): HTMLElement {
  const row = el('div', 'memory-row');
  row.append(el('span', `memory-badge ${action}`, action));
  row.append(el('span', 'memory-path', path));
  return row;
}

function renderMemoryRuns(ui: MemoryUi): void {
  ui.list.replaceChildren();
  for (const run of ui.runs) {
    const block = el('div', 'memory-run');
    block.append(el('div', 'memory-meta', memoryRunMeta(run)));
    if (run.running) {
      block.append(el('div', 'memory-empty', 'Extracting…'));
    } else if (!run.added.length && !run.updated.length) {
      block.append(el('div', 'memory-empty', 'No knowledge changes.'));
    } else {
      for (const path of run.added) block.append(memoryRow('added', path));
      for (const path of run.updated) block.append(memoryRow('updated', path));
    }
    if (run.note) {
      block.append(el('div', 'memory-section', 'Summary'));
      block.append(el('pre', 'memory-note', run.note));
    }
    ui.list.append(block);
  }
  const extracted = ui.runs.reduce((sum, run) => sum + run.added.length + run.updated.length, 0);
  const running = ui.runs.some((run) => run.running);
  ui.toggle.setLabel(extracted > 0 && !running ? `Memory (${extracted})` : 'Memory');
  ui.toggle.chip.classList.toggle('busy', running);
}

function memoryPathList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

/**
 * Engine event for a memory extraction run: the chip appears when the run starts and is filled
 * with the kept knowledge files when it completes.
 */
function handleMemoryEvent(event: any): void {
  let data: any;
  try {
    data = JSON.parse(event.data ?? '{}');
  } catch {
    return;
  }
  const runId = String(data.runId ?? '');
  const phase = String(data.phase ?? '').toUpperCase();
  const trigger = String(data.trigger ?? '').toUpperCase();
  if (phase === 'STARTED') {
    const wrapper = memoryWrapper();
    if (!wrapper) return;
    const ui = ensureMemoryUi(wrapper);
    const run: MemoryRun = {
      runId,
      trigger,
      running: true,
      durationMs: null,
      cursor: null,
      added: [],
      updated: [],
      note: null,
    };
    ui.runs.push(run);
    pendingMemoryRun = { wrapper, ui, run };
    renderMemoryRuns(ui);
    scrollTranscript();
    return;
  }
  if (phase !== 'COMPLETED') return;
  let entry = pendingMemoryRun && pendingMemoryRun.run.runId === runId ? pendingMemoryRun : null;
  // the message the run started on is gone (session reopened): attach to the current last message
  if (entry && !entry.wrapper.isConnected) entry = null;
  if (!entry) {
    const wrapper = memoryWrapper();
    if (!wrapper) return;
    const ui = ensureMemoryUi(wrapper);
    const run: MemoryRun = {
      runId,
      trigger: '',
      running: true,
      durationMs: null,
      cursor: null,
      added: [],
      updated: [],
      note: null,
    };
    ui.runs.push(run);
    entry = { wrapper, ui, run };
  }
  const run = entry.run;
  run.running = false;
  run.trigger = trigger || run.trigger;
  run.durationMs = typeof data.durationMs === 'number' ? data.durationMs : null;
  run.cursor = typeof data.cursor === 'number' && data.cursor >= 0 ? data.cursor : null;
  run.added = memoryPathList(data.added);
  run.updated = memoryPathList(data.updated);
  run.note = typeof data.note === 'string' && data.note ? data.note : null;
  if (pendingMemoryRun === entry) pendingMemoryRun = null;
  renderMemoryRuns(entry.ui);
  scrollTranscript();
}

function updateToolResult(event: any): void {
  const tool = state.toolMap.get(event.callId);
  if (!tool) return;
  tool.status = event.status ?? 'done';
  if (tool.durationNode && typeof event.durationMs === 'number') {
    tool.durationNode.textContent = formatDuration(event.durationMs);
  }
  if (tool.statusNode) {
    tool.statusNode.textContent = tool.status;
    tool.statusNode.className = `tool-status ${tool.status === 'success' ? 'success' : tool.status === 'failure' ? 'failure' : ''}`;
  }
  refreshToolsBusy(tool.node);
  if (tool.resultNode && event.result) {
    tool.resultNode.textContent = prettyJson(event.result);
    tool.resultNode.style.display = 'block';
  }
  if (state.pendingApproval?.callId === event.callId) removeApprovalBar();
  scrollTranscript();
}

function showApprovalBar(event: any): void {
  removeApprovalBar();
  state.pendingApproval = { callId: event.callId, toolName: event.toolName };
  approvalBar = el('div', 'approval-bar');
  approvalBar.append(el('div', 'approval-title', `⚠ ${event.toolName} needs approval${event.suggestedPattern ? ` · suggested rule ${event.suggestedPattern}` : ''}`));
  approvalBar.append(el('pre', undefined, prettyJson(event.arguments)));
  const actions = el('div', 'approval-actions');
  const buttons: Array<[string, string]> = [
    ['Allow once', 'APPROVE'],
    ['Allow for session', 'APPROVE_SESSION'],
    ['Always allow', 'APPROVE_ALWAYS'],
    ['Deny', 'DENY'],
    ['Always deny', 'DENY_ALWAYS'],
  ];
  for (const [label, decision] of buttons) {
    const button = el('button', `btn small${decision.startsWith('DENY') ? ' danger' : ''}`, label);
    button.onclick = async () => {
      for (const sibling of actions.querySelectorAll('button')) (sibling as HTMLButtonElement).disabled = true;
      await call('session/approve', { sessionId: state.sessionId, callId: event.callId, decision });
      removeApprovalBar();
    };
    actions.append(button);
  }
  approvalBar.append(actions);
  const chat = transcriptEl?.parentElement;
  chat?.insertBefore(approvalBar, chat.querySelector('.composer'));
}

function removeApprovalBar(): void {
  approvalBar?.remove();
  approvalBar = null;
  state.pendingApproval = null;
}

function setStatus(status: string): void {
  state.status = status.toLowerCase();
  if (statusChip) {
    statusChip.textContent = state.status;
    statusChip.className = `composer-status ${state.status}`;
  }
  if (stopButton) {
    stopButton.disabled = state.status !== 'running';
  }
}

/** Updates the sidebar row of whichever session the event belongs to; the viewed one drives the chip. */
function handleStatusEvent(event: any): void {
  const status = String(event.status ?? 'idle').toLowerCase();
  let rowChanged = false;
  if (event.sessionId) {
    const row = state.sessions.find((session) => session.sessionId === event.sessionId);
    if (row && row.status !== status) {
      row.status = status;
      rowChanged = true;
    }
  }
  if (event.sessionId === state.sessionId) {
    setStatus(status);
  }
  if (rowChanged) {
    renderSessionItems();
  }
}

/** Mirrors the CLI turn summary format: <60s "42s", then "1m 05s". */
function formatElapsed(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

/** Mirrors the CLI cost format: trailing zeros trimmed ($0.003, $1.25). */
function formatCostUsd(cost: number): string {
  return `$${cost.toFixed(6).replace(/0+$/, '').replace(/\.$/, '')}`;
}

function handleSessionEvent(event: any): void {
  if (!event) return;
  // status changes are tracked for EVERY session in this workspace, so the sidebar can
  // spin the rows of sessions still working while the user looks at another one
  if (event.type === 'status_change') {
    handleStatusEvent(event);
    return;
  }
  if (event.sessionId && event.sessionId !== state.sessionId) return;
  switch (event.type) {
    case 'text_chunk': {
      const current = ensureStreaming();
      current.text += event.chunk ?? '';
      renderStreamingMarkdown(current);
      break;
    }
    case 'reasoning_chunk': {
      const current = ensureStreaming();
      current.reasoning += event.chunk ?? '';
      if (current.reasoningLiveStart === null) current.reasoningLiveStart = Date.now();
      if (!current.reasoningPre) {
        const { chips, panels } = ensureAssistBlock(current);
        const toggle = createAssistToggle('Thinking…');
        current.reasoningToggle = toggle;
        current.reasoningPre = el('div', 'reasoning');
        toggle.panel.append(current.reasoningPre);
        // the status chip stays first in the row; the thinking chip slots in right after it
        const statusChip = chips.querySelector('.turn-status');
        if (statusChip) chips.insertBefore(toggle.chip, statusChip.nextSibling);
        else chips.prepend(toggle.chip);
        panels.prepend(toggle.panel);
      }
      startReasoningTick(current);
      current.reasoningPre.textContent = current.reasoning;
      scrollTranscript();
      break;
    }
    case 'reasoning_complete': {
      const current = streaming;
      if (!current) break;
      stopReasoningTick(current);
      if (typeof event.durationMs === 'number') {
        current.reasoningToggle?.setLabel(`Thinking · ${formatSeconds(event.durationMs)}`);
      }
      if (typeof event.reasoning === 'string' && event.reasoning && current.reasoningPre) {
        current.reasoning = event.reasoning;
        current.reasoningPre.textContent = current.reasoning;
      }
      break;
    }
    case 'tool_start':
      if (state.pendingApproval) removeApprovalBar();
      maybeAutoStartBrowser(event);
      appendToolStart(event);
      break;
    case 'tool_result':
      updateToolResult(event);
      break;
    case 'tool_approval_request':
      showApprovalBar(event);
      break;
    case 'turn_complete': {
      const assistantWrapper = streaming?.bubble.parentElement ?? null;
      resolveStaleToolStatuses(assistantWrapper);
      finishTurnStatus(event);
      resetStreaming();
      removeApprovalBar();
      const parts: string[] = [];
      if (event.cancelled) parts.push('turn cancelled');
      if (event.max_turns_reached) parts.push('max turns reached');
      const bits: string[] = [];
      if (typeof event.duration_ms === 'number') bits.push(formatElapsed(event.duration_ms));
      if (event.input_tokens || event.output_tokens) {
        const input = Number(event.input_tokens ?? 0);
        const output = Number(event.output_tokens ?? 0);
        const cached = Number(event.cached_tokens ?? 0);
        bits.push(`${(input + output).toLocaleString()} tokens (↑ ${input.toLocaleString()} ↓ ${output.toLocaleString()}${cached > 0 ? ` ~${cached.toLocaleString()}` : ''})`);
      }
      if (event.cost_usd) bits.push(formatCostUsd(Number(event.cost_usd)));
      if (bits.length) parts.push(`✦ ${bits.join(' | ')}`);
      if (parts.length) {
        const summary = el('div', 'turn-summary', parts.join(' · '));
        (assistantWrapper ?? transcriptEl)?.append(summary);
        scrollTranscript();
      }
      void refreshSessionList();
      void loadSummaries();
      if (state.pendingEngineRestart) {
        state.pendingEngineRestart = false;
        void restartWorkspaceEngine();
      }
      break;
    }
    case 'compression':
      if (event.completed !== false) {
        appendNotice(`✦ Context compressed ${event.before_count} → ${event.after_count} · ${event.context_tokens ?? '?'} / ${event.max_context_tokens ?? '?'} tokens`, 'compression');
      }
      break;
    case 'plan_update': {
      const block = el('div', 'plan-block');
      block.append(el('div', 'plan-title', 'plan'));
      for (const todo of event.todos ?? []) {
        block.append(el('div', 'plan-item', `• ${todo.content ?? todo.text ?? JSON.stringify(todo)} ${todo.status ? `(${todo.status})` : ''}`));
      }
      transcriptEl?.append(block);
      scrollTranscript();
      break;
    }
    case 'custom':
      handleCustomEvent(event);
      break;
    case 'task_status':
      appendNotice(`task ${event.toolName ?? ''}: ${event.status ?? ''}`);
      break;
    case 'environment_output_chunk': {
      const tool = state.toolMap.get(event.callId);
      if (tool?.resultNode) {
        tool.resultNode.style.display = 'block';
        tool.resultNode.textContent += event.chunk ?? '';
      }
      break;
    }
    case 'error':
      appendNotice(`✗ ${event.message ?? 'error'}`, 'error');
      break;
    default:
      break;
  }
}

function handleCustomEvent(event: any): void {
  if (event.name === 'memory') {
    handleMemoryEvent(event);
    return;
  }
  if (event.name !== 'quick_replies') return;
  let payload: any;
  try {
    payload = JSON.parse(event.data ?? '{}');
  } catch {
    return;
  }
  const options: any[] = Array.isArray(payload.options) ? payload.options : [];
  if (!options.length) return;
  const row = el('div', 'quick-replies');
  for (const option of options) {
    const label = typeof option === 'string' ? option : (option.label ?? option.text ?? option.value ?? '');
    const value = typeof option === 'string' ? option : (option.value ?? option.label ?? option.text ?? '');
    if (!label) continue;
    const button = el('button', 'btn small', String(label));
    button.onclick = () => {
      if (composerInput) {
        composerInput.value = String(value);
        void sendCurrent();
      }
    };
    row.append(button);
  }
  transcriptEl?.append(row);
}

// ---------- panels ----------

function panel(title: string, hint: string): { root: HTMLElement; body: HTMLElement } {
  const root = el('div', 'panel');
  root.append(el('h2', undefined, title));
  root.append(el('div', 'hint', hint));
  const body = el('div');
  root.append(body);
  return { root, body };
}

async function renderArtifactsPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Artifacts', 'Local outputs: .core-ai/media (images/videos) and .core-ai/tasks (sub-agent output)');
  const listed = await call('artifacts/list', { limit: 200 });
  if (!listed) return root;
  const files: any[] = listed.files ?? [];
  if (!files.length) {
    body.append(el('div', 'notice', 'No artifacts yet.'));
    return root;
  }
  const table = el('table');
  const headRow = el('tr');
  for (const label of ['Name', 'Kind', 'Size', 'Modified', '']) headRow.append(el('th', undefined, label));
  table.append(headRow);
  for (const file of files) {
    const row = el('tr');
    row.append(el('td', undefined, file.name));
    row.append(el('td', undefined, file.kind));
    row.append(el('td', undefined, fmtBytes(file.size)));
    row.append(el('td', undefined, fmtTime(file.createdAt)));
    const actions = el('td');
    const open = el('button', 'btn small', 'Open');
    open.onclick = async () => {
      const error = await window.coreai.openPath(state.workspace as string, file.path);
      if (error) toast(error, 'error');
    };
    const reveal = el('button', 'btn small', 'Reveal');
    reveal.style.marginLeft = '6px';
    reveal.onclick = () => window.coreai.revealPath(state.workspace as string, file.path);
    actions.append(open, reveal);
    row.append(actions);
    table.append(row);
  }
  body.append(table);
  return root;
}

async function renderSkillsPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Skills', 'Workspace and user-level skills (~/.core-ai/skills and {workspace}/.core-ai/skills)');
  const listed = await call('skills/list', {});
  if (!listed) return root;
  const skills: any[] = listed.skills ?? [];
  if (!skills.length) {
    body.append(el('div', 'notice', 'No skills yet.'));
    return root;
  }
  const table = el('table');
  const headRow = el('tr');
  for (const label of ['Name', 'Source', 'Path']) headRow.append(el('th', undefined, label));
  table.append(headRow);
  for (const skill of skills) {
    const row = el('tr');
    row.append(el('td', undefined, skill.name));
    row.append(el('td', undefined, skill.source));
    row.append(el('td', 'mono', skill.path));
    table.append(row);
  }
  body.append(table);
  return root;
}

async function renderMemoryPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Memory', 'Knowledge wiki / episodes / daily-logs ({workspace}/.core-ai)');
  const listed = await call('memory/list', { limit: 300 });
  if (!listed) return root;
  const files: any[] = listed.files ?? [];
  const layout = el('div', 'memory-list');
  const items = el('div', 'file-items');
  const preview = el('div', 'file-preview');
  preview.append(el('div', 'notice', 'Select a file on the left to preview'));
  for (const file of files) {
    const item = el('button', 'file-item');
    item.append(el('span', 'path', file.path));
    item.append(el('span', 'meta', `${fmtBytes(file.size)} · ${fmtTime(file.modifiedAt)}`));
    item.onclick = async () => {
      const read = await call('memory/read', { path: file.path });
      if (!read) return;
      preview.replaceChildren(el('pre', undefined, read.content ?? ''));
    };
    items.append(item);
  }
  layout.append(items, preview);
  body.append(layout);
  return root;
}

async function renderSettingsPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Settings', 'Engine, approval policy and permission rules');
  const info = state.info ?? (await window.coreai.info());
  state.info = info;
  const grid = el('dl', 'settings-grid');
  const rows: Array<[string, string]> = [
    ['Workspace', state.workspace ? workspaceLabel(state.workspace) : 'default (not created yet)'],
    ['Engine path', `${info.enginePath}${info.engineExists ? '' : '  (missing — run :core-ai-cli:installDist)'}`],
    ['Desktop', `v${info.desktopVersion} (electron ${info.electron}, node ${info.node})`],
    ['Settings file', info.userData],
  ];
  for (const [key, value] of rows) {
    grid.append(el('dt', undefined, key));
    grid.append(el('dd', undefined, value));
  }
  grid.append(el('dt', undefined, 'Approval policy'));
  const policy = el('select');
  for (const value of ['ask', 'workspace-auto', 'full']) {
    const option = el('option', undefined, value);
    option.value = value;
    policy.append(option);
  }
  policy.value = state.approvalPolicy;
  policy.onchange = async () => {
    state.approvalPolicy = policy.value;
    await window.coreai.setSettings({ approvalPolicy: policy.value });
    toast(`Approval policy set to ${policy.value} (the engine restarts to apply it)`);
  };
  const policyCell = el('dd');
  policyCell.append(policy);
  grid.append(policyCell);
  body.append(grid);

  const permissions = state.workspace ? await call('permissions/get', {}) : null;
  if (permissions) {
    body.append(el('h2', undefined, 'Permission rules'));
    body.append(el('div', 'hint', `allow ${(permissions.allow ?? []).length} · deny ${(permissions.deny ?? []).length} (editing comes in a later batch)`));
    const pre = el('pre');
    pre.style.fontFamily = 'var(--mono)';
    pre.style.fontSize = '12px';
    pre.textContent = `allow:\n${(permissions.allow ?? []).join('\n')}\n\ndeny:\n${(permissions.deny ?? []).join('\n')}`;
    body.append(pre);
  }
  return root;
}

// ---------- content router ----------

let contentRenderToken = 0;

async function renderContent(): Promise<void> {
  const token = ++contentRenderToken;
  const content = document.getElementById('content') as HTMLElement;
  const info = state.info ?? (await window.coreai.info());
  state.info = info;
  // Panels load asynchronously; a render the user already navigated away from must not win.
  if (token !== contentRenderToken) return;

  if (!info.engineExists) {
    content.replaceChildren();
    const empty = el('div', 'empty-state');
    empty.append(el('div', 'big', 'core-ai desktop'));
    empty.append(el('div', 'notice error', `Engine not found: ${info.enginePath} — run .\\gradlew.bat :core-ai-cli:installDist first`));
    content.append(empty);
    return;
  }

  if (!state.workspace) {
    if (state.nav === 'sessions') {
      if (!chatView) buildChatView();
      content.replaceChildren(chatView as HTMLElement);
      updateChatHeader();
      showBlankHint();
      return;
    }
    content.replaceChildren(virtualDefaultPanel());
    return;
  }

  if (state.nav === 'sessions') {
    if (!chatView) buildChatView();
    content.replaceChildren(chatView as HTMLElement);
    updateChatHeader();
    showBlankHint();
    scrollTranscript();
    return;
  }
  let panel: HTMLElement;
  if (state.nav === 'artifacts') {
    panel = await renderArtifactsPanel();
  } else if (state.nav === 'skills') {
    panel = await renderSkillsPanel();
  } else if (state.nav === 'memory') {
    panel = await renderMemoryPanel();
  } else {
    panel = await renderSettingsPanel();
  }
  if (token !== contentRenderToken) return;
  content.replaceChildren(panel);
}

// ---------- bootstrap ----------

async function bootstrap(): Promise<void> {
  state.info = await window.coreai.info();
  const settings = await window.coreai.getSettings();
  state.approvalPolicy = settings?.approvalPolicy ?? 'ask';
  state.workspaces = settings?.workspaces ?? [];
  state.colorScheme = normalizeColorScheme(settings?.colorScheme);
  try {
    state.auth = await window.coreai.authStatus();
  } catch {
    state.auth = null;
  }
  await loadSummaries();
  renderSidebar();
  renderComposerControls();

  window.addEventListener('focus', () => {
    void refreshAuth();
  });

  window.addEventListener('resize', () => {
    scheduleBrowserBounds();
  });

  window.coreai.onEvent((payload: any) => {
    if (!payload || payload.workspace !== state.workspace) return;
    if (payload.method === 'session/event') {
      handleSessionEvent(payload.params?.event);
    } else if (payload.method === 'engine/ready') {
      setStatus('idle');
    }
  });

  window.coreai.onBrowserState((status: BrowserStatus) => {
    const wasRunning = Boolean(state.browserStatus?.running);
    applyBrowserStatus(status);
    // Covers a browser the main process started on its own (autostart): when it becomes
    // running, open the pane so the view gets laid out (bounds) and the user can watch.
    if (status?.running && !wasRunning && !state.browserPanelOpen) {
      state.browserPanelOpen = true;
      renderBrowserPanel();
    }
  });
  window.coreai.onBrowserNeedPanel(() => {
    // The agent attached to a tab the view tree cannot lay out while the panel is hidden.
    if (!state.browserStatus?.running) return;
    if (!state.browserPanelOpen) {
      state.browserPanelOpen = true;
      renderBrowserPanel();
    } else {
      scheduleBrowserBounds();
    }
  });
  void window.coreai.browserStatus().then((status) => {
    applyBrowserStatus(status);
    // A browser that was already running when the window loaded (e.g. autostart) opens the pane.
    if (status?.running && !state.browserPanelOpen) {
      state.browserPanelOpen = true;
      renderBrowserPanel();
    }
  }).catch(() => undefined);

  if (!state.info?.engineExists) {
    await renderContent();
    return;
  }
  state.defaultExists = Boolean(state.info?.defaultExists);
  const startup = state.info?.startupWorkspace as string | null;
  if (startup) {
    await connect(startup);
  } else {
    await enterDefaultMode();
  }
}

void bootstrap();
