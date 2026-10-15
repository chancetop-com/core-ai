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
  rewriteLocalImageSources(body);
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

interface ComputerStep {
  step: string;
  ok: boolean;
  detail: string;
}

interface ComputerStatus {
  enabled: boolean;
  ready: boolean;
  problems: string[];
  cli: { present: boolean; path: string | null; version: string | null };
  consent: boolean;
  host: { configured: boolean; installed: boolean; running: boolean; mode: string | null; directUrl: string | null };
  target: { host: boolean };
  token: boolean;
  skill: { installed: boolean; managed: boolean; upToDate: boolean; detail: string; targetDir: string };
}

interface UpdateState {
  phase: 'idle' | 'checking' | 'up-to-date' | 'available' | 'downloading' | 'installing' | 'ready' | 'error';
  currentVersion: string;
  latestVersion: string | null;
  releaseUrl: string | null;
  installMode: 'install' | 'download' | 'manual';
  assetName: string | null;
  transferred: number;
  total: number;
  error: string | null;
  devMode: boolean;
}

interface PendingAttachment {
  path: string;
  name: string;
  kind: 'image' | 'file' | 'pdf';
  size: number;
  preview?: string | null;
}

interface StagedAttachmentResult {
  attachments: PendingAttachment[];
  skipped: Array<{ name: string; reason: string }>;
}

/** One local artifact in the right dock: generated media (event live) or a scanned workspace file (baseline). */
interface ArtifactEntry {
  path: string;
  name: string;
  kind: string;
  size: number;
  contentType: string | null;
  createdAt: string | null;
  tool: string | null;
}

const ARTIFACT_CONTENT_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mov': 'video/quicktime',
  '.pdf': 'application/pdf',
  '.html': 'text/html',
  '.htm': 'text/html',
  '.md': 'text/markdown',
  '.json': 'application/json',
  '.txt': 'text/plain',
  '.log': 'text/plain',
  '.csv': 'text/csv',
  '.xml': 'application/xml',
};

function artifactContentType(entry: ArtifactEntry): string {
  if (entry.contentType) return entry.contentType;
  const dot = entry.name.lastIndexOf('.');
  const extension = dot >= 0 ? entry.name.slice(dot).toLowerCase() : '';
  return ARTIFACT_CONTENT_TYPES[extension] ?? 'application/octet-stream';
}

function baseName(path: string): string {
  const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  return slash >= 0 ? path.slice(slash + 1) : path;
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
  engineCapabilities(workspace: string): Promise<string[]>;
  pickAttachments(): Promise<{ paths: string[] }>;
  stageAttachments(sessionId: string, paths: string[]): Promise<StagedAttachmentResult>;
  removeAttachment(stagedPath: string): Promise<void>;
  artifactBase(workspace: string): Promise<{ baseUrl: string }>;
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
  computerStatus(): Promise<ComputerStatus | null>;
  computerInstallCli(): Promise<{ ok: boolean; detail: string }>;
  computerEnable(): Promise<{ ok: boolean; steps: ComputerStep[] }>;
  computerDisable(): Promise<ComputerStatus | null>;
  computerRemoveService(): Promise<{ ok: boolean; detail: string }>;
  updateStatus(): Promise<UpdateState | null>;
  updateCheck(): Promise<UpdateState | null>;
  updateInstall(): Promise<UpdateState | null>;
  onUpdateState(callback: (state: UpdateState) => void): () => void;
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

type NavKey = 'sessions' | 'hub' | 'artifacts' | 'skills' | 'memory' | 'schedules' | 'settings';

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

/**
 * One row of the message's "Tasks" chip: a background task (launched through task / run_bash_command
 * with run_in_background) whose status outlives the turn, or a scheduled-task creation (a static
 * record — a schedule fires later or on another day, so it never "runs" in the conversation).
 */
interface UiTask {
  status: string;
  node: HTMLElement;
  statusNode: HTMLElement;
}

const SECTION_PAGE_SIZE = 10;
const COLLAPSE_KEY = 'coreai.sessionsCollapsed';

const NAV: Array<{ key: NavKey; label: string; icon: keyof typeof ICONS }> = [
  { key: 'sessions', label: 'Sessions', icon: 'sessions' },
  { key: 'artifacts', label: 'Artifacts', icon: 'artifacts' },
  { key: 'skills', label: 'Skills', icon: 'skills' },
  { key: 'memory', label: 'Memory', icon: 'memory' },
  { key: 'schedules', label: 'Scheduled tasks', icon: 'refresh' },
  { key: 'hub', label: 'Hub', icon: 'hub' },
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
  /** Sessions created here whose first turn has not saved yet — a listing must not drop them. */
  pendingSessionIds: Set<string>;
  sessionsCollapsed: boolean;
  sessionId: string | null;
  sessionTitle: string;
  status: string;
  models: Array<{ model: string; provider?: string; displayName?: string; reasoningEfforts?: string[] }>;
  modelSource: string | null;
  currentModel: string | null;
  /** Model picked in a blank window (no session yet); applied to the session the first message creates. */
  pendingModel: string | null;
  thinkingLevel: string;
  pendingEngineRestart: boolean;
  browserStatus: BrowserStatus | null;
  rightDock: 'none' | 'browser' | 'artifacts';
  dockFullscreen: boolean;
  browserStarting: boolean;
  artifactBase: string | null;
  artifacts: ArtifactEntry[];
  activeArtifactPath: string | null;
  artifactSource: boolean;
  artifactUnread: boolean;
  artifactDismissedForTurn: boolean;
  sidebarCollapsed: boolean;
  sidebarAutoCollapsed: boolean;
  colorScheme: string;
  update: UpdateState | null;
  capabilities: string[];
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
  pendingSessionIds: new Set(),
  sessionsCollapsed: localStorage.getItem(COLLAPSE_KEY) === '1',
  sessionId: null,
  sessionTitle: '',
  status: 'idle',
  models: [],
  modelSource: null,
  currentModel: null,
  pendingModel: null,
  thinkingLevel: 'off',
  pendingEngineRestart: false,
  browserStatus: null,
  rightDock: 'none',
  dockFullscreen: false,
  browserStarting: false,
  artifactBase: null,
  artifacts: [],
  activeArtifactPath: null,
  artifactSource: false,
  artifactUnread: false,
  artifactDismissedForTurn: false,
  sidebarCollapsed: false,
  sidebarAutoCollapsed: false,
  colorScheme: 'system',
  update: null,
  capabilities: [],
};

let chatView: HTMLElement | null = null;
let sessionItemsEl: HTMLElement | null = null;
let sessionStackEl: HTMLElement | null = null;
let composerInput: HTMLTextAreaElement | null = null;
let composerControlsEl: HTMLElement | null = null;
let composerAttachEl: HTMLElement | null = null;
let composerAutoGrow: (() => void) | null = null;
/** Attachments staged for the open session and not yet sent; cleared when the session changes. */
let pendingAttachments: PendingAttachment[] = [];
let chatTitleEl: HTMLElement | null = null;
let statusChip: HTMLElement | null = null;
let stopButton: HTMLButtonElement | null = null;
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
let browserFullscreenEl: HTMLButtonElement | null = null;
let artifactFullscreenEl: HTMLButtonElement | null = null;
let browserToggleEl: HTMLButtonElement | null = null;
let browserBoundsTimer: number | null = null;
let browserBoundsValue: string | null = null;
let browserResizeObserver: ResizeObserver | null = null;
let sidebarToggleEl: HTMLButtonElement | null = null;
let rightDockEl: HTMLElement | null = null;
let rightDockWasOpen = false;
let browserTabEl: HTMLButtonElement | null = null;
let artifactsTabEl: HTMLButtonElement | null = null;
let artifactsToggleEl: HTMLButtonElement | null = null;
let artifactPaneEl: HTMLElement | null = null;
let artifactGalleryEl: HTMLElement | null = null;
let artifactHeadEl: HTMLElement | null = null;
let artifactBodyEl: HTMLElement | null = null;
/** Artifacts produced by a tool call, keyed by callId, so the tool card can show a thumbnail. */
const artifactByCallId = new Map<string, ArtifactEntry>();
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

/** Tool calls with a known origin are listed under their own chip, mirroring "Skill use". */
type ToolUseKind = 'skill' | 'subagent' | 'mcp' | 'api' | 'agent';

interface ToolUseChip {
  toggle: AssistToggle;
  panel: HTMLElement;
  rows: Map<string, { node: HTMLElement; count: number }>;
}

interface StreamingState {
  view: SessionView;
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
  taskToggle: AssistToggle | null;
  toolUses: Map<ToolUseKind, ToolUseChip> | null;
}

/**
 * One session window: its own transcript DOM plus every piece of live turn state (streaming
 * bubble, turn clock, tool map, approval bar). Views stay alive off-screen so a session that is
 * still working keeps updating while the user looks at another one — switching only mounts and
 * unmounts the DOM, it never rebuilds turn state.
 */
interface SessionView {
  sessionId: string | null;
  root: HTMLElement;
  transcript: HTMLElement;
  stick: boolean;
  savedScrollTop: number | null;
  streaming: StreamingState | null;
  turnClock: TurnClock | null;
  toolMap: Map<string, UiTool>;
  taskMap: Map<string, UiTask>;
  pendingTaskStatus: Map<string, string>;
  approvalBar: HTMLElement | null;
  pendingApproval: { callId: string; toolName: string } | null;
  planBlock: HTMLElement | null;
  pendingMemoryRun: { wrapper: HTMLElement; ui: MemoryUi; run: MemoryRun } | null;
  status: string;
  historyLoaded: boolean;
  historyLoading: boolean;
  pendingEvents: any[];
}

/** The +New conversation has no engine session yet; its blank window is still a view. */
const DRAFT_VIEW_KEY = '';
const views = new Map<string, SessionView>();
let mountedView: SessionView | null = null;

function viewKey(sessionId: string | null): string {
  return sessionId ?? DRAFT_VIEW_KEY;
}

function findSessionView(sessionId: string | null): SessionView | null {
  return views.get(viewKey(sessionId)) ?? null;
}

function activeSessionView(): SessionView | null {
  return findSessionView(state.sessionId);
}

function createSessionView(sessionId: string | null): SessionView {
  const root = el('div', 'session-view');
  const transcript = el('div', 'transcript');
  root.append(transcript);
  const view: SessionView = {
    sessionId,
    root,
    transcript,
    stick: true,
    savedScrollTop: null,
    streaming: null,
    turnClock: null,
    toolMap: new Map(),
    taskMap: new Map(),
    pendingTaskStatus: new Map(),
    approvalBar: null,
    pendingApproval: null,
    planBlock: null,
    pendingMemoryRun: null,
    status: 'idle',
    // A draft window has nothing on disk; a real session loads its history before events apply.
    historyLoaded: sessionId == null,
    historyLoading: false,
    pendingEvents: [],
  };
  transcript.addEventListener('scroll', () => {
    const distance = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight;
    view.stick = distance < 60;
  });
  transcript.addEventListener('click', (event) => {
    const anchor = (event.target as HTMLElement).closest('a');
    if (!anchor) return;
    event.preventDefault();
    void window.coreai.openExternal(anchor.href);
  });
  views.set(viewKey(sessionId), view);
  return view;
}

function ensureSessionView(sessionId: string | null): SessionView {
  return findSessionView(sessionId) ?? createSessionView(sessionId);
}

/** Mounts a view (unmounting the previous one) and freezes idle off-screen views. */
function showSessionView(view: SessionView): void {
  if (mountedView !== view) {
    if (mountedView) {
      mountedView.savedScrollTop = mountedView.transcript.scrollTop;
      mountedView.root.remove();
    }
    mountedView = view;
  }
  if (sessionStackEl && view.root.parentElement !== sessionStackEl) sessionStackEl.append(view.root);
  applyComposerStatus(view.status);
  if (view.stick) view.transcript.scrollTop = view.transcript.scrollHeight;
  else if (view.savedScrollTop != null) view.transcript.scrollTop = view.savedScrollTop;
  sweepIdleViews(view);
}

function disposeSessionView(view: SessionView): void {
  if (view.streaming) stopReasoningTick(view.streaming);
  stopTurnClock(view);
  view.root.remove();
  if (views.get(viewKey(view.sessionId)) === view) views.delete(viewKey(view.sessionId));
  if (mountedView === view) mountedView = null;
}

/** Workspace switches drop every window: the engine behind them is gone with the workspace. */
function disposeAllSessionViews(): void {
  for (const view of [...views.values()]) disposeSessionView(view);
  mountedView = null;
}

/** Sessions without a turn in flight rebuild from history on reopen — only working ones are kept. */
function sweepIdleViews(keep: SessionView): void {
  for (const view of [...views.values()]) {
    if (view === keep || view === mountedView) continue;
    if (view.turnClock == null && view.status !== 'running' && !view.historyLoading) disposeSessionView(view);
  }
}

/** Live-only ticking while the model reasons; the final label is frozen from the engine duration. */
function startReasoningTick(current: StreamingState): void {
  if (current.reasoningTick != null) return;
  current.reasoningToggle?.chip.classList.add('busy');
  current.reasoningTick = setInterval(() => {
    if (current.reasoningLiveStart == null || !current.reasoningToggle) return;
    current.reasoningToggle.chip.classList.add('busy');
    const seconds = Math.max(1, Math.round((Date.now() - current.reasoningLiveStart) / 1000));
    current.reasoningToggle.setLabel(`Thinking · ${seconds}s`);
  }, 1000);
}

function stopReasoningTick(current: StreamingState): void {
  if (current.reasoningTick != null) {
    clearInterval(current.reasoningTick);
    current.reasoningTick = null;
  }
  current.reasoningToggle?.chip.classList.remove('busy');
  current.reasoningLiveStart = null;
}

function resetStreaming(view: SessionView): void {
  if (view.streaming) stopReasoningTick(view.streaming);
  view.streaming = null;
  view.planBlock = null;
}

/**
 * True once the turn holds engine output: any chip beyond the turn status, a tool row, or text.
 * Text may still be queued in the streaming buffers — it reaches the DOM on the next animation
 * frame, which can lag behind a turn that ends within the same frame as its last chunk.
 */
function hasAssistantContent(wrapper: HTMLElement | null, current: StreamingState | null): boolean {
  if (!wrapper) return false;
  if (wrapper.querySelector('.assist-chips .assist-chip:not(.turn-status)')) return true;
  if (wrapper.querySelector('.tool-entry')) return true;
  if (current && (current.text.trim() || current.reasoning.trim())) return true;
  const body = wrapper.querySelector('.md-body');
  return Boolean(body && (body.textContent ?? '').trim());
}

/** Drops a turn bubble that never received anything (send rejected / turn that ended empty). */
function discardEmptyTurnBubble(view: SessionView): void {
  const wrapper = view.streaming?.bubble.parentElement ?? null;
  if (wrapper && !hasAssistantContent(wrapper, view.streaming)) wrapper.remove();
}

interface TurnClock {
  startedAt: number;
  tick: ReturnType<typeof setInterval> | null;
  chip: HTMLElement | null;
}

function activeTurnClock(view: SessionView): TurnClock {
  if (!view.turnClock) view.turnClock = { startedAt: Date.now(), tick: null, chip: null };
  return view.turnClock;
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
function finishTurnStatus(view: SessionView, event: any): void {
  const clock = view.turnClock;
  stopTurnClock(view);
  if (!clock?.chip) return;
  const elapsed = typeof event?.duration_ms === 'number' ? event.duration_ms : Date.now() - clock.startedAt;
  const cancelled = Boolean(event?.cancelled);
  clock.chip.textContent = `${cancelled ? 'Cancelled' : 'Done'} · ${formatElapsed(elapsed)}`;
  clock.chip.classList.remove('busy');
  clock.chip.classList.add(cancelled ? 'cancelled' : 'done');
}

/** The engine reports a failed turn as an error event instead of a turn_complete (no duration payload). */
function failTurnStatus(view: SessionView): void {
  const clock = view.turnClock;
  stopTurnClock(view);
  if (!clock?.chip) return;
  clock.chip.textContent = `Failed · ${formatElapsed(Date.now() - clock.startedAt)}`;
  clock.chip.classList.remove('busy');
  clock.chip.classList.add('failed');
}

function stopTurnClock(view: SessionView): void {
  if (view.turnClock?.tick != null) clearInterval(view.turnClock.tick);
  view.turnClock = null;
}

/**
 * The engine no longer runs this view's turn (engine restart/crash): freeze the UI where it was
 * instead of leaving a chip that ticks forever and tools stuck "running".
 */
function finalizeInterruptedTurn(view: SessionView): void {
  const clock = view.turnClock;
  resolveStaleToolStatuses(view.streaming?.bubble.parentElement ?? null);
  resolveStaleTaskStatuses(view);
  stopTurnClock(view);
  if (clock?.chip) {
    clock.chip.textContent = `Interrupted · ${formatElapsed(Date.now() - clock.startedAt)}`;
    clock.chip.classList.remove('busy');
    clock.chip.classList.add('failed');
  }
  resetStreaming(view);
  removeApprovalBar(view);
  setViewStatus(view, 'idle');
}

/** A fresh engine process serves no live turns: every previously running one died with the old one. */
function finalizeTurnsForEngineRestart(): void {
  for (const view of [...views.values()]) {
    // Background tasks die with the engine and they outlive their turn, so the view holding one is
    // usually idle — sweep every view, not only the ones with a live turn (an idle view's Tasks
    // chip would otherwise spin forever: the new engine will never send that task_status).
    resolveStaleTaskStatuses(view);
    if (view.turnClock || view.streaming || view.status === 'running') finalizeInterruptedTurn(view);
  }
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
  hub: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>',
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
  maximize: '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>',
  minimize: '<polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/>',
  x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
  cpu: '<rect x="4" y="4" width="16" height="16" rx="2" ry="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/>',
  lightbulb: '<circle cx="12" cy="9.5" r="5"/><path d="M9.8 15h4.4"/><path d="M10.3 18h3.4"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
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

/** Raw envelope for panels that render their own failure states (e.g. the Hub's not-connected view). */
async function callEnvelope(method: string, params: Record<string, unknown> = {}): Promise<Envelope> {
  if (!state.workspace) return { ok: false, error: { code: 0, message: 'no workspace connected' } };
  return window.coreai.call(state.workspace, method, params);
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
      const view = findSessionView(sessionId);
      if (resumed && view) applyResumeStatus(view, resumed);
    }
    void refreshModelState();
  } catch (error) {
    toast(`Switch failed: ${cleanIpcError(error)}`);
  }
}

function openLoginDialog(onSuccess?: () => void): void {
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
      onSuccess?.();
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
  const sessionId = state.sessionId;
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
  const current = await call('model/get', sessionId ? { sessionId } : {});
  // A reply that raced a session switch or a blank-window pick must not overwrite the newer state.
  const stillCurrent = state.sessionId === sessionId && (sessionId !== null || state.pendingModel == null);
  if (current?.model && stillCurrent) state.currentModel = String(current.model);
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
    const view = findSessionView(sessionId);
    if (resumed && view) applyResumeStatus(view, resumed);
  }
}

/** Computer use enable/disable restart engines too; the open session must be resumed before it sends again. */
async function resumeOpenSession(): Promise<void> {
  const sessionId = state.sessionId;
  if (!sessionId || !state.workspace) return;
  const resumed = await call('session/resume', { sessionId });
  const view = findSessionView(sessionId);
  if (resumed && view && sessionId === state.sessionId) applyResumeStatus(view, resumed);
}

/**
 * Applies a resume result to its view. A session the engine reports as not-running while the view
 * still shows a live turn was lost outside this client (engine restart/crash): the stale turn is
 * finalized instead of ticking forever. The grace period keeps a just-sent message — whose driver
 * thread may not have started yet — from being mistaken for one.
 */
function applyResumeStatus(view: SessionView, resumed: any): void {
  const status = String(resumed?.status ?? 'idle').toLowerCase();
  const clock = view.turnClock;
  if (status !== 'running' && clock && Date.now() - clock.startedAt > 5_000) {
    finalizeInterruptedTurn(view);
  }
  setViewStatus(view, status);
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
    if (!state.sessionId) {
      // No session yet: the pick lands on the session the first message creates.
      state.pendingModel = picked;
      state.currentModel = picked;
      toast(`Model: ${picked} — applies to the new session`);
      return;
    }
    const target = state.sessionId;
    const applied = await call('model/set', { model: picked, sessionId: target });
    if (state.sessionId !== target) return;
    if (!applied) {
      modelSelect.value = state.currentModel ?? '';
      return;
    }
    state.currentModel = String(applied.model ?? picked);
    toast(`Model: ${state.currentModel} — this session`);
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

  statusChip = el('span', `composer-status ${state.status}`, state.status);
  composerControlsEl.append(statusChip);
}

async function chooseWorkspace(): Promise<void> {
  const chosen = await window.coreai.chooseWorkspace();
  if (chosen) await connect(chosen);
}

async function connect(workspace: string): Promise<void> {
  state.workspace = workspace;
  state.capabilities = await window.coreai.engineCapabilities(workspace).catch(() => []);
  renderSidebar();
  if (workspace === state.info?.defaultWorkspacePath) state.defaultExists = true;
  state.sessionId = null;
  state.sessionTitle = '';
  state.sessions = [];
  state.pendingSessionIds.clear();
  state.pendingModel = null;
  state.sessionCursor = null;
  state.hasMoreSessions = false;
  clearPendingAttachments();
  disposeAllSessionViews();
  chatView = null;
  sessionStackEl = null;
  chatTitleEl = null;
  applyComposerStatus('idle');
  if (!state.workspaces.includes(workspace)) {
    state.workspaces = [workspace, ...state.workspaces].slice(0, 8);
  }
  const settings = await window.coreai.getSettings();
  state.info.backgroundScheduling = settings?.backgroundScheduling === true;
  state.workspaces = settings?.workspaces ?? state.workspaces;
  renderSidebar();
  renderComposerControls();
  await loadSummaries();
  await loadSessions(true);
  await refreshModelState();
  state.artifacts = [];
  state.activeArtifactPath = null;
  state.artifactUnread = false;
  state.artifactDismissedForTurn = false;
  await refreshArtifactBase();
  void refreshArtifactBaseline();
  renderContent();
}

async function enterDefaultMode(): Promise<void> {
  state.workspace = null;
  state.capabilities = [];
  state.sessionId = null;
  state.sessionTitle = '';
  state.sessions = [];
  state.pendingSessionIds.clear();
  state.pendingModel = null;
  state.sessionCursor = null;
  state.hasMoreSessions = false;
  clearPendingAttachments();
  disposeAllSessionViews();
  chatView = null;
  sessionStackEl = null;
  chatTitleEl = null;
  applyComposerStatus('idle');
  state.artifacts = [];
  state.activeArtifactPath = null;
  state.artifactBase = null;
  state.artifactUnread = false;
  state.artifactDismissedForTurn = false;
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
  if (reset) {
    state.sessions = [...(await pendingSessionRows(rows)), ...rows];
  } else {
    const known = new Set(state.sessions.map((session) => session.sessionId));
    state.sessions = [...state.sessions, ...rows.filter((row) => !known.has(row.sessionId))];
  }
  state.sessionCursor = listed.nextCursor ?? null;
  state.hasMoreSessions = Boolean(listed.nextCursor);
  renderSessionItems();
}

/**
 * The engine persists a session only at its first save point, so `session/list` misses one whose
 * first turn is still running. Local rows of such sessions survive the rebuild; a session the
 * engine no longer knows — restarted or crashed before that first save — is dropped instead, and
 * one the listing has caught up with retires its local row.
 */
async function pendingSessionRows(listed: SessionRow[]): Promise<SessionRow[]> {
  const listedIds = new Set(listed.map((row) => row.sessionId));
  const kept: SessionRow[] = [];
  for (const session of state.sessions) {
    if (!state.pendingSessionIds.has(session.sessionId)) continue;
    if (listedIds.has(session.sessionId)) {
      state.pendingSessionIds.delete(session.sessionId);
      continue;
    }
    const envelope = await callEnvelope('session/resume', { sessionId: session.sessionId });
    if (envelope.ok) {
      kept.push(session);
    } else {
      state.pendingSessionIds.delete(session.sessionId);
    }
  }
  return kept;
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
  state.pendingModel = null;
  clearPendingAttachments();
  const previousDraft = findSessionView(null);
  if (previousDraft) disposeSessionView(previousDraft);
  renderSessionItems();
  updateChatHeader();
  await showConversationView();
  const view = ensureSessionView(null);
  showSessionView(view);
  showBlankHint();
  // A blank window starts from the engine default (model picks are per session); re-read it so the
  // composer shows what the next session will actually use.
  void refreshModelState();
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
  // A model picked in the blank window becomes this session's model before its first turn runs.
  if (state.pendingModel) {
    const pending = state.pendingModel;
    state.pendingModel = null;
    await call('model/set', { sessionId: createdId, model: pending });
  }
  // Remember it until an engine listing carries it: refreshes triggered before its first save
  // point (another session starting, any turn ending) rebuild the list from disk alone.
  state.pendingSessionIds.add(createdId);
  await refreshSessionList();
  // A failed or stale listing leaves no engine row at all — show the fresh session locally.
  if (!state.sessions.some((session) => session.sessionId === createdId)) {
    state.sessions = [{ sessionId: createdId, title: firstMessage }, ...state.sessions];
  }
  state.sessionId = createdId;
  state.sessionTitle = state.sessions.find((session) => session.sessionId === createdId)?.title ?? firstMessage;
  const view = ensureSessionView(createdId);
  view.historyLoaded = true;
  renderSessionItems();
  updateChatHeader();
  showSessionView(view);
  return true;
}

/** A sidebar session action means "show the conversation" — a panel must give the chat view back. */
async function showConversationView(): Promise<void> {
  if (state.nav === 'sessions') return;
  state.nav = 'sessions';
  renderSidebar();
  await renderContent();
}

/**
 * Switching sessions only swaps which window is on screen. The outgoing view keeps its DOM,
 * streaming bubble, turn clock and tool map alive (swept later if it has nothing in flight), so a
 * session that is still working shows everything exactly where it was when the user comes back.
 */
async function openSession(sessionId: string): Promise<void> {
  const target = sessionId;
  state.sessionId = sessionId;
  state.sessionTitle = state.sessions.find((session) => session.sessionId === sessionId)?.title ?? sessionId;
  state.pendingModel = null;
  clearPendingAttachments();
  const view = ensureSessionView(target);
  renderSessionItems();
  updateChatHeader();
  await showConversationView();
  if (state.sessionId !== target) return;
  showSessionView(view);

  const resumed = await call('session/resume', { sessionId: target });
  if (state.sessionId !== target) return;
  if (resumed) applyResumeStatus(view, resumed);

  if (!view.historyLoaded && !view.historyLoading) {
    view.historyLoading = true;
    const history = await call('session/history', { sessionId: target, limit: 300 });
    view.historyLoading = false;
    if (findSessionView(target) !== view) return;
    view.historyLoaded = true;
    if (history?.messages) {
      renderHistoryMessages(view, history.messages);
    }
    // Events that arrived during the load apply after the history they belong behind.
    for (const buffered of view.pendingEvents.splice(0)) applySessionEvent(view, buffered);
    if (view === mountedView) scrollTranscript(view, true);
  }
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
  const view = activeSessionView();
  if (!view || state.sessionId || view.transcript.childElementCount > 0) return;
  const hint = el('div', 'default-hint');
  hint.append(el('div', 'big', 'new session'));
  hint.append(el('div', undefined, 'Created with your first message; it then shows up in the session list.'));
  view.transcript.append(hint);
}

function showDefaultHint(): void {
  const view = activeSessionView();
  if (!view || state.sessionId || view.transcript.childElementCount > 0) return;
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
  view.transcript.append(hint);
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
  header.append(chatTitleEl, buildDockToggles());

  // One session window is mounted at a time; the others keep their DOM (and scroll position) in
  // memory while their session works, and are dropped once idle.
  sessionStackEl = el('div', 'session-stack');
  mountedView = null;
  const chatMain = el('div', 'chat-main');
  chatMain.append(header, sessionStackEl, buildComposer());
  chatView.append(chatMain, buildRightDock());
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
  // The + sits at the top-left corner of the input box, before the text.
  const inputRow = el('div', 'composer-input-row');
  const attach = el('button', 'composer-attach-btn') as HTMLButtonElement;
  attach.append(icon('plus'));
  attach.title = 'Attach images or files';
  attach.onclick = () => void chooseAttachments();
  inputRow.append(attach, composerInput);
  composerAttachEl = el('div', 'composer-attachments');
  composerAttachEl.hidden = true;
  composerControlsEl = el('div', 'composer-controls');
  card.append(inputRow, composerAttachEl, composerControlsEl);
  composer.append(card);
  composerAutoGrow();
  renderComposerAttachments();
  renderComposerControls();
  return composer;
}

// --- composer attachments (design §8): files are staged by the main process into the engine's
// per-session staging directory, shown as chips, and sent as image / pdf / file parts.

function baseNameOf(filePath: string): string {
  const parts = filePath.split(/[\\/]/);
  return parts[parts.length - 1] || filePath;
}

function attachmentChipEl(attachment: PendingAttachment, removable: boolean): HTMLElement {
  if (attachment.kind === 'image' && attachment.preview) {
    return imageTileEl(attachment, removable);
  }
  const chip = el('div', 'attachment-chip');
  chip.title = attachment.path;
  chip.append(icon('file'));
  chip.append(el('span', 'attachment-name', attachment.name));
  chip.append(el('span', 'attachment-size', fmtBytes(attachment.size)));
  if (removable) {
    const remove = el('button', 'attachment-remove') as HTMLButtonElement;
    remove.append(icon('x'));
    remove.title = 'Remove attachment';
    remove.onclick = () => removePendingAttachment(attachment);
    chip.append(remove);
  }
  return chip;
}

/** Attached images show as a real preview tile (click to enlarge), not just a file name. */
function imageTileEl(attachment: PendingAttachment, removable: boolean): HTMLElement {
  const tile = el('div', 'attachment-image-tile');
  tile.title = `${attachment.name} · ${fmtBytes(attachment.size)}`;
  const img = el('img', undefined) as HTMLImageElement;
  img.src = attachment.preview as string;
  img.alt = attachment.name;
  img.onclick = () => openAttachmentLightbox(attachment.preview as string, attachment.name, attachment.size);
  tile.append(img);
  if (removable) {
    const remove = el('button', 'attachment-remove') as HTMLButtonElement;
    remove.append(icon('x'));
    remove.title = 'Remove attachment';
    remove.onclick = (event) => {
      event.stopPropagation();
      removePendingAttachment(attachment);
    };
    tile.append(remove);
  }
  return tile;
}

/** Full-size preview overlay; appended to the body so it covers the window, not the message flow. */
function openAttachmentLightbox(src: string, name: string, size: number): void {
  const overlay = el('div', 'attachment-lightbox');
  const inner = el('div', 'lightbox-inner');
  const figure = el('figure', 'lightbox-figure');
  const image = el('img', undefined) as HTMLImageElement;
  image.src = src;
  image.alt = name;
  figure.append(image);
  figure.append(el('figcaption', 'lightbox-caption', `${name} · ${fmtBytes(size)}`));
  inner.append(figure);
  overlay.append(inner);
  document.body.append(overlay);
  const close = () => {
    overlay.remove();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') close();
  };
  document.addEventListener('keydown', onKey);
  overlay.addEventListener('click', close);
}

function renderComposerAttachments(): void {
  if (!composerAttachEl) return;
  composerAttachEl.replaceChildren();
  composerAttachEl.hidden = pendingAttachments.length === 0;
  for (const attachment of pendingAttachments) {
    composerAttachEl.append(attachmentChipEl(attachment, true));
  }
}

async function chooseAttachments(): Promise<void> {
  let picked: { paths?: string[] } | null = null;
  try {
    picked = await window.coreai.pickAttachments();
  } catch (error) {
    toast(`Attach failed: ${cleanIpcError(error)}`, 'error');
    return;
  }
  const paths = (picked?.paths ?? []).filter((entry) => typeof entry === 'string' && entry.trim());
  if (!paths.length) return;
  if (!state.sessionId) {
    // session/send only reads attachment paths inside the session's staging directory, so the
    // conversation must exist before the copy is made.
    if (!(await startNewSession(baseNameOf(paths[0])))) return;
  }
  let staged: StagedAttachmentResult | null = null;
  try {
    staged = await window.coreai.stageAttachments(state.sessionId as string, paths);
  } catch (error) {
    toast(`Attach failed: ${cleanIpcError(error)}`, 'error');
    return;
  }
  for (const skip of staged?.skipped ?? []) {
    toast(`Skipped ${skip.name || 'file'}: ${skip.reason}`, 'error');
  }
  for (const attachment of staged?.attachments ?? []) {
    if (!pendingAttachments.some((entry) => entry.path === attachment.path)) {
      pendingAttachments.push(attachment);
    }
  }
  renderComposerAttachments();
  composerInput?.focus();
}

function removePendingAttachment(attachment: PendingAttachment): void {
  pendingAttachments = pendingAttachments.filter((entry) => entry.path !== attachment.path);
  renderComposerAttachments();
  void window.coreai.removeAttachment(attachment.path).catch(() => undefined);
}

/** Staged copies stay on disk (removed by the age sweep); only the composer chips are dropped. */
function clearPendingAttachments(): void {
  if (pendingAttachments.length === 0) return;
  pendingAttachments = [];
  renderComposerAttachments();
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
  browserFullscreenEl = browserToolButton('maximize', 'Full screen', () => toggleDockFullscreen());
  browserDotEl = el('span', 'browser-dot');
  const stop = el('button', 'btn small', 'Stop');
  stop.title = 'Stop the embedded browser (closes every tab)';
  stop.onclick = () => void stopBrowser();
  toolbar.append(browserBackEl, browserForwardEl, browserReloadEl, browserAddressEl, browserOpenExternalEl, browserFullscreenEl, browserDotEl, stop);

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

/** Chat header right side: one toggle per dock tab (Browser / Artifacts); the dock itself carries the tabs. */
function buildDockToggles(): HTMLElement {
  const group = el('div', 'dock-toggles');
  browserToggleEl = el('button', 'browser-toggle') as HTMLButtonElement;
  browserToggleEl.onclick = () => void toggleBrowser();
  artifactsToggleEl = el('button', 'browser-toggle') as HTMLButtonElement;
  artifactsToggleEl.onclick = () => toggleArtifactsDock();
  group.append(browserToggleEl, artifactsToggleEl);
  renderBrowserToggle(Boolean(state.browserStatus?.running));
  renderArtifactsToggle();
  return group;
}

/** The right dock: a tab strip over the embedded browser pane and the (pure DOM) artifact pane. */
function buildRightDock(): HTMLElement {
  const dock = el('div', 'right-dock');
  rightDockEl = dock;
  const tabs = el('div', 'right-dock-tabs');
  browserTabEl = el('button', 'right-dock-tab', 'Browser') as HTMLButtonElement;
  browserTabEl.onclick = () => setRightDock(state.rightDock === 'browser' ? 'none' : 'browser');
  artifactsTabEl = el('button', 'right-dock-tab', 'Artifacts') as HTMLButtonElement;
  artifactsTabEl.onclick = () => toggleArtifactsDock();
  tabs.append(browserTabEl, artifactsTabEl);
  dock.append(tabs, buildBrowserPanel(), buildArtifactPane());
  renderDock();
  return dock;
}

function setRightDock(dock: 'none' | 'browser' | 'artifacts'): void {
  if (state.rightDock === dock) return;
  state.rightDock = dock;
  if (dock === 'none') setDockFullscreen(false);
  renderDock();
}

function renderDock(): void {
  const open = state.rightDock !== 'none';
  if (rightDockEl) rightDockEl.hidden = !open;
  if (browserPanelEl) browserPanelEl.hidden = state.rightDock !== 'browser';
  if (artifactPaneEl) artifactPaneEl.hidden = state.rightDock !== 'artifacts';
  renderDockTabs();
  renderBrowserToggle(Boolean(state.browserStatus?.running));
  renderArtifactsToggle();
  renderArtifactFullscreenButton();
  syncSidebarWithRightDock();
  scheduleBrowserBounds();
}

function renderDockTabs(): void {
  browserTabEl?.classList.toggle('active', state.rightDock === 'browser');
  artifactsTabEl?.classList.toggle('active', state.rightDock === 'artifacts');
  artifactsTabEl?.classList.toggle('unread', state.artifactUnread && state.rightDock !== 'artifacts');
}

function toggleArtifactsDock(): void {
  if (state.rightDock === 'artifacts') {
    state.artifactDismissedForTurn = true;
    setRightDock('none');
    return;
  }
  state.artifactUnread = false;
  setRightDock('artifacts');
  void refreshArtifactBaseline();
}

function renderArtifactsToggle(): void {
  if (!artifactsToggleEl) return;
  const open = state.rightDock === 'artifacts';
  artifactsToggleEl.title = open
    ? 'Collapse the artifacts pane'
    : 'Local artifacts — generated images/videos and workspace output';
  artifactsToggleEl.replaceChildren(el('span', 'browser-toggle-label', 'Artifacts'));
  if (!open && state.artifactUnread) artifactsToggleEl.append(el('span', 'browser-dot on'));
  artifactsToggleEl.append(icon(open ? 'chevron-right' : 'chevron-left'));
}

// --- artifacts pane (gallery + preview/source) ------------------------------------------------

function buildArtifactPane(): HTMLElement {
  const pane = el('div', 'artifact-panel');
  artifactPaneEl = pane;
  artifactHeadEl = el('div', 'artifact-head');
  artifactGalleryEl = el('div', 'artifact-gallery');
  artifactBodyEl = el('div', 'artifact-preview');
  pane.append(artifactHeadEl, artifactGalleryEl, artifactBodyEl);
  renderArtifactPanel();
  return pane;
}

function activeArtifact(): ArtifactEntry | null {
  if (!state.activeArtifactPath) return state.artifacts[0] ?? null;
  return state.artifacts.find((entry) => entry.path === state.activeArtifactPath) ?? state.artifacts[0] ?? null;
}

function artifactUrl(relativePath: string): string {
  const encoded = relativePath.split('/').map((segment) => encodeURIComponent(segment)).join('/');
  return `${state.artifactBase ?? ''}/${encoded}`;
}

function renderArtifactPanel(): void {
  if (!artifactPaneEl) return;
  renderArtifactHead();
  renderArtifactGallery();
  renderArtifactBody();
}

function renderArtifactHead(): void {
  if (!artifactHeadEl) return;
  artifactHeadEl.replaceChildren();
  const entry = activeArtifact();
  if (!entry) {
    artifactHeadEl.append(el('span', 'artifact-title', state.artifacts.length ? 'Artifacts' : 'No artifacts yet'));
    return;
  }
  const title = el('span', 'artifact-title', entry.name);
  title.title = entry.path;
  artifactHeadEl.append(title);
  artifactHeadEl.append(el('span', 'artifact-meta', `${entry.kind} · ${artifactContentType(entry)} · ${fmtBytes(entry.size)}`));
  const actions = el('div', 'artifact-actions');
  if (artifactSupportsSource(entry)) {
    const toggle = el('button', 'btn small', state.artifactSource ? 'Preview' : 'Source');
    toggle.onclick = () => { state.artifactSource = !state.artifactSource; renderArtifactPanel(); };
    actions.append(toggle);
  }
  const reveal = el('button', 'btn small', 'Reveal');
  reveal.onclick = () => window.coreai.revealPath(state.workspace as string, entry.path);
  const maximize = el('button', 'artifact-icon-btn') as HTMLButtonElement;
  artifactFullscreenEl = maximize;
  maximize.onclick = () => toggleDockFullscreen();
  renderArtifactFullscreenButton();
  const close = el('button', 'btn small', 'Close');
  close.onclick = () => { state.artifactDismissedForTurn = true; setRightDock('none'); };
  actions.append(reveal, maximize, close);
  artifactHeadEl.append(actions);
}

function renderArtifactGallery(): void {
  if (!artifactGalleryEl) return;
  artifactGalleryEl.replaceChildren();
  if (!state.artifacts.length) {
    artifactGalleryEl.hidden = true;
    return;
  }
  artifactGalleryEl.hidden = false;
  const current = activeArtifact();
  for (const entry of state.artifacts) {
    const item = el('button', `artifact-thumb${current?.path === entry.path ? ' active' : ''}`) as HTMLButtonElement;
    item.title = entry.name;
    if (artifactIsImage(entry)) {
      const img = el('img') as HTMLImageElement;
      img.src = artifactUrl(entry.path);
      img.alt = entry.name;
      img.loading = 'lazy';
      item.append(img);
    } else {
      item.append(el('span', 'artifact-thumb-icon', entry.kind === 'video' ? '▶' : '▤'));
    }
    item.onclick = () => {
      state.activeArtifactPath = entry.path;
      state.artifactSource = false;
      renderArtifactPanel();
    };
    artifactGalleryEl.append(item);
  }
}

function renderArtifactBody(): void {
  if (!artifactBodyEl) return;
  artifactBodyEl.replaceChildren();
  const entry = activeArtifact();
  if (!entry) {
    artifactBodyEl.append(el('div', 'notice', 'Generated images and videos appear here as they are created.'));
    return;
  }
  const url = artifactUrl(entry.path);
  if (state.artifactSource) {
    if (!artifactSupportsSource(entry)) {
      artifactBodyEl.append(el('div', 'notice', 'Source not available for this file type.'));
      return;
    }
    void renderArtifactSourceText(url, artifactIsJson(entry) ? 'json' : 'text');
    return;
  }
  // Format dispatch mirrors the server ArtifactDrawer: json / image / video / html / markdown / pdf.
  if (artifactIsJson(entry)) { void renderArtifactSourceText(url, 'json'); return; }
  if (artifactIsImage(entry)) {
    const img = el('img', 'artifact-image') as HTMLImageElement;
    img.src = url;
    img.alt = entry.name;
    artifactBodyEl.append(img);
    return;
  }
  if (artifactIsVideo(entry)) {
    const video = el('video', 'artifact-video') as HTMLVideoElement;
    video.src = url;
    video.controls = true;
    video.preload = 'metadata';
    artifactBodyEl.append(video);
    return;
  }
  if (artifactIsHtml(entry)) { artifactBodyEl.append(artifactFrame(url, 'allow-scripts')); return; }
  if (artifactIsMarkdown(entry)) { void renderArtifactMarkdown(url); return; }
  if (artifactIsPdf(entry)) { artifactBodyEl.append(artifactFrame(url, null)); return; }
  artifactBodyEl.append(el('div', 'notice', 'Preview not available for this file type.'));
}

/** A sandbox value of null omits the attribute (the PDF viewer refuses to run inside a sandbox). */
function artifactFrame(url: string, sandbox: string | null): HTMLIFrameElement {
  const frame = el('iframe', 'artifact-frame') as HTMLIFrameElement;
  if (sandbox !== null) frame.setAttribute('sandbox', sandbox);
  frame.src = url;
  return frame;
}

async function renderArtifactSourceText(url: string, mode: 'json' | 'text'): Promise<void> {
  const target = artifactBodyEl;
  if (!target) return;
  target.replaceChildren(el('div', 'notice', 'Loading…'));
  try {
    const response = await fetch(url);
    const text = await response.text();
    if (target !== artifactBodyEl) return;
    let body = text;
    if (mode === 'json') {
      try { body = JSON.stringify(JSON.parse(text), null, 2); } catch { body = text; }
    }
    target.replaceChildren(el('pre', 'artifact-source', body));
  } catch (error) {
    target.replaceChildren(el('div', 'notice error', `Unable to read artifact: ${(error as Error).message}`));
  }
}

async function renderArtifactMarkdown(url: string): Promise<void> {
  const target = artifactBodyEl;
  if (!target) return;
  try {
    const response = await fetch(url);
    const text = await response.text();
    if (target !== artifactBodyEl) return;
    const container = el('div', 'artifact-markdown');
    target.replaceChildren(container);
    renderMarkdownInto(container, text);
  } catch (error) {
    target.replaceChildren(el('div', 'notice error', `Unable to read artifact: ${(error as Error).message}`));
  }
}

const ARTIFACT_TEXT_EXT = /\.(html?|css|js|jsx|ts|tsx|mjs|json|md|markdown|txt|xml|svg|py|java|kt|go|rb|rs|sh|bash|yaml|yml|toml|csv|tsv|sql|conf|ini|log)$/i;

function artifactExtension(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot).toLowerCase() : '';
}

function artifactIsImage(entry: ArtifactEntry): boolean {
  return artifactContentType(entry).startsWith('image/') || /^\.(png|jpe?g|gif|webp|svg|bmp|ico)$/.test(artifactExtension(entry.name));
}

function artifactIsVideo(entry: ArtifactEntry): boolean {
  return artifactContentType(entry).startsWith('video/') || /^\.(mp4|webm|ogv|mov|m4v)$/.test(artifactExtension(entry.name));
}

function artifactIsHtml(entry: ArtifactEntry): boolean {
  return artifactContentType(entry) === 'text/html' || /^\.html?$/.test(artifactExtension(entry.name));
}

function artifactIsMarkdown(entry: ArtifactEntry): boolean {
  return artifactContentType(entry) === 'text/markdown' || /^\.(md|markdown)$/.test(artifactExtension(entry.name));
}

function artifactIsPdf(entry: ArtifactEntry): boolean {
  return artifactContentType(entry) === 'application/pdf' || artifactExtension(entry.name) === '.pdf';
}

function artifactIsJson(entry: ArtifactEntry): boolean {
  return artifactContentType(entry).includes('json') || artifactExtension(entry.name) === '.json';
}

/** A file whose bytes are readable text — the only files with a Source view (mirrors ArtifactDrawer). */
function artifactSupportsSource(entry: ArtifactEntry): boolean {
  const type = artifactContentType(entry);
  if (type.startsWith('text/')) return true;
  if (type.includes('json') || type.includes('xml') || type.includes('javascript') || type.includes('yaml')) return true;
  return ARTIFACT_TEXT_EXT.test(entry.name);
}

function artifactEntryFromPath(path: string): ArtifactEntry {
  const name = baseName(path);
  const lower = name.toLowerCase();
  const kind = /\.(png|jpe?g|gif|webp|bmp|svg)$/.test(lower) ? 'image'
    : /\.(mp4|webm|mov)$/.test(lower) ? 'video' : 'file';
  return { path, name, kind, size: 0, contentType: null, createdAt: null, tool: null };
}

/** Opens the artifacts dock on one entry, adding it to the gallery when it is not there yet. */
function openArtifactInDock(entry: ArtifactEntry): void {
  if (!state.artifacts.some((item) => item.path === entry.path)) {
    state.artifacts = [entry, ...state.artifacts];
  }
  state.activeArtifactPath = entry.path;
  state.artifactSource = false;
  setRightDock('artifacts');
  renderArtifactPanel();
}

/** A generated-media `artifact` custom event: newest first, auto-expanded only when the dock is idle (D9). */
function applyArtifactEvent(view: SessionView, event: any): void {
  let payload: any;
  try { payload = JSON.parse(event.data ?? '{}'); } catch { return; }
  if (!payload || typeof payload.path !== 'string' || !payload.path) return;
  const entry: ArtifactEntry = {
    path: payload.path,
    name: typeof payload.name === 'string' && payload.name ? payload.name : baseName(payload.path),
    kind: typeof payload.kind === 'string' ? payload.kind : 'file',
    size: Number(payload.size ?? 0),
    contentType: typeof payload.contentType === 'string' ? payload.contentType : null,
    createdAt: typeof payload.createdAt === 'string' ? payload.createdAt : null,
    tool: typeof payload.tool === 'string' ? payload.tool : null,
  };
  state.artifacts = [entry, ...state.artifacts.filter((item) => item.path !== entry.path)];
  state.activeArtifactPath = entry.path;
  state.artifactSource = false;
  if (typeof event.callId === 'string' && event.callId) artifactByCallId.set(event.callId, entry);
  renderArtifactPanel();
  const isCurrent = view === mountedView;
  if (isCurrent && state.rightDock === 'none' && !state.artifactDismissedForTurn) {
    setRightDock('artifacts');
  } else if (isCurrent && state.rightDock !== 'artifacts') {
    state.artifactUnread = true;
    renderArtifactsToggle();
    renderDockTabs();
  }
}

async function refreshArtifactBase(): Promise<void> {
  if (!state.workspace) { state.artifactBase = null; return; }
  try {
    const result = await window.coreai.artifactBase(state.workspace);
    state.artifactBase = result?.baseUrl ?? null;
  } catch {
    state.artifactBase = null;
  }
}

/** Baseline for the panel: realtime events are live-only, so a reopened panel re-reads the scan. */
async function refreshArtifactBaseline(): Promise<void> {
  if (!state.workspace) { state.artifacts = []; renderArtifactPanel(); return; }
  const listed = await call('artifacts/list', { limit: 200 });
  if (!listed) return;
  const files: any[] = listed.files ?? [];
  const baseline: ArtifactEntry[] = files.map((file) => ({
    path: String(file.path ?? ''),
    name: String(file.name ?? baseName(String(file.path ?? ''))),
    kind: typeof file.kind === 'string' ? file.kind : 'file',
    size: Number(file.size ?? 0),
    contentType: null,
    createdAt: typeof file.createdAt === 'string' ? file.createdAt : null,
    tool: null,
  })).filter((entry) => entry.path);
  const known = new Set(baseline.map((entry) => entry.path));
  const live = state.artifacts.filter((entry) => !known.has(entry.path));
  state.artifacts = [...live, ...baseline];
  if (!state.activeArtifactPath && state.artifacts.length) state.activeArtifactPath = state.artifacts[0].path;
  renderArtifactPanel();
}

/** Maps an absolute local path (as it appears in generated-image markdown) to a workspace-relative one. */
function workspaceRelativePath(value: string | null): string | null {
  if (!value || !state.workspace) return null;
  const normalized = value.replace(/\\/g, '/');
  const root = state.workspace.replace(/\\/g, '/').replace(/\/+$/, '');
  if (!normalized.toLowerCase().startsWith(`${root.toLowerCase()}/`)) return null;
  return normalized.slice(root.length + 1);
}

/** Rewrites `<img>` links that point at workspace files to the in-app protocol so generated images render. */
function rewriteLocalImageSources(root: HTMLElement): void {
  if (!state.workspace || !state.artifactBase) return;
  for (const img of Array.from(root.querySelectorAll('img'))) {
    const relative = workspaceRelativePath(img.getAttribute('src'));
    if (!relative) continue;
    img.src = artifactUrl(relative);
    img.loading = 'lazy';
    img.classList.add('artifact-inline-image');
    img.onclick = () => openArtifactInDock(artifactEntryFromPath(relative));
  }
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
    if (state.rightDock !== 'browser' || !running || !frame) {
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
  if (browserDotEl) browserDotEl.className = running ? 'browser-dot on' : 'browser-dot';
  renderBrowserToggle(running);
  renderBrowserFullscreenButton();
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

/**
 * The single browser button (chat header, rightmost): 'Browser' starts it; while running it shows
 * a status dot and a chevron pointing where the pane moves — collapsing never stops the browser.
 */
function renderBrowserToggle(running: boolean): void {
  if (!browserToggleEl) return;
  if (!running) {
    browserToggleEl.title = 'Embedded browser — start it; the agent drives the same tabs';
    browserToggleEl.replaceChildren(el('span', 'browser-toggle-label', 'Browser'));
    return;
  }
  const open = state.rightDock === 'browser';
  browserToggleEl.title = open
    ? 'Collapse the browser pane — the browser keeps running'
    : 'Expand the browser pane — the browser keeps running';
  browserToggleEl.replaceChildren(
    el('span', 'browser-toggle-label', 'Browser'),
    el('span', 'browser-dot on'),
    icon(open ? 'chevron-right' : 'chevron-left'),
  );
}

/**
 * Full screen hides the chat column and the sidebar so the dock owns the window (for pages that read
 * poorly in the half-width split, or to inspect an artifact up close). It applies to whichever tab is
 * active; the browser and its tabs are untouched, and each tab carries its own maximize button.
 */
function setDockFullscreen(fullscreen: boolean): void {
  if (state.dockFullscreen === fullscreen) return;
  state.dockFullscreen = fullscreen;
  document.getElementById('app')?.classList.toggle('dock-fullscreen', fullscreen);
  renderBrowserFullscreenButton();
  renderArtifactFullscreenButton();
  scheduleBrowserBounds();
}

function toggleDockFullscreen(): void {
  setDockFullscreen(!state.dockFullscreen);
}

function renderBrowserFullscreenButton(): void {
  if (!browserFullscreenEl) return;
  const on = state.dockFullscreen;
  browserFullscreenEl.title = on
    ? 'Exit full screen — bring the chat back'
    : 'Full screen — hide the chat and use the whole window';
  browserFullscreenEl.replaceChildren(icon(on ? 'minimize' : 'maximize'));
}

function renderArtifactFullscreenButton(): void {
  if (!artifactFullscreenEl) return;
  const on = state.dockFullscreen;
  artifactFullscreenEl.title = on ? 'Restore — bring the chat back' : 'Maximize — use the whole window';
  artifactFullscreenEl.replaceChildren(icon(on ? 'minimize' : 'maximize'));
}

/** Opening either dock tab narrows the chat column, so the sidebar yields automatically. */
function syncSidebarWithRightDock(): void {
  const open = state.rightDock !== 'none';
  if (open === rightDockWasOpen) return;
  rightDockWasOpen = open;
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
    setRightDock(state.rightDock === 'browser' ? 'none' : 'browser');
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
    state.rightDock = 'browser';
    renderDock();
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
    if (state.rightDock === 'browser') state.rightDock = 'none';
    renderDock();
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
    if (state.rightDock !== 'browser') setRightDock('browser');
    return;
  }
  if (state.browserStarting) return;
  void startBrowser();
}

/**
 * Engine-injected `<system-reminder>` messages are plumbing, not user input: they render as a small
 * centered gray note. Only a message made up entirely of such blocks qualifies; anything the user
 * actually typed keeps its bubble.
 */
function systemReminderText(text: string): string | null {
  const blocks = [...text.matchAll(/<system-reminder>([\s\S]*?)<\/system-reminder>/g)];
  if (!blocks.length) return null;
  if (text.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim()) return null;
  return blocks.map((block) => block[1].trim()).filter(Boolean).join('\n');
}

interface TaskNotification {
  taskId: string | null;
  status: string;
  label: string | null;
  detail: string;
}

const TASK_STATUS_CLASS: Record<string, string> = {
  completed: 'done',
  done: 'done',
  success: 'done',
  failed: 'failed',
  error: 'failed',
  timeout: 'failed',
  cancelled: 'failed',
};

/** Engine-injected `<task-notification>` messages collapse to one small row; the payload stays hidden. */
function parseTaskNotifications(text: string): TaskNotification[] | null {
  const blocks = [...text.matchAll(/<task-notification>([\s\S]*?)<\/task-notification>/g)];
  if (!blocks.length) return null;
  if (text.replace(/<task-notification>[\s\S]*?<\/task-notification>/g, '').trim()) return null;
  return blocks.map((block) => taskNotificationOf(block[1]));
}

function taskNotificationOf(body: string): TaskNotification {
  return {
    taskId: xmlTag(body, 'task-id'),
    status: xmlTag(body, 'status') ?? 'done',
    label: xmlTag(body, 'tool') ?? xmlTag(body, 'task-type'),
    detail: xmlTag(body, 'result') ?? xmlTag(body, 'error') ?? body.trim(),
  };
}

function xmlTag(xml: string, tag: string): string | null {
  const match = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  const value = match?.[1].trim();
  return value ? value : null;
}

function taskNoteElement(notification: TaskNotification): HTMLElement {
  const note = el('div', 'task-note');
  if (notification.taskId) note.dataset.taskId = notification.taskId;
  const head = el('button', 'task-note-head') as HTMLButtonElement;
  const chevron = el('span', 'task-note-chevron', '▸');
  const state = TASK_STATUS_CLASS[notification.status];
  const title = el('span', state ? `task-note-title ${state}` : 'task-note-title', `Task ${notification.status}`);
  head.append(chevron, title);
  if (notification.label) head.append(el('span', 'task-note-label', notification.label));
  const body = el('div', 'task-note-body');
  body.hidden = true;
  body.append(el('pre', undefined, notification.detail));
  head.onclick = () => {
    body.hidden = !body.hidden;
    chevron.textContent = body.hidden ? '▸' : '▾';
  };
  note.append(head, body);
  return note;
}

/**
 * History stores one assistant message per model round while a live turn renders as a single
 * bubble. Reload folds consecutive assistant messages back into that same grouping, keeping each
 * round's text its own paragraph — the same shape the live transcript streams into.
 */
function renderHistoryMessages(view: SessionView, messages: Array<{ role?: string; text?: string }>): void {
  let segments: string[] = [];
  const flushAssistant = (): void => {
    if (!segments.length) return;
    addMessageBubble(view, 'assistant', segments.join('\n\n'));
    segments = [];
  };
  for (const message of messages) {
    if (message.role === 'user') {
      flushAssistant();
      addMessageBubble(view, 'user', message.text ?? '');
    } else {
      segments.push(message.text ?? '');
    }
  }
  flushAssistant();
}

function addMessageBubble(view: SessionView, role: 'user' | 'assistant', text: string, attachments: PendingAttachment[] = []): HTMLElement {
  if (role === 'user') {
    const notifications = parseTaskNotifications(text);
    if (notifications) {
      const notes = notifications.map((notification) => taskNoteElement(notification));
      for (const note of notes) view.transcript.append(note);
      return notes[notes.length - 1];
    }
    const reminder = systemReminderText(text);
    if (reminder !== null) {
      const note = el('div', 'system-note', reminder);
      view.transcript.append(note);
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
    if (text) bubble.textContent = text;
    if (attachments.length) {
      const row = el('div', 'attachment-row');
      for (const attachment of attachments) row.append(attachmentChipEl(attachment, false));
      bubble.append(row);
    }
  }
  wrapper.append(bubble);
  view.transcript.append(wrapper);
  return wrapper;
}

/**
 * Pins the transcript to the bottom while content streams in. Stops following when the user
 * scrolls up to read (stick turns off), resumes when they return to the bottom; `force` is for
 * explicit user actions like sending a message. Off-screen views have no layout to scroll.
 */
function scrollTranscript(view: SessionView, force = false): void {
  if (force) view.stick = true;
  if (!view.stick) return;
  if (view !== mountedView || !view.transcript.isConnected) return;
  view.transcript.scrollTop = view.transcript.scrollHeight;
}

function appendNotice(text: string, kind = '', parent?: HTMLElement | null): void {
  const notice = el('div', `notice ${kind}`, text);
  (parent ?? activeSessionView()?.transcript)?.append(notice);
}

type PlanState = 'done' | 'active' | 'pending';

const PLAN_MARKS: Record<PlanState, string> = { done: '✓', active: '▶', pending: '○' };

function planState(status: unknown): PlanState {
  const value = String(status ?? '').toLowerCase();
  if (value === 'completed' || value === 'done') return 'done';
  if (value === 'in_progress' || value === 'in-progress' || value === 'active') return 'active';
  return 'pending';
}

/**
 * Sized and aligned like the assistant messages: write_todos re-emits the whole list, so the
 * turn's card is rewritten in place instead of stacking a new box per update.
 */
function renderPlanUpdate(view: SessionView, event: any): void {
  const todos: any[] = Array.isArray(event.todos) ? event.todos : [];
  if (!todos.length) return;
  let block = view.planBlock;
  if (!block || !block.isConnected) {
    block = el('div', 'plan-block');
    view.transcript.append(block);
    view.planBlock = block;
  }
  const head = el('div', 'plan-head');
  head.append(el('span', 'plan-title', 'plan'));
  const done = todos.filter((todo) => planState(todo?.status) === 'done').length;
  head.append(el('span', 'plan-count', `${done}/${todos.length}`));
  block.replaceChildren(head);
  for (const todo of todos) {
    const state = planState(todo?.status);
    const item = el('div', `plan-item ${state}`);
    const content = todo?.content ?? todo?.text ?? JSON.stringify(todo);
    item.append(el('span', 'plan-mark', PLAN_MARKS[state]), el('span', 'plan-text', String(content)));
    block.append(item);
  }
  scrollTranscript(view);
}

async function sendCurrent(): Promise<void> {
  const text = composerInput?.value ?? '';
  const attachments = [...pendingAttachments];
  if (!text.trim() && attachments.length === 0) return;
  if (!state.sessionId && !(await startNewSession(text.trim() || baseNameOf(attachments[0]?.path ?? '')))) return;
  const view = ensureSessionView(state.sessionId);
  composerInput!.value = '';
  composerAutoGrow?.();
  addMessageBubble(view, 'user', text.trim(), attachments);
  scrollTranscript(view, true);
  resetStreaming(view);
  stopTurnClock(view);
  view.turnClock = { startedAt: Date.now(), tick: null, chip: null };
  // Open the turn bubble right away: the first engine event can be a full model round trip away, and
  // until then the ticking "Running · Ns" chip is the only sign of life (the CLI spinner works the same way).
  ensureStreaming(view);
  const parts: Array<{ type: string; text?: string; path?: string }> = [];
  if (text.trim()) parts.push({ type: 'text', text: text.trim() });
  for (const attachment of attachments) parts.push({ type: attachment.kind, path: attachment.path });
  const sent = await call('session/send', { sessionId: state.sessionId, parts });
  if (!sent) {
    discardEmptyTurnBubble(view);
    resetStreaming(view);
    stopTurnClock(view);
    return;
  }
  clearPendingAttachments();
}

// ---------- live events ----------

function ensureStreaming(view: SessionView): StreamingState {
  if (!view.streaming) {
    const wrapper = el('div', 'msg assistant');
    wrapper.append(el('div', 'role', 'assistant'));
    const bubble = el('div', 'bubble md');
    const body = el('div', 'md-body');
    bubble.append(body);
    wrapper.append(bubble);
    view.transcript.append(wrapper);
    view.streaming = { view, text: '', bubble, body, reasoning: '', reasoningPre: null, reasoningToggle: null, reasoningTick: null, reasoningLiveStart: null, chips: null, panels: null, toolsPanel: null, toolsToggle: null, taskToggle: null, toolUses: null };
    ensureAssistBlock(view.streaming);
    scrollTranscript(view);
  }
  return view.streaming;
}

let streamingRenderQueued = false;

/** Re-render the markdown body at most once per frame while text streams in. */
function renderStreamingMarkdown(current: StreamingState): void {
  if (streamingRenderQueued) return;
  streamingRenderQueued = true;
  requestAnimationFrame(() => {
    streamingRenderQueued = false;
    renderMarkdownInto(current.body, current.text);
    scrollTranscript(current.view);
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
function ensureAssistBlock(current: StreamingState): { chips: HTMLElement; panels: HTMLElement } {
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
function ensureTurnStatusChip(current: StreamingState): void {
  const clock = activeTurnClock(current.view);
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
function ensureToolPanel(current: StreamingState): HTMLElement {
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

function appendToolStart(view: SessionView, event: any): void {
  const current = ensureStreaming(view);
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
  const useKind = toolUseKind(tool.name, event.toolType);
  if (useKind) {
    const row = toolUseRowText(useKind, tool.name, event.arguments);
    if (row) recordToolUse(current, useKind, row);
  }
  view.toolMap.set(tool.callId, tool);
  scrollTranscript(view);
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

/** "explore-agent · find the login flow" — what a task call launches, from its arguments. */
function subagentLabelFrom(argumentsText: unknown): string | null {
  try {
    const parsed = typeof argumentsText === 'string' ? JSON.parse(argumentsText) : argumentsText;
    if (parsed && typeof parsed === 'object') {
      const args = parsed as Record<string, unknown>;
      const type = typeof args.subagent_type === 'string' && args.subagent_type.trim() ? args.subagent_type.trim() : null;
      const description = typeof args.description === 'string' && args.description.trim() ? args.description.trim() : null;
      if (type && description) return `${type} · ${description}`;
      if (type) return type;
    }
  } catch {
    return null;
  }
  return null;
}

/**
 * Which chip a tool call is listed under, or null for the generic tool list. The engine tags each
 * tool with its origin (sourceType) as {@code toolType}; task is still recognizable by name on
 * engines that predate the tag.
 */
function toolUseKind(toolName: string, toolType: unknown): ToolUseKind | null {
  if (toolName === 'use_skill') return 'skill';
  if (toolType === 'subagent' || toolType === 'mcp' || toolType === 'api' || toolType === 'agent') return toolType;
  return toolName === 'task' ? 'subagent' : null;
}

function toolUseRowText(kind: ToolUseKind, toolName: string, argumentsText: unknown): string | null {
  if (kind === 'skill') return skillNameFrom(argumentsText);
  if (kind === 'subagent') return subagentLabelFrom(argumentsText) ?? toolName;
  return toolName;
}

const TOOL_USE_CHIPS: Record<ToolUseKind, { label: string; chipClass: string }> = {
  skill: { label: 'Skill use', chipClass: 'skills-chip' },
  subagent: { label: 'Subagent', chipClass: 'subagent-chip' },
  mcp: { label: 'MCP', chipClass: 'mcp-chip' },
  api: { label: 'API tool', chipClass: 'api-chip' },
  agent: { label: 'Agent', chipClass: 'agent-chip' },
};

/** One chip per tool origin next to thinking / tool use; expanding lists everything called this turn. */
function recordToolUse(current: StreamingState, kind: ToolUseKind, text: string): void {
  if (!current.toolUses) current.toolUses = new Map();
  let chip = current.toolUses.get(kind);
  if (!chip) {
    const meta = TOOL_USE_CHIPS[kind];
    const { chips, panels } = ensureAssistBlock(current);
    const toggle = createAssistToggle(meta.label);
    toggle.chip.classList.add(meta.chipClass);
    chip = { toggle, panel: toggle.panel, rows: new Map() };
    current.toolUses.set(kind, chip);
    chips.append(toggle.chip);
    panels.append(toggle.panel);
  }
  const existing = chip.rows.get(text);
  if (existing) {
    existing.count += 1;
    existing.node.textContent = `${text} · ×${existing.count}`;
  } else {
    const node = el('div', 'tool-use-row', text);
    chip.panel.append(node);
    chip.rows.set(text, { node, count: 1 });
  }
  chip.toggle.setLabel(`${TOOL_USE_CHIPS[kind].label} (${chip.rows.size})`);
}

/** Where a memory report lands: the view's live message, else its newest assistant message. */
function memoryWrapper(view: SessionView): HTMLElement | null {
  if (view.streaming) return view.streaming.bubble.parentElement;
  const messages = view.transcript.querySelectorAll('.msg.assistant');
  return messages.length ? (messages[messages.length - 1] as HTMLElement) : null;
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
function handleMemoryEvent(view: SessionView, event: any): void {
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
    const wrapper = memoryWrapper(view);
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
    view.pendingMemoryRun = { wrapper, ui, run };
    renderMemoryRuns(ui);
    scrollTranscript(view);
    return;
  }
  if (phase !== 'COMPLETED') return;
  let entry = view.pendingMemoryRun && view.pendingMemoryRun.run.runId === runId ? view.pendingMemoryRun : null;
  // the message the run started on is gone (session reopened): attach to the current last message
  if (entry && !view.transcript.contains(entry.wrapper)) entry = null;
  if (!entry) {
    const wrapper = memoryWrapper(view);
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
  if (view.pendingMemoryRun === entry) view.pendingMemoryRun = null;
  renderMemoryRuns(entry.ui);
  scrollTranscript(view);
}

// ---------- tasks: background lifecycles and scheduled creations ----------
// Recognized tool results become rows under the message's "Tasks" chip, the same shape as tool use:
// a background task (task / run_bash_command with run_in_background) starts "running" and the chip
// spins until its terminal task_status arrives — possibly turns later — then the row is resolved in
// place. A scheduled-task creation is a static record ("scheduled" + next run), never a spinner:
// the task fires later or on another day, so there is nothing running in this conversation.

const TASK_ENTRY_STATUS_CLASS: Record<string, string> = {
  running: 'running',
  completed: 'success',
  failed: 'failure',
};

function ensureTaskToggle(current: StreamingState): AssistToggle {
  if (!current.taskToggle) {
    const { chips, panels } = ensureAssistBlock(current);
    const toggle = createAssistToggle('Tasks');
    toggle.chip.classList.add('tasks-chip');
    current.taskToggle = toggle;
    chips.append(toggle.chip);
    panels.append(toggle.panel);
  }
  return current.taskToggle;
}

function taskStatusClass(status: string): string {
  return TASK_ENTRY_STATUS_CLASS[status] ?? '';
}

/** The chip spins while any row in this message is still running (same rule as the tools chip). */
function refreshTasksBusy(sample: HTMLElement | null | undefined): void {
  const msg = sample?.closest('.msg');
  const chip = msg?.querySelector('.tasks-chip');
  if (!chip) return;
  chip.classList.toggle('busy', Boolean(msg?.querySelector('.task-entry .task-status.running')));
}

function applyTaskStatus(task: UiTask, status: string): void {
  task.status = status;
  task.statusNode.textContent = status;
  task.statusNode.className = `task-status ${taskStatusClass(status)}`.trim();
  refreshTasksBusy(task.node);
}

function appendTaskEntry(view: SessionView, name: string, desc: string, status: string, detail: string): UiTask {
  const current = ensureStreaming(view);
  const toggle = ensureTaskToggle(current);
  const entry = el('div', 'task-entry');
  const head = el('button', 'task-entry-head') as HTMLButtonElement;
  const chevron = el('span', 'tool-chevron', '▸');
  head.append(chevron, el('span', 'tool-name', name));
  if (desc) head.append(el('span', 'tool-desc', desc));
  const statusNode = el('span', `task-status ${taskStatusClass(status)}`.trim(), status);
  head.append(statusNode);
  const body = el('div', 'tool-entry-body');
  body.hidden = true;
  if (detail) body.append(el('pre', undefined, detail));
  head.onclick = () => {
    body.hidden = !body.hidden;
    chevron.textContent = body.hidden ? '▸' : '▾';
  };
  entry.append(head, body);
  toggle.panel.append(entry);
  toggle.setLabel(`Tasks (${toggle.panel.childElementCount})`);
  const task: UiTask = { status, node: entry, statusNode };
  refreshTasksBusy(entry);
  return task;
}

/** Resolves the async launch (status async_launched whose payload names the task and its output). */
function recognizeAsyncLaunch(view: SessionView, result: string): void {
  if (!result.includes('<task-notification>')) return;
  const taskId = xmlTag(result, 'task-id');
  if (!taskId || view.taskMap.has(taskId)) return;
  const detail = [
    `task id: ${taskId}`,
    xmlTag(result, 'output-ref') ? `output: ${xmlTag(result, 'output-ref')}` : null,
  ].filter(Boolean).join('\n');
  const task = appendTaskEntry(view, xmlTag(result, 'task-type') ?? 'task', xmlTag(result, 'task-description') ?? '', 'running', detail);
  view.taskMap.set(taskId, task);
  downgradeDuplicateTaskNotes(view, taskId);
  // the task can finish before its launch result arrives — apply the parked terminal status
  const pending = view.pendingTaskStatus.get(taskId);
  if (pending) {
    view.pendingTaskStatus.delete(taskId);
    applyTaskStatus(task, pending);
  }
}

/** Parses the "key: value" lines of a tool result (continuation lines belong to the last key). */
function textFields(text: string): Record<string, string> {
  const fields: Record<string, string> = {};
  let lastKey: string | null = null;
  for (const line of text.split(/\r?\n/)) {
    const match = /^([a-z_]+): ?(.*)$/.exec(line);
    if (match && !(match[1] in fields)) {
      fields[match[1]] = match[2];
      lastKey = match[1];
    } else if (lastKey && line.trim()) {
      fields[lastKey] += `\n${line}`;
    }
  }
  return fields;
}

/** Resolves a scheduled_task creation into a static record: name + cron, next run in the details. */
function recognizeScheduledCreation(view: SessionView, result: string): void {
  const fields = textFields(result);
  const id = fields.id ?? '';
  if (id && view.taskMap.has(`scheduled:${id}`)) return;
  const detail = [
    id ? `id: ${id}` : null,
    fields.next_run_at ? `next run: ${fields.next_run_at}` : null,
    fields.timezone ? `timezone: ${fields.timezone}` : null,
    fields.input ? `input: ${fields.input}` : null,
  ].filter(Boolean).join('\n');
  const task = appendTaskEntry(view, fields.name ?? 'scheduled task', fields.cron_expression ?? '', 'scheduled', detail);
  if (id) view.taskMap.set(`scheduled:${id}`, task);
}

/** Terminal task_status: resolve the row its launch created, or park it until that launch arrives. */
function handleTaskStatusEvent(view: SessionView, event: any): void {
  const taskId = typeof event.taskId === 'string' ? event.taskId : '';
  const status = typeof event.status === 'string' ? event.status : '';
  if (!taskId || !status) return;
  const task = view.taskMap.get(taskId);
  if (!task) {
    view.pendingTaskStatus.set(taskId, status);
    return;
  }
  applyTaskStatus(task, status);
  downgradeDuplicateTaskNotes(view, taskId);
}

/** A dead engine took its background tasks down: no terminal status will ever arrive. */
function resolveStaleTaskStatuses(view: SessionView): void {
  for (const statusNode of view.root.querySelectorAll('.task-entry .task-status.running')) {
    statusNode.textContent = 'cancelled';
    statusNode.className = 'task-status';
  }
  for (const chip of view.root.querySelectorAll('.tasks-chip.busy')) chip.classList.remove('busy');
}

/**
 * The terminal notification would show twice once this view holds the task's live row (history
 * rendering and buffered event replay can overlap): keep the row, drop the duplicate note.
 */
function downgradeDuplicateTaskNotes(view: SessionView, taskId: string): void {
  for (const note of view.transcript.querySelectorAll('.task-note')) {
    if ((note as HTMLElement).dataset.taskId === taskId) note.remove();
  }
}

function updateToolResult(view: SessionView, event: any): void {
  if (event.status === 'async_launched' && typeof event.result === 'string') {
    recognizeAsyncLaunch(view, event.result);
  } else if (event.toolName === 'scheduled_task' && typeof event.result === 'string' && event.result.startsWith('Scheduled task created')) {
    recognizeScheduledCreation(view, event.result);
  }
  const tool = view.toolMap.get(event.callId);
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
  const artifact = artifactByCallId.get(event.callId);
  if (artifact) appendToolThumbnail(tool, artifact);
  if (view.pendingApproval?.callId === event.callId) removeApprovalBar(view);
  scrollTranscript(view);
}

/** A generated image/video preview inside its tool card, once the artifact event has named it. */
function appendToolThumbnail(tool: UiTool, entry: ArtifactEntry): void {
  const body = tool.resultNode?.parentElement;
  if (!body || body.querySelector('.tool-artifact-thumb')) return;
  const thumb = el('div', 'tool-artifact-thumb');
  const type = artifactContentType(entry);
  if (type.startsWith('image/') && type !== 'image/svg+xml') {
    const img = el('img') as HTMLImageElement;
    img.src = artifactUrl(entry.path);
    img.alt = entry.name;
    img.loading = 'lazy';
    thumb.append(img);
  } else {
    thumb.append(el('span', 'tool-artifact-icon', entry.kind === 'video' ? '▶' : '▤'), el('span', undefined, entry.name));
  }
  thumb.onclick = () => openArtifactInDock(entry);
  body.append(thumb);
}

function showApprovalBar(view: SessionView, event: any): void {
  removeApprovalBar(view);
  view.pendingApproval = { callId: event.callId, toolName: event.toolName };
  const bar = el('div', 'approval-bar');
  bar.append(el('div', 'approval-title', `⚠ ${event.toolName} needs approval${event.suggestedPattern ? ` · suggested rule ${event.suggestedPattern}` : ''}`));
  bar.append(el('pre', undefined, prettyJson(event.arguments)));
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
      await call('session/approve', { sessionId: view.sessionId, callId: event.callId, decision });
      removeApprovalBar(view);
    };
    actions.append(button);
  }
  bar.append(actions);
  view.approvalBar = bar;
  view.root.append(bar);
}

function removeApprovalBar(view: SessionView): void {
  view.approvalBar?.remove();
  view.approvalBar = null;
  view.pendingApproval = null;
}

function applyComposerStatus(status: string): void {
  state.status = status.toLowerCase();
  if (statusChip) {
    statusChip.textContent = state.status;
    statusChip.className = `composer-status ${state.status}`;
  }
  if (stopButton) {
    stopButton.disabled = state.status !== 'running';
  }
}

/** The composer mirrors whichever session window is on screen. */
function setViewStatus(view: SessionView, status: string): void {
  view.status = status.toLowerCase();
  if (view === mountedView) applyComposerStatus(view.status);
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
  const view = findSessionView(typeof event.sessionId === 'string' ? event.sessionId : state.sessionId);
  if (view) setViewStatus(view, status);
  // A fresh turn re-arms the artifact dock's auto-expand, even if the user collapsed it last turn.
  if (status === 'running' && view === mountedView) state.artifactDismissedForTurn = false;
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
  // Events route to the session's own view — a background session keeps streaming, counting its
  // tools and ticking its clock while the user is looking somewhere else.
  const view = findSessionView(typeof event.sessionId === 'string' ? event.sessionId : state.sessionId);
  if (!view) return;
  if (!view.historyLoaded) {
    // the view is still loading its back-history; replay in order once it is rendered
    view.pendingEvents.push(event);
    return;
  }
  applySessionEvent(view, event);
}

function applySessionEvent(view: SessionView, event: any): void {
  switch (event.type) {
    case 'text_chunk': {
      const current = ensureStreaming(view);
      current.text += event.chunk ?? '';
      renderStreamingMarkdown(current);
      break;
    }
    case 'reasoning_chunk': {
      const current = ensureStreaming(view);
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
      scrollTranscript(view);
      break;
    }
    case 'reasoning_complete': {
      const current = view.streaming;
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
      if (view.pendingApproval) removeApprovalBar(view);
      maybeAutoStartBrowser(event);
      appendToolStart(view, event);
      break;
    case 'tool_result':
      updateToolResult(view, event);
      break;
    case 'tool_approval_request':
      showApprovalBar(view, event);
      break;
    case 'turn_complete': {
      const assistantWrapper = view.streaming?.bubble.parentElement ?? null;
      resolveStaleToolStatuses(assistantWrapper);
      const hasContent = hasAssistantContent(assistantWrapper, view.streaming);
      finishTurnStatus(view, event);
      resetStreaming(view);
      removeApprovalBar(view);
      // A turn that produced nothing leaves no bubble behind — the send-time placeholder goes away
      // and its summary (if any) falls back to a transcript line.
      if (assistantWrapper && !hasContent) assistantWrapper.remove();
      const keptWrapper = hasContent ? assistantWrapper : null;
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
        (keptWrapper ?? view.transcript).append(summary);
        scrollTranscript(view);
      }
      void refreshSessionList();
      void loadSummaries();
      maybeApplyPendingEngineRestart(view);
      break;
    }
    case 'compression':
      if (event.completed !== false) {
        appendNotice(`✦ Context compressed ${event.before_count} → ${event.after_count} · ${event.context_tokens ?? '?'} / ${event.max_context_tokens ?? '?'} tokens`, 'compression', view.transcript);
      }
      break;
    case 'plan_update': {
      renderPlanUpdate(view, event);
      break;
    }
    case 'custom':
      handleCustomEvent(view, event);
      break;
    case 'task_status':
      handleTaskStatusEvent(view, event);
      break;
    case 'environment_output_chunk': {
      const tool = view.toolMap.get(event.callId);
      if (tool?.resultNode) {
        tool.resultNode.style.display = 'block';
        tool.resultNode.textContent += event.chunk ?? '';
      }
      break;
    }
    case 'error': {
      // A failed turn ends with an error event instead of a turn_complete: end its UI state here,
      // or the turn chip keeps spinning and its tools stay "running" for a turn that never resumes.
      const assistantWrapper = view.streaming?.bubble.parentElement ?? null;
      resolveStaleToolStatuses(assistantWrapper);
      const hasContent = hasAssistantContent(assistantWrapper, view.streaming);
      failTurnStatus(view);
      resetStreaming(view);
      removeApprovalBar(view);
      if (assistantWrapper && !hasContent) assistantWrapper.remove();
      appendNotice(`✗ ${event.message ?? 'error'}`, 'error', view.transcript);
      void refreshSessionList();
      void loadSummaries();
      maybeApplyPendingEngineRestart(view);
      break;
    }
    default:
      break;
  }
}

/**
 * A deferred engine reload (reasoning effort changed mid-turn) waits for the viewed turn to end;
 * reloading while a background turn runs would kill it.
 */
function maybeApplyPendingEngineRestart(view: SessionView): void {
  if (!state.pendingEngineRestart) return;
  if (view !== mountedView) return;
  state.pendingEngineRestart = false;
  void restartWorkspaceEngine();
}

function handleCustomEvent(view: SessionView, event: any): void {
  if (event.name === 'artifact') {
    applyArtifactEvent(view, event);
    return;
  }
  if (event.name === 'memory') {
    handleMemoryEvent(view, event);
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
  view.transcript.append(row);
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

// ---------- scheduled tasks ----------

interface ScheduledTaskView {
  id: string;
  sessionId: string;
  sessionTitle?: string;
  workspace?: string;
  name?: string;
  cronExpression?: string;
  timezone?: string;
  input?: string;
  enabled?: boolean;
  nextRunAt?: string;
  lastRunAt?: string;
  lastRunStatus?: string;
  runCount?: number;
  disabledReason?: string;
  runs?: Array<Record<string, unknown>>;
}

let scheduleFilterWorkspace = 'all';
let scheduleEnabledOnly = false;
let selectedScheduleId: string | null = null;

async function renderSchedulesPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Scheduled tasks', 'Session-bound tasks from every workspace. They execute while core-ai-desktop is running.');
  if (!state.workspace) {
    body.append(el('div', 'empty-state', 'Connect a workspace to load scheduled tasks.'));
    return root;
  }
  const controls = el('div', 'schedule-controls');
  const workspaceFilter = el('select', 'input');
  for (const [value, label] of [['all', 'All workspaces'], ['current', 'This workspace']]) {
    const option = el('option', undefined, label);
    option.value = value;
    workspaceFilter.append(option);
  }
  workspaceFilter.value = scheduleFilterWorkspace;
  workspaceFilter.onchange = () => { scheduleFilterWorkspace = workspaceFilter.value; void renderContent(); };
  controls.append(workspaceFilter);
  const enabledLabel = el('label', 'schedule-filter');
  const enabled = el('input') as HTMLInputElement;
  enabled.type = 'checkbox';
  enabled.checked = scheduleEnabledOnly;
  enabled.onchange = () => { scheduleEnabledOnly = enabled.checked; void renderContent(); };
  enabledLabel.append(enabled, document.createTextNode('Enabled only'));
  controls.append(enabledLabel);
  body.append(controls);
  const backgroundSettings = await window.coreai.getSettings();
  state.info.backgroundScheduling = backgroundSettings?.backgroundScheduling === true;
  if (!state.info.backgroundScheduling) {
    const notice = el('div', 'notice');
    notice.append(document.createTextNode('Tasks stop when you close this window. '));
    const settingsButton = el('button', 'btn small', 'Run in background');
    settingsButton.onclick = () => { state.nav = 'settings'; renderSidebar(); void renderContent(); };
    notice.append(settingsButton);
    body.append(notice);
  }
  const response = await callEnvelope('schedules/list', { scope: 'all' });
  if (!response.ok) {
    body.append(el('div', 'notice error', response.error?.message ?? 'Could not load scheduled tasks'));
    return root;
  }
  const raw = response.result as any;
  let tasks: ScheduledTaskView[] = Array.isArray(raw) ? raw : (raw?.tasks ?? []);
  if (scheduleFilterWorkspace === 'current') tasks = tasks.filter((task) => task.workspace === state.workspace);
  if (scheduleEnabledOnly) tasks = tasks.filter((task) => task.enabled);
  if (!tasks.length) {
    body.append(el('div', 'empty-state', 'No scheduled tasks found. Create one in a conversation with the scheduled_task tool.'));
    return root;
  }
  const table = el('table', 'schedule-table');
  const head = el('thead');
  const headerRow = el('tr');
  for (const label of ['Status', 'Name', 'Next run', 'Last run', 'Session', 'Workspace']) {
    headerRow.append(el('th', undefined, label));
  }
  head.append(headerRow);
  table.append(head);
  const rows = el('tbody');
  for (const task of tasks) {
    const row = el('tr', selectedScheduleId === task.id ? 'selected' : '');
    const status = el('td');
    status.append(el('span', task.enabled ? 'schedule-status enabled' : 'schedule-status disabled', task.enabled ? 'Enabled' : 'Disabled'));
    if (task.disabledReason) status.title = task.disabledReason;
    row.append(status);
    const name = el('td');
    name.append(el('strong', undefined, task.name ?? '(unnamed)'));
    name.append(el('div', 'hint', `${task.cronExpression ?? ''} (${task.timezone ?? 'UTC'})`));
    row.append(name);
    const next = el('td', undefined, task.enabled ? fmtTime(task.nextRunAt) || '—' : '—');
    if (task.nextRunAt) next.title = task.nextRunAt;
    row.append(next);
    const last = el('td', undefined, task.lastRunAt ? `${fmtTime(task.lastRunAt)} · ${task.lastRunStatus ?? ''}` : '—');
    row.append(last);
    row.append(el('td', undefined, task.sessionTitle ?? (task.sessionId ?? '').slice(0, 12)));
    row.append(el('td', undefined, task.workspace ? baseName(task.workspace) : '(legacy)'));
    row.onclick = () => { selectedScheduleId = task.id; void openScheduleDetail(task); };
    rows.append(row);
  }
  table.append(rows);
  body.append(table);
  return root;
}

async function openScheduleDetail(task: ScheduledTaskView): Promise<void> {
  const workspace = task.workspace ?? state.workspace;
  if (!workspace) return;
  const detail = await window.coreai.call(workspace, 'schedules/get', { id: task.id });
  if (!detail.ok) {
    toast(detail.error?.message ?? 'Could not load task detail', 'error');
    return;
  }
  const value = (detail.result?.task ?? detail.result) as ScheduledTaskView;
  if (!value.workspace) value.workspace = workspace;
  const runs = (detail.result?.runs ?? value.runs ?? []) as Array<Record<string, unknown>>;
  const drawer = el('div', 'schedule-drawer');
  const header = el('div', 'schedule-drawer-header');
  header.append(el('h2', undefined, value.name ?? '(unnamed)'));
  const close = el('button', 'btn small', 'Close');
  close.onclick = () => drawer.remove();
  header.append(close);
  drawer.append(header);
  const actions = el('div', 'schedule-actions');
  const toggle = el('button', 'btn small', value.enabled ? 'Disable' : 'Enable');
  toggle.onclick = async () => {
    const result = await window.coreai.call(workspace, 'schedules/update', { id: value.id, enabled: !value.enabled });
    if (result.ok) {
      toast(value.enabled ? 'Task disabled' : 'Task enabled');
      drawer.remove();
      void renderContent();
    } else {
      toast(result.error?.message ?? 'Update failed', 'error');
    }
  };
  actions.append(toggle);
  const runNow = el('button', 'btn small primary', 'Run now');
  runNow.onclick = async () => {
    const result = await window.coreai.call(workspace, 'schedules/trigger', { id: value.id });
    if (result.ok) {
      toast('Scheduled task triggered');
      drawer.remove();
      void renderContent();
    } else {
      toast(result.error?.message ?? 'Trigger failed', 'error');
    }
  };
  actions.append(runNow);
  const open = el('button', 'btn small', 'Open session');
  open.onclick = async () => { drawer.remove(); if (state.workspace !== workspace) await connect(workspace); await openSession(value.sessionId); };
  actions.append(open);
  const remove = el('button', 'btn small danger', 'Delete');
  remove.onclick = async () => {
    if (!window.confirm(`Delete scheduled task “${value.name ?? value.id}”?`)) return;
    const result = await window.coreai.call(workspace, 'schedules/delete', { id: value.id });
    if (result.ok) { drawer.remove(); selectedScheduleId = null; toast('Task deleted'); void renderContent(); }
    else toast(result.error?.message ?? 'Delete failed', 'error');
  };
  actions.append(remove);
  drawer.append(actions);
  const definition = el('dl', 'settings-grid');
  for (const [label, text] of [['Cron', value.cronExpression], ['Timezone', value.timezone], ['Next run', value.nextRunAt], ['Last run', value.lastRunAt], ['Workspace', workspace], ['Session', value.sessionTitle ?? value.sessionId]]) {
    definition.append(el('dt', undefined, label), el('dd', undefined, text ?? '—'));
  }
  drawer.append(definition);
  drawer.append(el('h3', undefined, 'Input'));
  const input = el('pre', 'schedule-input', value.input ?? '');
  const copyInput = el('button', 'btn small', 'Copy input');
  copyInput.onclick = async () => { await navigator.clipboard.writeText(value.input ?? ''); toast('Input copied'); };
  drawer.append(copyInput, input);
  drawer.append(el('h3', undefined, 'Recent runs'));
  if (!runs.length) drawer.append(el('div', 'hint', 'No runs yet'));
  else {
    const list = el('div', 'schedule-runs');
    for (const run of runs.slice(0, 20)) list.append(el('div', 'schedule-run', `${String(run.firedAt ?? '')} · ${String(run.status ?? '')}${run.error ? ` · ${String(run.error)}` : ''}`));
    drawer.append(list);
  }
  for (const existing of document.querySelectorAll('.schedule-drawer')) existing.remove();
  document.body.append(drawer);
}

// ---------- hub panel ----------
// Server capability bridge: MCP tools / API operations / agents / LLM_CALL definitions come from
// the one-shot hub catalog, skills from the skill hub (pull installs the same managed copy as
// `core-ai-cli skill pull`). Discovery is read-only; management stays in the web console.

type HubKind = 'mcp' | 'api' | 'agent' | 'llm_call' | 'skill';
type HubFilter = 'all' | HubKind;

interface HubRow {
  kind: HubKind;
  name: string;
  group: string | null;
  description: string | null;
  namespace: string | null;
  qualifiedName: string | null;
  resourceCount: number | null;
  stale: boolean;
  refId: string | null;
}

const HUB_KIND_LABELS: Record<HubKind, string> = {
  mcp: 'MCP',
  api: 'API tool',
  agent: 'Agent',
  llm_call: 'LLM call',
  skill: 'Skill',
};

const HUB_FILTERS: Array<[HubFilter, string]> = [
  ['all', 'All'],
  ['mcp', 'MCP'],
  ['api', 'API tools'],
  ['agent', 'Agents'],
  ['llm_call', 'LLM calls'],
  ['skill', 'Skills'],
];

const HUB_SECTION_CAP = 50;

function hubSignInState(retry?: () => void): HTMLElement {
  const empty = el('div', 'empty-state');
  empty.append(el('div', 'big', 'Not connected'));
  empty.append(el('div', 'notice', 'Sign in to browse server capabilities — MCP tools, API tools, agents and skills your account can use. Local sessions work without it.'));
  const button = el('button', 'btn primary', 'Sign in');
  button.style.marginTop = '10px';
  button.onclick = () => openLoginDialog(retry);
  empty.append(button);
  return empty;
}

function hubUnreachableState(detail: string, retry: () => Promise<void>): HTMLElement {
  const empty = el('div', 'empty-state');
  empty.append(el('div', 'big', 'Server unreachable'));
  empty.append(el('div', 'notice', detail));
  const button = el('button', 'btn', 'Retry');
  button.style.marginTop = '10px';
  button.onclick = () => void retry();
  empty.append(button);
  return empty;
}

function hubRows(catalogData: any, skillsData: any): HubRow[] {
  const rows: HubRow[] = [];
  const kinds = HUB_FILTERS.map(([key]) => key);
  for (const tool of catalogData?.tools ?? []) {
    const kind = String(tool.kind ?? '') as HubKind;
    if (!kinds.includes(kind)) continue;
    rows.push({
      kind,
      name: String(tool.path || tool.name || ''),
      group: tool.group ? String(tool.group) : null,
      description: tool.description ? String(tool.description) : null,
      namespace: null,
      qualifiedName: null,
      resourceCount: null,
      stale: Boolean(tool.stale),
      refId: tool.refId ? String(tool.refId) : null,
    });
  }
  for (const skill of skillsData?.skills ?? []) {
    rows.push({
      kind: 'skill',
      name: String(skill.name ?? ''),
      group: skill.namespace ? String(skill.namespace) : null,
      description: skill.description ? String(skill.description) : null,
      namespace: skill.namespace ? String(skill.namespace) : null,
      qualifiedName: skill.qualifiedName ? String(skill.qualifiedName) : null,
      resourceCount: typeof skill.resourceCount === 'number' ? skill.resourceCount : null,
      stale: false,
      refId: null,
    });
  }
  return rows;
}

/** Where `core-ai-cli skill pull` puts a hub skill; used to mark rows already installed locally. */
function hubLocalKey(row: HubRow): string {
  const home = String(state.info?.homedir ?? '').replace(/\\/g, '/').toLowerCase();
  return `${home}/.core-ai/skills/${(row.namespace ?? '').toLowerCase()}/${row.name.toLowerCase()}`;
}

function hubInstalledSet(local: Envelope): Set<string> {
  const set = new Set<string>();
  for (const skill of local.ok ? local.result?.skills ?? [] : []) {
    set.add(String(skill?.path ?? '').replace(/\\/g, '/').toLowerCase());
  }
  return set;
}

function buildHubRow(row: HubRow, installed: Set<string>, onPulled: (row: HubRow, actions: HTMLElement) => void,
                     mount: HubHelperMount): HTMLElement {
  const item = el('div', 'hub-row');
  const head = el('div', 'hub-row-head');
  head.append(el('span', 'hub-row-name', row.name));
  let meta = HUB_KIND_LABELS[row.kind];
  if (row.group) meta += ` · ${row.group}`;
  if (row.kind === 'skill' && row.resourceCount != null) meta += ` · ${row.resourceCount} files`;
  if (row.kind === 'agent' && row.refId && mount.loaded.has(row.refId)) meta += ' · helper';
  head.append(el('span', 'hub-row-meta', meta));
  if (row.stale) head.append(el('span', 'chip', 'stale'));
  head.append(el('span', 'spacer'));
  const actions = el('span', 'hub-row-actions');
  if (row.kind === 'skill') {
    if (installed.has(hubLocalKey(row))) actions.append(el('span', 'hub-installed', 'installed'));
    const pull = el('button', 'btn small', 'Pull');
    pull.title = `Pull ${row.qualifiedName ?? row.name} into ~/.core-ai/skills`;
    pull.onclick = () => void pullHubSkill(row, pull, actions, onPulled);
    actions.append(pull);
  }
  if (row.kind === 'agent' && row.refId && !mount.loaded.has(row.refId)) {
    const load = el('button', 'btn small', 'Load as helper');
    load.title = mount.connected
      ? `Mount ${row.name} as a delegation tool of your local agent (new sessions)`
      : 'Sign in to core-ai-server to load helpers';
    load.disabled = !mount.connected;
    load.onclick = () => void loadHelper(row, load, mount);
    actions.append(load);
  }
  if (actions.childElementCount) head.append(actions);
  item.append(head);
  if (row.description) {
    const desc = el('div', 'hub-row-desc', row.description);
    desc.title = row.description;
    item.append(desc);
  }
  return item;
}

async function pullHubSkill(
  row: HubRow,
  button: HTMLButtonElement,
  actions: HTMLElement,
  onPulled: (row: HubRow, actions: HTMLElement) => void,
): Promise<void> {
  const label = row.qualifiedName ?? `${row.namespace ?? ''}/${row.name}`;
  const params: Record<string, unknown> = { namespace: row.namespace, name: row.name };
  button.disabled = true;
  button.textContent = 'Pulling…';
  let outcome = await callEnvelope('hub/skills/pull', params);
  if (!outcome.ok && outcome.error?.data?.code === 'SKILL_CONFLICT') {
    const confirmed = window.confirm(`${label} exists locally without a hub marker, or was modified locally.\n\nOverwrite the local copy?`);
    if (confirmed) {
      outcome = await callEnvelope('hub/skills/pull', { ...params, force: true });
    }
  }
  if (outcome.ok) {
    toast(outcome.result?.status === 'up_to_date'
      ? `Skill ${label} is already up to date`
      : `Pulled ${label} → ${displayPath(outcome.result?.dir ?? '')}`);
    button.textContent = 'Pulled';
    button.disabled = false;
    onPulled(row, actions);
    return;
  }
  toast(`✗ Pull failed: ${outcome.error?.message ?? 'unknown error'}`, 'error');
  button.textContent = 'Pull';
  button.disabled = false;
}

// ---------- helpers (server agents mounted as delegation tools of the local agent) ----------

interface HubHelper {
  agentId: string;
  name: string | null;
  description: string | null;
  whenToUse: string | null;
  enabled: boolean;
  addedAt: string | null;
}

interface HubHelpersData {
  helpers: HubHelper[];
  max: number;
  connected: boolean;
}

/** Shared state a hub row needs to render and drive the "Load as helper" action. */
interface HubHelperMount {
  loaded: Set<string>;
  connected: boolean;
  onLoaded: (agentId: string) => void;
}

function normalizeHelpers(data: any): HubHelpersData {
  return {
    helpers: Array.isArray(data?.helpers) ? (data.helpers as HubHelper[]) : [],
    max: typeof data?.max === 'number' ? data.max : 5,
    connected: Boolean(data?.connected),
  };
}

async function loadHelper(row: HubRow, button: HTMLButtonElement, mount: HubHelperMount): Promise<void> {
  if (!row.refId) return;
  button.disabled = true;
  button.textContent = 'Loading…';
  const outcome = await callEnvelope('helpers/add', { agentId: row.refId });
  if (outcome.ok) {
    const name = String(outcome.result?.helper?.name ?? row.name);
    toast(`Loaded ${name} as a helper — new sessions can delegate to it`);
    mount.onLoaded(row.refId);
    return;
  }
  toast(`✗ Load failed: ${outcome.error?.message ?? 'unknown error'}`, 'error');
  button.textContent = 'Load as helper';
  button.disabled = !mount.connected;
}

function renderHelpersSection(data: HubHelpersData, onChanged: () => void): HTMLElement {
  const section = el('div', 'hub-helpers');
  const header = el('div', 'hub-section');
  header.append(el('span', 'hub-section-title', 'Helpers'));
  header.append(el('span', 'hub-section-count', `${data.helpers.length}/${data.max}`));
  section.append(header);
  section.append(el('div', 'hub-helper-note', data.connected
    ? 'Server agents your local agent can delegate to. Changes apply to new sessions.'
    : 'Sign in to core-ai-server to add helpers; existing ones stay listed.'));
  if (!data.helpers.length) {
    section.append(el('div', 'notice', 'No helpers yet — use “Load as helper” on an agent below.'));
    return section;
  }
  for (const helper of data.helpers) {
    section.append(buildHelperRow(helper, onChanged));
  }
  return section;
}

function buildHelperRow(helper: HubHelper, onChanged: () => void): HTMLElement {
  const row = el('div', 'hub-row hub-helper-row');
  const head = el('div', 'hub-row-head');
  head.append(el('span', 'hub-row-name', String(helper.name ?? helper.agentId)));
  head.append(el('span', 'hub-row-meta', helper.enabled ? 'helper' : 'paused'));
  head.append(el('span', 'spacer'));
  const actions = el('span', 'hub-row-actions');

  const edit = el('button', 'btn small', 'Edit hint');
  edit.title = 'Tell the model when to delegate to this agent';
  edit.onclick = () => {
    const editor = el('div', 'hub-helper-editor');
    const input = el('input');
    input.value = helper.whenToUse ?? '';
    input.placeholder = 'e.g. HR policy questions — ask it before answering';
    const save = el('button', 'btn small', 'Save');
    save.onclick = async () => {
      save.disabled = true;
      const outcome = await callEnvelope('helpers/update', { agentId: helper.agentId, whenToUse: input.value });
      if (!outcome.ok) {
        toast(`✗ ${outcome.error?.message ?? 'update failed'}`, 'error');
        save.disabled = false;
        return;
      }
      toast('Hint saved — new sessions use it');
      onChanged();
    };
    const cancel = el('button', 'btn small', 'Cancel');
    cancel.onclick = () => editor.remove();
    editor.append(input, save, cancel);
    row.append(editor);
  };

  const toggle = el('button', 'btn small', helper.enabled ? 'Pause' : 'Resume');
  toggle.onclick = async () => {
    toggle.disabled = true;
    const outcome = await callEnvelope('helpers/update', { agentId: helper.agentId, enabled: !helper.enabled });
    if (!outcome.ok) {
      toast(`✗ ${outcome.error?.message ?? 'update failed'}`, 'error');
      toggle.disabled = false;
      return;
    }
    toast(helper.enabled ? 'Helper paused — new sessions skip it' : 'Helper resumed');
    onChanged();
  };

  const remove = el('button', 'btn small', 'Remove');
  remove.onclick = async () => {
    if (!window.confirm(`Remove helper ${helper.name ?? helper.agentId}?`)) return;
    remove.disabled = true;
    const outcome = await callEnvelope('helpers/remove', { agentId: helper.agentId });
    if (!outcome.ok) {
      toast(`✗ ${outcome.error?.message ?? 'remove failed'}`, 'error');
      remove.disabled = false;
      return;
    }
    toast('Helper removed — new sessions skip it');
    onChanged();
  };

  actions.append(edit, toggle, remove);
  head.append(actions);
  row.append(head);
  row.append(el('div', 'hub-row-desc', helper.whenToUse
    ? helper.whenToUse
    : 'No “use it when” hint yet — the model routes on the agent description alone.'));
  return row;
}

function renderHubFooter(footer: HTMLElement, counts: Record<HubKind, number>, sections: string[] | null, generatedAt: string | null): void {
  footer.replaceChildren();
  const totals = `${counts.mcp} MCP tools · ${counts.api} API operations · ${counts.agent} agents · ${counts.llm_call} LLM calls · ${counts.skill} skills`;
  footer.append(el('div', 'hint', generatedAt ? `${totals} · catalog generated ${fmtTime(generatedAt)}` : totals));
  const missing = sections == null ? [] : HUB_FILTERS
    .filter(([key]) => key !== 'all' && key !== 'skill' && !sections.includes(key))
    .map(([key]) => HUB_KIND_LABELS[key as HubKind]);
  if (missing.length) {
    footer.append(el('div', 'notice', `No read access to: ${missing.join(', ')}`));
  }
  const line = el('div', 'notice', 'Read-only discovery — managing servers, agents and skills stays in the web console. ');
  if (state.auth?.serverUrl) {
    const link = el('a', 'hub-link', 'Open web console ↗');
    link.href = state.auth.serverUrl;
    link.onclick = (event) => {
      event.preventDefault();
      void window.coreai.openExternal(state.auth?.serverUrl as string);
    };
    line.append(link);
  }
  footer.append(line);
}

async function renderHubPanel(): Promise<HTMLElement> {
  const host = hostOf(state.auth?.serverUrl);
  const { root, body } = panel('Hub', host
    ? `Server capabilities via the hub — signed in to ${host}. Load agents as helpers; managing servers and skills stays in the web console.`
    : 'Server capabilities via the hub — MCP tools, API tools, agents and skills.');

  const view: { filter: HubFilter; query: string } = { filter: 'all', query: '' };

  const build = async (): Promise<void> => {
    if (!state.auth?.loggedIn) {
      const helperList = await callEnvelope('helpers/list');
      const data = normalizeHelpers(helperList.ok ? helperList.result : null);
      const wrap = el('div');
      if (data.helpers.length) wrap.append(renderHelpersSection(data, () => void build()));
      wrap.append(hubSignInState(() => void build()));
      body.replaceChildren(wrap);
      return;
    }
    body.replaceChildren(el('div', 'notice', 'Loading the capability catalog…'));
    const [catalog, skills, local, helperList] = await Promise.all([
      callEnvelope('hub/catalog'),
      callEnvelope('hub/skills', { limit: 200 }),
      callEnvelope('skills/list'),
      callEnvelope('helpers/list'),
    ]);
    const catalogData = catalog.ok ? catalog.result ?? {} : null;
    const skillsData = skills.ok ? skills.result ?? {} : null;
    const helpersData = normalizeHelpers(helperList.ok ? helperList.result : null);
    if (catalogData?.reason === 'not_logged_in' || skillsData?.reason === 'not_logged_in') {
      const wrap = el('div');
      if (helpersData.helpers.length) wrap.append(renderHelpersSection(helpersData, () => void build()));
      wrap.append(hubSignInState(() => void build()));
      body.replaceChildren(wrap);
      return;
    }
    const catalogError = !catalog.ok
      ? catalog.error?.message ?? 'request failed'
      : catalogData?.connected === false ? String(catalogData.message ?? catalogData.reason) : null;
    const skillsError = !skills.ok
      ? skills.error?.message ?? 'request failed'
      : skillsData?.connected === false ? String(skillsData.message ?? skillsData.reason) : null;
    if (catalogError && skillsError) {
      const wrap = el('div');
      if (helpersData.helpers.length) wrap.append(renderHelpersSection(helpersData, () => void build()));
      wrap.append(hubUnreachableState(String(catalogError), build));
      body.replaceChildren(wrap);
      return;
    }

    const rows = hubRows(catalogData, skillsData);
    const installed = hubInstalledSet(local);
    const counts: Record<HubKind, number> = { mcp: 0, api: 0, agent: 0, llm_call: 0, skill: 0 };
    for (const row of rows) counts[row.kind] += 1;

    const loadedIds = new Set(helpersData.helpers.map((helper) => helper.agentId));
    const mount: HubHelperMount = {
      loaded: loadedIds,
      connected: helpersData.connected,
      onLoaded: (agentId: string) => {
        loadedIds.add(agentId);
        void refreshHelpers();
      },
    };
    const helpersHost = el('div');
    const refreshHelpers = async (): Promise<void> => {
      const list = await callEnvelope('helpers/list');
      const data = normalizeHelpers(list.ok ? list.result : null);
      loadedIds.clear();
      for (const helper of data.helpers) loadedIds.add(helper.agentId);
      mount.connected = data.connected;
      helpersHost.replaceChildren(renderHelpersSection(data, () => void refreshHelpers()));
      render();
    };
    helpersHost.replaceChildren(renderHelpersSection(helpersData, () => void refreshHelpers()));

    const toolbar = el('div', 'hub-toolbar');
    const search = el('input', 'hub-search') as HTMLInputElement;
    search.type = 'search';
    search.placeholder = 'Search tools, agents, skills…';
    search.value = view.query;
    toolbar.append(search);
    const chips = el('div', 'hub-chips');
    const chipEls = new Map<HubFilter, HTMLButtonElement>();
    for (const [key, label] of HUB_FILTERS) {
      const chip = el('button', 'hub-chip', `${label} ${key === 'all' ? rows.length : counts[key]}`);
      chipEls.set(key, chip);
      chip.onclick = () => {
        view.filter = key;
        render();
      };
      chips.append(chip);
    }
    toolbar.append(chips);
    const refresh = el('button', 'btn small hub-refresh', 'Refresh');
    refresh.title = 'Fetch the catalog and skill list again';
    refresh.onclick = () => void build();
    toolbar.append(refresh);

    const list = el('div', 'hub-list');
    const footer = el('div', 'hub-footer');

    const onPulled = (row: HubRow, actions: HTMLElement): void => {
      installed.add(hubLocalKey(row));
      if (!actions.querySelector('.hub-installed')) {
        actions.prepend(el('span', 'hub-installed', 'installed'));
      }
    };

    const render = (): void => {
      for (const [key, chip] of chipEls) chip.classList.toggle('active', view.filter === key);
      const query = view.query.trim().toLowerCase();
      const visible = rows.filter((row) => (view.filter === 'all' || row.kind === view.filter)
        && (!query || `${row.name} ${row.group ?? ''} ${row.description ?? ''}`.toLowerCase().includes(query)));
      list.replaceChildren();
      if (catalogError) list.append(el('div', 'notice error', `Capability catalog unavailable: ${catalogError}`));
      if (skillsError) list.append(el('div', 'notice error', `Skill catalog unavailable: ${skillsError}`));
      for (const [key] of HUB_FILTERS) {
        if (key === 'all') continue;
        const sectionRows = visible.filter((row) => row.kind === key);
        if (!sectionRows.length) continue;
        const header = el('div', 'hub-section');
        header.append(el('span', 'hub-section-title', HUB_KIND_LABELS[key]));
        header.append(el('span', 'hub-section-count', String(sectionRows.length)));
        list.append(header);
        for (const row of sectionRows.slice(0, HUB_SECTION_CAP)) {
          list.append(buildHubRow(row, installed, onPulled, mount));
        }
        if (sectionRows.length > HUB_SECTION_CAP) {
          list.append(el('div', 'notice', `showing first ${HUB_SECTION_CAP} of ${sectionRows.length} — refine the search to narrow`));
        }
      }
      if (!visible.length) list.append(el('div', 'notice', 'No matches.'));
      renderHubFooter(footer, counts, catalogError ? null : catalogData?.sections ?? [], catalogData?.generatedAt ?? null);
    };

    search.oninput = () => {
      view.query = search.value;
      render();
    };
    body.replaceChildren(helpersHost, toolbar, list, footer);
    render();
  };

  await build();
  return root;
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
    const preview = el('button', 'btn small', 'Preview');
    preview.onclick = async () => {
      await showConversationView();
      openArtifactInDock({
        path: String(file.path),
        name: String(file.name ?? baseName(String(file.path))),
        kind: typeof file.kind === 'string' ? file.kind : 'file',
        size: Number(file.size ?? 0),
        contentType: null,
        createdAt: typeof file.createdAt === 'string' ? file.createdAt : null,
        tool: null,
      });
    };
    const open = el('button', 'btn small', 'Open');
    open.style.marginLeft = '6px';
    open.onclick = async () => {
      const error = await window.coreai.openPath(state.workspace as string, file.path);
      if (error) toast(error, 'error');
    };
    const reveal = el('button', 'btn small', 'Reveal');
    reveal.style.marginLeft = '6px';
    reveal.onclick = () => window.coreai.revealPath(state.workspace as string, file.path);
    actions.append(preview, open, reveal);
    row.append(actions);
    table.append(row);
  }
  body.append(table);
  return root;
}

async function renderSkillsPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Skills', 'Workspace and user-level skills (~/.core-ai/skills and {workspace}/.core-ai/skills)');

  const skillState = (skill: any, connected: boolean): { label: string; update: boolean; force: boolean } => {
    switch (String(skill.state ?? '')) {
      case 'outdated': return { label: 'Update available', update: true, force: false };
      case 'modified': return { label: 'Modified locally', update: true, force: true };
      case 'up-to-date': return { label: 'Up to date', update: false, force: false };
      case 'unverified': return { label: 'Not verified', update: false, force: false };
      case 'local': return { label: 'Local only', update: false, force: false };
      default: return { label: connected ? 'Up to date' : 'Unchecked', update: false, force: false };
    }
  };

  const runUpdate = async (qualifiedName: string | null, force: boolean): Promise<void> => {
    if (force && !window.confirm(`Overwrite local changes in ${qualifiedName ?? 'every hub skill'}?`)) return;
    const outcome = await callEnvelope('hub/skills/update', {
      ...(qualifiedName ? { name: qualifiedName } : {}),
      ...(force ? { force: true } : {}),
    });
    if (!outcome.ok) {
      toast(`Skill update failed: ${outcome.error?.message ?? 'request failed'}`, 'error');
      return;
    }
    const updated: string[] = outcome.result?.updated ?? [];
    const skipped: string[] = outcome.result?.skipped ?? [];
    toast(updated.length ? `Updated ${updated.join(', ')}`
      : skipped.length ? `Nothing to update (${skipped.length} skipped)` : 'Nothing to update');
    await refresh();
  };

  const refresh = async (): Promise<void> => {
    body.replaceChildren();
    const listed = await call('skills/list', {});
    if (!listed) return;
    const skills: any[] = listed.skills ?? [];
    if (!skills.length) {
      body.append(el('div', 'notice', 'No skills yet.'));
      return;
    }
    if (!listed.connected) {
      body.append(el('div', 'notice', 'Sign in to core-ai-server to check for skill updates.'));
    }
    if (skills.some((skill: any) => skill.state === 'outdated')) {
      const bar = el('div');
      bar.style.margin = '0 0 10px';
      const all = el('button', 'btn small primary', 'Update all');
      all.onclick = () => void runUpdate(null, false);
      bar.append(all);
      body.append(bar);
    }
    const table = el('table');
    const headRow = el('tr');
    for (const label of ['Name', 'Source', 'State', 'Path']) headRow.append(el('th', undefined, label));
    table.append(headRow);
    for (const skill of skills) {
      const row = el('tr');
      const info = skillState(skill, Boolean(listed.connected));
      row.append(el('td', undefined, skill.name));
      row.append(el('td', undefined, skill.source));
      const stateCell = el('td', undefined, info.label);
      if (info.update && skill.qualifiedName) {
        const button = el('button', 'btn small primary', info.force ? 'Overwrite' : 'Update');
        button.style.marginLeft = '8px';
        button.onclick = () => void runUpdate(String(skill.qualifiedName), info.force);
        stateCell.append(button);
      }
      row.append(stateCell);
      row.append(el('td', 'mono', skill.path));
      table.append(row);
    }
    body.append(table);
  };

  await refresh();
  return root;
}

/** Memory files are markdown; render them with the assistant palette, plain text stays verbatim. */
function memoryPreview(content: string, path: string): HTMLElement {
  if (!/\.(md|markdown)$/i.test(path)) return el('pre', undefined, content);
  const wrap = el('div', 'bubble md');
  const body = el('div', 'md-body');
  wrap.append(body);
  renderMarkdownInto(body, content);
  return wrap;
}

async function renderMemoryPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Memory', 'Global preferences (~/.core-ai/MEMORY.md) + knowledge wiki / episodes / daily-logs ({workspace}/.core-ai)');
  const listed = await call('memory/list', { limit: 300 });
  if (!listed) return root;
  const files: any[] = listed.files ?? [];
  const layout = el('div', 'memory-list');
  const items = el('div', 'file-items');
  const preview = el('div', 'file-preview');
  preview.append(el('div', 'notice', 'Select a file on the left to preview'));
  for (const file of files) {
    const item = el('button', 'file-item');
    if (String(file.path).startsWith('global/')) item.classList.add('global');
    item.append(el('span', 'path', file.path));
    item.append(el('span', 'meta', `${fmtBytes(file.size)} · ${fmtTime(file.modifiedAt)}`));
    item.onclick = async () => {
      const read = await call('memory/read', { path: file.path });
      if (!read) return;
      preview.replaceChildren(memoryPreview(read.content ?? '', file.path));
    };
    items.append(item);
  }
  layout.append(items, preview);
  body.append(layout);
  return root;
}

async function renderSettingsPanel(): Promise<HTMLElement> {
  const { root, body } = panel('Settings', 'Engine, approval policy, scheduled tasks and permission rules');
  const info = state.info ?? (await window.coreai.info());
  state.info = info;
  const grid = el('dl', 'settings-grid');
  const rows: Array<[string, string]> = [
    ['Workspace', state.workspace ? workspaceLabel(state.workspace) : 'default (not created yet)'],
    ['Engine path', `${info.enginePath}${info.engineExists ? '' : '  (missing — run :core-ai-cli:installDist)'}`],
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

  if (state.workspace) {
    const maxTurn = await call('maxturn/get', {});
    if (maxTurn) {
      const fallback = String(maxTurn.value ?? 100);
      grid.append(el('dt', undefined, 'Max turns'));
      const turnsCell = el('dd');
      const turnsInput = el('input', 'settings-input');
      turnsInput.type = 'number';
      turnsInput.min = '1';
      turnsInput.step = '1';
      turnsInput.value = fallback;
      turnsInput.onchange = async () => {
        const value = Number.parseInt(turnsInput.value, 10);
        if (!Number.isInteger(value) || value < 1) {
          turnsInput.value = fallback;
          toast('Max turns must be a positive integer', 'error');
          return;
        }
        const applied = await call('maxturn/set', { value });
        if (!applied) {
          turnsInput.value = fallback;
          return;
        }
        if (state.status === 'running') {
          state.pendingEngineRestart = true;
          toast(`Max turns: ${value} — applies when this turn ends (engine reload)`);
          return;
        }
        toast(`Max turns: ${value} — reloading engine…`);
        await restartWorkspaceEngine();
      };
      turnsCell.append(turnsInput);
      turnsCell.append(el('div', 'hint', 'Maximum model turns per message (agent.max.turn, default 100). Shared with the CLI; the engine reloads to apply it.'));
      grid.append(turnsCell);
    }
  }
  body.append(grid);

  body.append(el('h2', undefined, 'Scheduled tasks'));
  body.append(el('div', 'hint', 'Keep the app in the system tray so local scheduled tasks can execute after the window is closed.'));
  const backgroundLabel = el('label', 'schedule-filter');
  const backgroundToggle = el('input') as HTMLInputElement;
  backgroundToggle.type = 'checkbox';
  const currentSettings = await window.coreai.getSettings();
  backgroundToggle.checked = currentSettings?.backgroundScheduling === true;
  backgroundToggle.onchange = async () => {
    await window.coreai.setSettings({ backgroundScheduling: backgroundToggle.checked });
    state.info.backgroundScheduling = backgroundToggle.checked;
    toast(backgroundToggle.checked ? 'Background scheduling enabled' : 'Background scheduling disabled');
  };
  backgroundLabel.append(backgroundToggle, document.createTextNode('Run in background to execute scheduled tasks'));
  body.append(backgroundLabel);

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

  body.append(await renderComputerUseSection());
  body.append(renderAboutSection());
  return root;
}

/**
 * Settings → Computer use (design §4.10, CUA CLI route): status of the cua CLI, the local
 * cua-spacesd service, the host target and the gui-automation skill, plus the one-time
 * enablement. Enabling is what advertises computer use to the engine; without it agents are
 * never told the desktop is available.
 */
async function renderComputerUseSection(): Promise<HTMLElement> {
  const section = el('div');
  section.append(el('h2', undefined, 'Computer use'));
  section.append(el('div', 'hint', 'Agents operate native apps through the CUA CLI (cua do): screenshots, clicks, '
    + 'typing, windows. Enabling grants desktop consent, installs CUA\'s local cua-spacesd service '
    + '(loopback 127.0.0.1:3211) and the gui-automation skill. It is the last resort behind shell, files and the browser pane.'));
  const status = await window.coreai.computerStatus();
  if (!status) {
    section.append(el('div', 'notice error', 'Computer use is unavailable in this build.'));
    return section;
  }

  const grid = el('dl', 'settings-grid');
  const row = (label: string, value: string): void => {
    grid.append(el('dt', undefined, label));
    grid.append(el('dd', undefined, value));
  };
  row('CUA CLI', status.cli.present
    ? `${status.cli.version ? `v${status.cli.version}` : 'installed'} - ${status.cli.path ?? ''}`
    : 'not installed');
  const access: string[] = [];
  access.push(status.consent ? 'consent granted' : 'consent missing');
  access.push(status.host.running ? `service running (${status.host.mode ?? 'unknown'} mode)` : status.host.configured ? 'service stopped' : 'service not set up');
  row('Desktop access', access.join(' - '));
  row('Host target', `${status.target.host ? 'selected' : 'not selected'} - ${status.token ? 'access token stored' : 'no access token'}`);
  row('gui-automation skill', `${status.skill.detail} (${status.skill.targetDir})`);
  row('Status', status.enabled ? (status.ready ? 'enabled and ready' : 'enabled, setup incomplete') : 'disabled');
  section.append(grid);

  if (status.problems.length > 0) {
    const problems = el('div', 'notice');
    problems.append(el('div', undefined, 'Setup needed:'));
    for (const problem of status.problems) problems.append(el('div', undefined, `- ${problem}`));
    section.append(problems);
  }

  const actions = el('div');
  actions.style.display = 'flex';
  actions.style.gap = '8px';
  actions.style.margin = '10px 0 18px';

  const rerender = async (): Promise<HTMLElement> => {
    const fresh = await renderComputerUseSection();
    section.replaceWith(fresh);
    return fresh;
  };

  const refresh = el('button', 'btn small', 'Refresh');
  refresh.onclick = () => void rerender();
  actions.append(refresh);

  if (!status.cli.present) {
    const install = el('button', 'btn small', 'Install CUA CLI');
    install.onclick = async () => {
      const confirmed = window.confirm('Install the CUA CLI from cua.ai with the official installer?\n\n'
        + 'It downloads the CLI to %LOCALAPPDATA%\\Programs\\cua\\bin and adds it to your user PATH.\n'
        + 'No sign-in, no VMs, CLI only.');
      if (!confirmed) return;
      install.disabled = true;
      install.textContent = 'Installing…';
      const result = await window.coreai.computerInstallCli();
      toast(result.ok ? `CUA CLI installed: ${result.detail}` : `CUA CLI install failed: ${result.detail}`);
      await rerender();
    };
    actions.append(install);
  }

  if (!status.enabled) {
    const enable = el('button', 'btn small primary', 'Enable computer use');
    enable.onclick = async () => {
      const confirmed = window.confirm('Enable computer use for this app?\n\n'
        + '- install the CUA CLI if missing\n'
        + '- grant desktop consent (cua do-host-consent)\n'
        + '- install CUA\'s local cua-spacesd service on loopback 127.0.0.1:3211\n'
        + '- install the gui-automation skill into ~/.core-ai/skills\n\n'
        + 'Agents in this app can then operate your desktop through the cua CLI.');
      if (!confirmed) return;
      enable.disabled = true;
      enable.textContent = 'Enabling…';
      const result = await window.coreai.computerEnable();
      toast(result.ok ? 'Computer use enabled - new sessions can drive the desktop via the CUA CLI'
        : 'Computer use could not be fully enabled - see the step list in Settings');
      await resumeOpenSession();
      const fresh = await rerender();
      if (!result.ok && result.steps.length > 0) {
        const list = el('div', 'notice error');
        for (const step of result.steps) {
          list.append(el('div', undefined, `${step.ok ? 'ok' : 'failed'} - ${step.step}: ${step.detail}`));
        }
        fresh.append(list);
      }
    };
    actions.append(enable);
  } else {
    const disable = el('button', 'btn small', 'Disable');
    disable.onclick = async () => {
      const confirmed = window.confirm('Disable computer use?\n\nAgents will no longer be told the desktop is available. '
        + 'The CUA service and desktop consent stay installed - remove the service separately if you want it gone.');
      if (!confirmed) return;
      disable.disabled = true;
      await window.coreai.computerDisable();
      toast('Computer use disabled');
      await resumeOpenSession();
      await rerender();
    };
    actions.append(disable);
  }

  if (status.host.configured) {
    const remove = el('button', 'btn small danger', 'Remove CUA service…');
    remove.onclick = async () => {
      const confirmed = window.confirm('Remove CUA\'s local desktop service (cua host remove)?\n\n'
        + 'Desktop access stops until computer use is enabled again.');
      if (!confirmed) return;
      remove.disabled = true;
      const result = await window.coreai.computerRemoveService();
      toast(result.ok ? 'CUA service removed' : `Remove failed: ${result.detail}`);
      await resumeOpenSession();
      await rerender();
    };
    actions.append(remove);
  }

  section.append(actions);
  return section;
}

// ---------- about & updates ----------

interface AboutUpdateNodes {
  root: HTMLElement;
  status: HTMLElement;
  bar: HTMLElement;
  fill: HTMLElement;
  check: HTMLButtonElement;
  action: HTMLButtonElement;
  notes: HTMLButtonElement;
}

let aboutUpdateNodes: AboutUpdateNodes | null = null;

/**
 * Settings → About: version, update check and the one-click upgrade (design §4.7). The update
 * work itself lives in the main process; this section only renders the pushed state.
 */
function renderAboutSection(): HTMLElement {
  const section = el('div');
  section.append(el('h2', undefined, 'About'));
  section.append(el('div', 'hint', 'Version and updates. Checks the GitHub release feed and, on a new version, '
    + 'downloads the installer (sha512-verified) and installs it.'));
  const grid = el('dl', 'settings-grid');
  const info = state.info;
  grid.append(el('dt', undefined, 'Version'));
  grid.append(el('dd', undefined, `v${info?.desktopVersion ?? '?'} (electron ${info?.electron ?? '?'}, node ${info?.node ?? '?'})`));
  section.append(grid);

  const status = el('div', 'notice');
  const bar = el('div', 'update-bar');
  const fill = el('div', 'update-fill');
  bar.append(fill);
  bar.style.display = 'none';
  const actions = el('div', 'update-actions');
  const check = el('button', 'btn small', 'Check for updates') as HTMLButtonElement;
  const action = el('button', 'btn small primary', 'Install update') as HTMLButtonElement;
  action.style.display = 'none';
  const notes = el('button', 'linkish', 'Release notes') as HTMLButtonElement;
  notes.style.display = 'none';
  actions.append(check, action, notes);
  section.append(status, bar, actions);

  const nodes: AboutUpdateNodes = { root: section, status, bar, fill, check, action, notes };
  aboutUpdateNodes = nodes;

  check.onclick = async () => {
    try {
      const next = await window.coreai.updateCheck();
      if (next) applyUpdateState(next);
    } catch (error) {
      toast(`Update check failed: ${cleanIpcError(error)}`);
    }
  };
  action.onclick = async () => {
    if (state.update?.installMode === 'manual') {
      if (state.update.releaseUrl) await window.coreai.openExternal(state.update.releaseUrl);
      return;
    }
    action.disabled = true;
    try {
      const next = await window.coreai.updateInstall();
      if (next) applyUpdateState(next);
    } catch (error) {
      toast(`Update install failed: ${cleanIpcError(error)}`);
    } finally {
      action.disabled = false;
    }
  };
  notes.onclick = () => {
    const url = state.update?.releaseUrl;
    if (url) void window.coreai.openExternal(url);
  };

  if (state.update) {
    paintUpdate(nodes, state.update);
  } else {
    void window.coreai.updateStatus().then((current) => {
      if (current) applyUpdateState(current);
    }).catch(() => undefined);
  }
  return section;
}

/** Stores the pushed state and repaints the About section when it is on screen. */
function applyUpdateState(next: UpdateState): void {
  state.update = next;
  if (aboutUpdateNodes && aboutUpdateNodes.root.isConnected) paintUpdate(aboutUpdateNodes, next);
}

function paintUpdate(nodes: AboutUpdateNodes, current: UpdateState): void {
  const busy = current.phase === 'checking' || current.phase === 'downloading' || current.phase === 'installing';
  nodes.check.disabled = busy;
  nodes.check.textContent = current.phase === 'checking' ? 'Checking…' : 'Check for updates';
  nodes.action.style.display = 'none';
  nodes.notes.style.display = 'none';
  nodes.bar.style.display = 'none';
  nodes.status.className = 'notice';

  const latest = current.latestVersion ? `v${current.latestVersion}` : 'the new version';
  if (current.phase === 'idle') {
    nodes.status.textContent = 'Not checked yet.';
  } else if (current.phase === 'checking') {
    nodes.status.textContent = 'Checking for updates…';
  } else if (current.phase === 'up-to-date') {
    nodes.status.textContent = `Up to date — v${current.currentVersion} is the latest version.`;
  } else if (current.phase === 'available') {
    nodes.status.textContent = current.devMode
      ? `New version ${latest} available (current v${current.currentVersion}). This build runs from source — installs apply to packaged builds.`
      : `New version ${latest} available (current v${current.currentVersion}).`;
    nodes.action.style.display = '';
    nodes.action.textContent = current.installMode === 'install' ? 'Download & install'
      : current.installMode === 'download' ? 'Download update' : 'Open release page';
    nodes.notes.style.display = '';
  } else if (current.phase === 'downloading') {
    const percent = current.total > 0 ? Math.floor((current.transferred / current.total) * 100) : 0;
    nodes.status.textContent = `Downloading ${latest}… ${percent}% (${fmtBytes(current.transferred)} / ${fmtBytes(current.total)})`;
    nodes.bar.style.display = '';
    nodes.fill.style.width = `${percent}%`;
  } else if (current.phase === 'installing') {
    nodes.status.textContent = `Installing ${latest} — the app will restart automatically.`;
  } else if (current.phase === 'ready') {
    nodes.status.textContent = `Downloaded ${current.assetName ?? 'the update'} and opened it — drag core-ai-desktop to Applications to finish.`;
  } else {
    nodes.status.className = 'notice error';
    nodes.status.textContent = current.error ?? 'Update failed.';
  }
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
    empty.append(el('div', 'big', 'core-ai-desktop'));
    empty.append(el('div', 'notice error', `Engine not found: ${info.enginePath} — run .\\gradlew.bat :core-ai-cli:installDist first`));
    content.append(empty);
    return;
  }

  if (!state.workspace) {
    if (state.nav === 'schedules') {
      const panel = await renderSchedulesPanel();
      if (token === contentRenderToken) content.replaceChildren(panel);
      return;
    }
    if (state.nav === 'sessions') {
      if (!chatView) buildChatView();
      content.replaceChildren(chatView as HTMLElement);
      updateChatHeader();
      showSessionView(ensureSessionView(state.sessionId));
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
    showSessionView(ensureSessionView(state.sessionId));
    showBlankHint();
    return;
  }
  let panel: HTMLElement;
  if (state.nav === 'schedules') {
    panel = await renderSchedulesPanel();
  } else if (state.nav === 'hub') {
    panel = await renderHubPanel();
  } else if (state.nav === 'artifacts') {
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
  state.info.backgroundScheduling = settings?.backgroundScheduling === true;
  state.approvalPolicy = settings?.approvalPolicy ?? 'ask';
  state.workspaces = settings?.workspaces ?? [];
  state.capabilities = state.workspace ? await window.coreai.engineCapabilities(state.workspace).catch(() => []) : [];
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
  window.addEventListener('coreai-nav-go', (event) => {
    const nav = (event as CustomEvent<string>).detail;
    if (nav !== 'sessions' && nav !== 'schedules') return;
    state.nav = nav;
    renderSidebar();
    void renderContent();
  });

  window.addEventListener('resize', () => {
    scheduleBrowserBounds();
  });

  window.coreai.onEvent((payload: any) => {
    if (!payload) return;
    if (payload.method === 'schedule/changed' || payload.method === 'schedule/fired') {
      if (state.nav === 'schedules') void renderContent();
      if (payload.method === 'schedule/fired') {
        const taskName = String(payload.params?.name ?? 'Scheduled task');
        toast(`${taskName} ${String(payload.params?.status ?? 'fired')}`);
      }
      return;
    }
    if (payload.workspace !== state.workspace) return;
    if (payload.method === 'session/event') {
      handleSessionEvent(payload.params?.event);
    } else if (payload.method === 'engine/ready') {
      // A fresh engine process serves no live turns — every turn the old one was running died.
      finalizeTurnsForEngineRestart();
      // Artifact events are live-only: reload the baseline against the restarted engine.
      state.artifacts = [];
      state.activeArtifactPath = null;
      void refreshArtifactBase();
      void refreshArtifactBaseline();
    }
  });

  window.coreai.onUpdateState((next: UpdateState) => {
    if (next) applyUpdateState(next);
  });

  window.coreai.onBrowserState((status: BrowserStatus) => {
    const wasRunning = Boolean(state.browserStatus?.running);
    applyBrowserStatus(status);
    // Covers a browser the main process started on its own (autostart): when it becomes
    // running, open the pane so the view gets laid out (bounds) and the user can watch.
    if (status?.running && !wasRunning && state.rightDock === 'none') {
      setRightDock('browser');
    }
  });
  window.coreai.onBrowserNeedPanel(() => {
    // The agent attached to a tab the view tree cannot lay out while the panel is hidden.
    if (!state.browserStatus?.running) return;
    if (state.rightDock !== 'browser') {
      setRightDock('browser');
    } else {
      scheduleBrowserBounds();
    }
  });
  void window.coreai.browserStatus().then((status) => {
    applyBrowserStatus(status);
    // A browser that was already running when the window loaded (e.g. autostart) opens the pane.
    if (status?.running && state.rightDock === 'none') {
      setRightDock('browser');
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
