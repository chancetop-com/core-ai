// Remote direct-conversation view: the desktop's first-class window onto a server-side root agent.
// It owns no agent loop and no local workspace; messages and events are routed by the main process.
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import { marked } from 'marked';

marked.setOptions({ gfm: true, breaks: true });

export interface RemoteChatHost {
  requestRenderContent(): void;
  requestRenderSidebar(): void;
  toast(text: string, kind?: string): void;
}

interface RemoteServer {
  serverUrl: string;
  name: string | null;
  userId: string | null;
  active: boolean;
}

interface RemoteAgent {
  id: string;
  name: string;
  description: string | null;
}

interface RemoteSessionRow {
  id: string;
  agentId: string | null;
  agentName: string | null;
  title: string | null;
  lastMessageAt: string | null;
}

interface RemoteToolRecord {
  callId?: string;
  name?: string;
  arguments?: string;
  result?: string;
  status?: string;
}

interface RemoteEventRecord {
  name?: string;
  data?: string;
  card?: string;
  callId?: string;
}

interface RemoteMessage {
  role: string;
  content: string;
  thinking?: string | null;
  tools?: RemoteToolRecord[];
  events?: RemoteEventRecord[];
  seq?: number | null;
  timestamp?: string | null;
}

interface LiveTool {
  name: string;
  status: string;
  node: HTMLElement;
  statusNode: HTMLElement;
}

interface LiveTurn {
  text: string;
  reasoning: string;
  tools: Map<string, LiveTool>;
  bubble: HTMLElement | null;
  body: HTMLElement | null;
  thinkingNode: HTMLElement | null;
  thinkingBody: HTMLElement | null;
  toolList: HTMLElement | null;
  renderedText: string;
}

interface RemoteChatState {
  serverUrl: string;
  sessionId: string;
  agentName: string;
  title: string;
  status: 'idle' | 'running' | 'error';
  connection: 'connecting' | 'connected' | 'reconnecting' | 'rejected' | 'closed';
  connectionMessage: string;
  messages: RemoteMessage[];
  live: LiveTurn | null;
  loading: boolean;
  subscribing: boolean;
  sending: boolean;
  sendError: string | null;
  historyHasMore: boolean;
  oldestSeq: number | null;
}

let host: RemoteChatHost | null = null;
let active: RemoteChatState | null = null;
let mounted: HTMLElement | null = null;
let mountedKey = '';
let transcriptEl: HTMLElement | null = null;
let statusEl: HTMLElement | null = null;
let composerEl: HTMLTextAreaElement | null = null;
let sendButton: HTMLButtonElement | null = null;
let bannerEl: HTMLElement | null = null;
let loadToken = 0;
let pickerEl: HTMLElement | null = null;

let cachedServers: RemoteServer[] | null = null;
let cachedSessions: RemoteSessionRow[] = [];
let cachedServerUrl = '';
let sessionsLoading = false;
let sessionsLoaded = false;
let pickerAgents: RemoteAgent[] = [];
let pickerAgentFilter = '';

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function toast(text: string, kind = ''): void {
  host?.toast(text, kind);
}

function sanitizeRemoteMarkdown(text: string): string {
  const html = marked.parse(text, { async: false }) as string;
  return DOMPurify.sanitize(html, {
    FORBID_TAGS: ['img', 'video', 'audio', 'iframe', 'form', 'input', 'button'],
    FORBID_ATTR: ['style', 'srcset', 'formaction'],
  });
}

function renderRemoteMarkdown(body: HTMLElement, text: string): void {
  body.innerHTML = sanitizeRemoteMarkdown(text);
  for (const block of body.querySelectorAll('pre code')) {
    const match = /language-([\w-]+)/.exec((block as HTMLElement).className);
    if (match && hljs.getLanguage(match[1])) hljs.highlightElement(block as HTMLElement);
  }
  for (const anchor of body.querySelectorAll('a')) {
    const href = (anchor as HTMLAnchorElement).href;
    if (!/^https?:/i.test(href)) anchor.removeAttribute('href');
  }
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function serverHost(serverUrl: string): string {
  try {
    return new URL(serverUrl).host;
  } catch {
    return serverUrl;
  }
}

function activeServers(): RemoteServer[] {
  return (cachedServers ?? []).filter((server) => server.serverUrl);
}

function findServer(serverUrl: string): RemoteServer | null {
  return (cachedServers ?? []).find((server) => server.serverUrl === serverUrl) ?? null;
}

async function loadServers(force = false): Promise<RemoteServer[]> {
  if (cachedServers && !force) return cachedServers;
  try {
    cachedServers = (await window.coreai.remoteServers()) as RemoteServer[];
  } catch {
    cachedServers = [];
  }
  return cachedServers;
}

function preferredServer(): RemoteServer | null {
  return activeServers().find((server) => server.active) ?? activeServers()[0] ?? null;
}

async function loadSessions(force = false): Promise<void> {
  const server = preferredServer();
  if (!server) {
    cachedSessions = [];
    cachedServerUrl = '';
    sessionsLoaded = true;
    return;
  }
  if (sessionsLoading) return;
  if (sessionsLoaded && !force && cachedServerUrl === server.serverUrl) return;
  sessionsLoading = true;
  try {
    const result = await window.coreai.remoteSessions(server.serverUrl, 0, 20);
    cachedSessions = (result?.sessions ?? []) as RemoteSessionRow[];
    cachedServerUrl = server.serverUrl;
    sessionsLoaded = true;
  } catch {
    cachedSessions = [];
  } finally {
    sessionsLoading = false;
  }
}

export function configureRemoteChat(next: RemoteChatHost): void {
  host = next;
}

export function resetRemoteChatCache(): void {
  cachedServers = null;
  cachedSessions = [];
  sessionsLoaded = false;
  cachedServerUrl = '';
  if (active) deactivateRemoteSession();
}

export function remoteSessionActive(): boolean {
  return active != null;
}

export function deactivateRemoteSession(): void {
  if (active) {
    void window.coreai.remoteUnsubscribe(active.serverUrl, active.sessionId).catch(() => undefined);
  }
  active = null;
  mounted = null;
  mountedKey = '';
  transcriptEl = null;
  composerEl = null;
  sendButton = null;
  statusEl = null;
  bannerEl = null;
}

export function openRemoteConversation(serverUrl: string, sessionId: string, agentName?: string | null, title?: string | null): void {
  if (!serverUrl || !sessionId) return;
  if (active && active.serverUrl === serverUrl && active.sessionId === sessionId) {
    host?.requestRenderContent();
    return;
  }
  deactivateRemoteSession();
  active = {
    serverUrl,
    sessionId,
    agentName: agentName ?? 'Server agent',
    title: title ?? '',
    status: 'idle',
    connection: 'connecting',
    connectionMessage: '',
    messages: [],
    live: null,
    loading: true,
    subscribing: false,
    sending: false,
    sendError: null,
    historyHasMore: false,
    oldestSeq: null,
  };
  host?.requestRenderContent();
  void subscribeAndLoad();
}

async function subscribeAndLoad(): Promise<void> {
  const current = active;
  if (!current) return;
  const token = ++loadToken;
  if (!current.subscribing) {
    current.subscribing = true;
    try {
      await window.coreai.remoteSubscribe(current.serverUrl, current.sessionId);
    } catch (error) {
      current.connection = 'rejected';
      current.connectionMessage = cleanError(error);
    }
  }
  try {
    const snapshot = await window.coreai.remoteSnapshot(current.serverUrl, current.sessionId);
    if (token !== loadToken || active !== current) return;
    if (snapshot?.agentName) current.agentName = String(snapshot.agentName);
    if (snapshot?.title) current.title = String(snapshot.title);
    current.status = String(snapshot?.status ?? 'idle') === 'running' ? 'running' : 'idle';
  } catch (error) {
    if (token !== loadToken || active !== current) return;
    current.connection = 'rejected';
    current.connectionMessage = cleanError(error);
  }
  await loadHistoryPage(current, null);
  paintChat();
}

async function loadHistoryPage(current: RemoteChatState, beforeSeq: number | null): Promise<boolean> {
  const token = loadToken;
  try {
    const history = await window.coreai.remoteHistory(current.serverUrl, current.sessionId, beforeSeq, 50);
    if (token !== loadToken || active !== current) return false;
    const rows = (history?.messages ?? []) as unknown as RemoteMessage[];
    if (beforeSeq == null) {
      current.messages = rows;
    } else {
      current.messages = mergeMessages(rows, current.messages);
    }
    current.historyHasMore = history?.hasMore === true;
    current.oldestSeq = typeof history?.oldestSeq === 'number' ? history.oldestSeq : null;
    current.loading = false;
    return true;
  } catch (error) {
    if (token !== loadToken || active !== current) return false;
    current.loading = false;
    current.sendError = cleanError(error);
    return false;
  }
}

function mergeMessages(older: RemoteMessage[], current: RemoteMessage[]): RemoteMessage[] {
  const seen = new Set(current.map((message) => `${message.role}\u0000${message.seq ?? ''}\u0000${message.content}`));
  return [...older.filter((message) => !seen.has(`${message.role}\u0000${message.seq ?? ''}\u0000${message.content}`)), ...current];
}

function cleanError(error: unknown): string {
  const message = error instanceof Error ? error.message : String((error as { message?: string })?.message ?? error);
  return message.replace(/^Error invoking remote method '[^']*':\s*(Error:\s*)?/, '');
}

function paintChat(): void {
  if (!active) return;
  if (!mounted || mountedKey !== chatKey()) {
    host?.requestRenderContent();
    return;
  }
  if (!transcriptEl) return;
  renderTranscript(transcriptEl, active);
  updateHeader();
  paintBanner();
  paintStatus();
  if (active.status === 'running' && !active.loading) {
    transcriptEl.scrollTop = transcriptEl.scrollHeight;
  }
}

function chatKey(): string {
  return active ? `${active.serverUrl}\u0000${active.sessionId}` : '';
}

export function mountRemoteContent(container: HTMLElement): void {
  if (!active) {
    container.replaceChildren();
    return;
  }
  if (mounted && chatKey() === mountedKey) {
    container.replaceChildren(mounted);
    paintTranscriptOnly();
    return;
  }
  const root = el('div', 'chat remote-chat');
  const main = el('div', 'chat-main');
  const header = el('div', 'chat-header remote-chat-header');
  const titleWrap = el('div', 'remote-chat-title');
  const title = el('span', 'chat-title');
  title.dataset.role = 'title';
  const badge = el('span', 'remote-badge', 'Server');
  titleWrap.append(title, badge);
  const meta = el('span', 'remote-meta');
  meta.dataset.role = 'meta';
  titleWrap.append(meta);
  const actions = el('div', 'remote-header-actions');
  const web = el('button', 'remote-link', 'Open in Web');
  web.onclick = () => {
    const target = `${active?.serverUrl ?? ''}/chat?sessionId=${encodeURIComponent(active?.sessionId ?? '')}`;
    void window.coreai.openExternal(target);
  };
  actions.append(el('span', 'remote-run-state'), web);
  header.append(titleWrap, actions);
  (actions.querySelector('.remote-run-state') as HTMLElement).dataset.role = 'run';

  bannerEl = el('div', 'remote-banner');
  bannerEl.hidden = true;
  const transcript = el('div', 'transcript remote-transcript');
  transcriptEl = transcript;
  transcript.addEventListener('click', (event) => {
    const anchor = (event.target as HTMLElement).closest('a') as HTMLAnchorElement | null;
    if (anchor && /^https?:/i.test(anchor.href)) {
      event.preventDefault();
      void window.coreai.openExternal(anchor.href);
    }
  });

  const composer = el('div', 'composer remote-composer');
  const card = el('div', 'composer-card');
  const inputRow = el('div', 'composer-input-row');
  composerEl = el('textarea');
  composerEl.rows = 1;
  composerEl.placeholder = 'Message the server agent — Enter to send, Shift+Enter for a new line';
  composerEl.onkeydown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      void sendRemoteMessage();
    }
  };
  statusEl = el('div', 'composer-controls remote-composer-controls');
  const controls = el('div', 'remote-controls');
  controls.append(el('span', 'remote-control-label', 'Model & permissions: configured by the server agent'));
  sendButton = el('button', 'remote-send', 'Send') as HTMLButtonElement;
  sendButton.onclick = () => {
    if (active?.status === 'running') void stopRemoteTurn();
    else void sendRemoteMessage();
  };
  controls.append(sendButton);
  statusEl.append(el('span', 'remote-status'), controls);
  (statusEl.querySelector('.remote-status') as HTMLElement).dataset.role = 'status';
  inputRow.append(composerEl);
  card.append(inputRow, statusEl);
  composer.append(card);

  main.append(header, bannerEl, transcript, composer);
  root.append(main);
  mounted = root;
  mountedKey = chatKey();
  container.replaceChildren(root);
  paintTranscriptOnly();
  updateHeader();
  paintBanner();
  paintStatus();
}

function paintTranscriptOnly(): void {
  if (!transcriptEl || !active) return;
  renderTranscript(transcriptEl, active);
  transcriptEl.scrollTop = transcriptEl.scrollHeight;
}

function renderTranscript(target: HTMLElement, current: RemoteChatState): void {
  target.replaceChildren();
  if (current.loading && current.messages.length === 0) {
    const loading = el('div', 'remote-empty');
    loading.append(el('div', 'remote-empty-title', 'Loading conversation…'));
    loading.append(el('div', 'remote-empty-body', 'The history comes from the server; no local workspace is involved.'));
    target.append(loading);
    return;
  }
  if (current.messages.length === 0 && !current.live) {
    const empty = el('div', 'remote-empty');
    empty.append(el('div', 'remote-empty-title', `Chat with ${current.agentName}`));
    empty.append(el('div', 'remote-empty-body', `Running on ${serverHost(current.serverUrl)} · the agent cannot access this computer's files or browser.`));
    target.append(empty);
  }
  if (current.historyHasMore) {
    const more = el('button', 'remote-more', 'Load earlier messages');
    more.onclick = async () => {
      if (!active || active.oldestSeq == null) return;
      more.disabled = true;
      await loadHistoryPage(active, active.oldestSeq);
      paintChat();
    };
    target.append(more);
  }
  for (const message of current.messages) {
    target.append(messageEl(message));
  }
  if (current.live) target.append(liveEl(current.live));
  if (current.sendError) {
    const error = el('div', 'remote-error', current.sendError);
    target.append(error);
  }
}

function messageEl(message: RemoteMessage): HTMLElement {
  const isUser = message.role === 'user';
  const wrapper = el('div', `msg ${isUser ? 'user' : 'assistant'} remote-msg`);
  wrapper.append(el('div', 'role', isUser ? 'You' : (active?.agentName ?? 'Server agent')));
  if (message.thinking) {
    const details = el('details', 'remote-thinking');
    details.append(el('summary', undefined, 'Thought'));
    const body = el('div', 'md-body');
    renderRemoteMarkdown(body, message.thinking);
    details.append(body);
    wrapper.append(details);
  }
  if (message.tools?.length) {
    const details = el('details', 'remote-tools');
    details.append(el('summary', undefined, `Tool calls · ${message.tools.length}`));
    for (const tool of message.tools) {
      const row = el('div', 'remote-tool-row');
      row.append(el('span', 'remote-tool-name', tool.name ?? 'tool'));
      row.append(el('span', `remote-tool-status ${tool.status === 'error' || tool.status === 'failed' ? 'error' : 'ok'}`, tool.status ?? 'done'));
      details.append(row);
    }
    wrapper.append(details);
  }
  for (const event of message.events ?? []) {
    if (event.card) wrapper.append(readOnlyCardEl(event.card));
    else if (event.name) wrapper.append(el('div', 'remote-event', event.name));
  }
  if (message.content) {
    const bubble = el('div', 'bubble md');
    const body = el('div', 'md-body');
    renderRemoteMarkdown(body, message.content);
    bubble.append(body);
    wrapper.append(bubble);
  }
  return wrapper;
}

function liveEl(live: LiveTurn): HTMLElement {
  const wrapper = el('div', 'msg assistant remote-msg remote-live');
  wrapper.append(el('div', 'role', active?.agentName ?? 'Server agent'));
  if (live.thinkingNode) wrapper.append(live.thinkingNode);
  if (live.toolList) wrapper.append(live.toolList);
  const bubble = el('div', 'bubble md');
  const body = el('div', 'md-body');
  live.bubble = bubble;
  live.body = body;
  bubble.append(body);
  wrapper.append(bubble);
  renderLive(live);
  return wrapper;
}

function renderLive(live: LiveTurn): void {
  if (!live.body) {
    paintChat();
    return;
  }
  if (live.text !== live.renderedText) {
    renderRemoteMarkdown(live.body, live.text || '…');
    live.renderedText = live.text;
  }
}

function ensureLive(): LiveTurn {
  if (!active) throw new Error('no active conversation');
  if (!active.live) {
    active.live = {
      text: '',
      reasoning: '',
      tools: new Map(),
      bubble: null,
      body: null,
      thinkingNode: null,
      thinkingBody: null,
      toolList: null,
      renderedText: '',
    };
    if (active.status !== 'running') active.status = 'running';
  }
  return active.live;
}

function liveThinking(live: LiveTurn): HTMLElement {
  if (!live.thinkingNode) {
    live.thinkingNode = el('details', 'remote-thinking');
    live.thinkingNode.append(el('summary', undefined, 'Thinking'));
    live.thinkingBody = el('div', 'md-body');
    live.thinkingNode.append(live.thinkingBody);
  }
  return live.thinkingNode;
}

function liveToolList(live: LiveTurn): HTMLElement {
  if (!live.toolList) {
    live.toolList = el('div', 'remote-tools live');
  }
  return live.toolList;
}

function readOnlyCardEl(raw: string): HTMLElement {
  const box = el('div', 'remote-card');
  try {
    const card = JSON.parse(raw) as { blocks?: Array<Record<string, unknown>> };
    for (const block of card.blocks ?? []) {
      const type = String(block.type ?? '');
      if (type === 'text' && typeof block.text === 'string') {
        const body = el('div', 'md-body');
        renderRemoteMarkdown(body, block.text);
        box.append(body);
      } else if (type === 'divider') {
        box.append(el('hr'));
      } else if (type === 'key_values' && Array.isArray(block.items)) {
        for (const item of block.items as Array<Record<string, unknown>>) {
          const row = el('div', 'remote-kv');
          row.append(el('span', 'remote-kv-key', String(item.key ?? '')));
          row.append(el('span', 'remote-kv-value', String(item.value ?? '')));
          box.append(row);
        }
      } else if (type === 'table' && Array.isArray(block.rows)) {
        const table = el('table', 'remote-table');
        const head = el('tr');
        for (const column of (block.columns as unknown[]) ?? []) head.append(el('th', undefined, String(column)));
        table.append(head);
        for (const row of block.rows as unknown[][]) {
          const tr = el('tr');
          for (const cell of row ?? []) tr.append(el('td', undefined, String(cell)));
          table.append(tr);
        }
        box.append(table);
      }
    }
    if (!box.childElementCount) box.append(el('div', 'remote-card-note', 'Rich card available in the web console'));
  } catch {
    box.append(el('div', 'remote-card-note', 'Rich card available in the web console'));
  }
  return box;
}

function updateHeader(): void {
  if (!mounted || !active) return;
  const title = mounted.querySelector('[data-role="title"]') as HTMLElement | null;
  if (title) title.textContent = active.title || active.agentName || 'Server agent';
  const meta = mounted.querySelector('[data-role="meta"]') as HTMLElement | null;
  if (meta) {
    const server = findServer(active.serverUrl);
    const account = server?.userId ?? server?.name ?? '';
    meta.textContent = `${active.agentName} · ${serverHost(active.serverUrl)}${account ? ` · ${account}` : ''}`;
  }
  const run = mounted.querySelector('[data-role="run"]') as HTMLElement | null;
  if (run) {
    run.textContent = active.status === 'running' ? 'Running on server' : 'Idle';
    run.classList.toggle('running', active.status === 'running');
  }
}

function paintBanner(): void {
  if (!bannerEl || !active) return;
  if (active.connection === 'connected') {
    bannerEl.hidden = true;
    return;
  }
  bannerEl.hidden = false;
  bannerEl.replaceChildren();
  const text = active.connection === 'connecting'
    ? 'Connecting to the server…'
    : active.connection === 'reconnecting'
      ? 'Connection interrupted — the task may still be running on the server. Reconnecting…'
      : `Cannot reach this conversation: ${active.connectionMessage || 'unknown error'}`;
  bannerEl.append(el('span', undefined, text));
  if (active.connection === 'rejected' || active.connection === 'closed') {
    const login = el('button', 'remote-banner-action', 'Reconnect');
    login.onclick = () => void subscribeAndLoad();
    bannerEl.append(login);
  }
}

function paintStatus(): void {
  if (!active) return;
  const status = statusEl?.querySelector('[data-role="status"]') as HTMLElement | null;
  if (status) {
    status.textContent = active.status === 'running'
      ? 'Agent is running on the server'
      : active.connection === 'connected' ? 'Connected' : active.connection === 'connecting' ? 'Connecting…' : 'Disconnected';
    status.classList.toggle('running', active.status === 'running');
  }
  if (sendButton) {
    sendButton.textContent = active.status === 'running' ? 'Stop' : 'Send';
    sendButton.disabled = active.connection !== 'connected' || active.sending;
  }
}

async function sendRemoteMessage(): Promise<void> {
  if (!active || !composerEl) return;
  const text = composerEl.value.trim();
  if (!text) return;
  if (active.status === 'running') {
    toast('The server agent is still running; stop it before sending another message');
    return;
  }
  if (active.connection !== 'connected') {
    toast('The connection is not ready — reconnect before sending');
    return;
  }
  active.sending = true;
  active.sendError = null;
  composerEl.value = '';
  active.messages.push({ role: 'user', content: text, timestamp: new Date().toISOString() });
  active.live = null;
  active.status = 'running';
  paintChat();
  try {
    await window.coreai.remoteSend(active.serverUrl, active.sessionId, text);
  } catch (error) {
    const message = cleanError(error);
    if (/SESSION_BUSY/i.test(message)) {
      toast('Another turn is already running on this session');
      active.status = 'running';
    } else if (/REQUEST_IN_PROGRESS/i.test(message)) {
      active.sendError = 'The previous request is still being confirmed. Check the conversation before sending again.';
      active.status = 'idle';
    } else if (/timeout|ECONNRESET|socket hang up|network/i.test(message)) {
      // The command may or may not have reached the server; never auto-resend it.
      active.sendError = `Send status unknown: ${message}. The server may still be running this message; check the conversation before retrying.`;
    } else {
      active.sendError = `Send failed: ${message}`;
      active.status = 'idle';
    }
  } finally {
    if (active) active.sending = false;
    paintChat();
  }
}

async function stopRemoteTurn(): Promise<void> {
  if (!active) return;
  try {
    const result = await window.coreai.remoteCancel(active.serverUrl, active.sessionId);
    if (!result?.cancelled) toast('No running turn on the server');
  } catch (error) {
    toast(`Stop failed: ${cleanError(error)}`, 'error');
  }
}

async function refreshAfterTurn(): Promise<void> {
  if (!active) return;
  const token = loadToken;
  try {
    const history = await window.coreai.remoteHistory(active.serverUrl, active.sessionId, null, 50);
    if (token !== loadToken || !active) return;
    active.messages = (history?.messages ?? []) as unknown as RemoteMessage[];
    active.historyHasMore = history?.hasMore === true;
    active.oldestSeq = typeof history?.oldestSeq === 'number' ? history.oldestSeq : null;
    if (active.live) {
      active.live.bubble?.remove();
      active.live = null;
    }
    paintChat();
    sessionsLoaded = false;
    host?.requestRenderSidebar();
  } catch {
    // The terminal event already updated the UI; a failed history refresh is retried on reconnect.
  }
}

export function handleRemotePush(payload: unknown): void {
  if (!payload || typeof payload !== 'object') return;
  const push = payload as { type?: string; serverUrl?: string; sessionId?: string; event?: Record<string, unknown>; phase?: string; message?: string };
  if (!active || push.serverUrl !== active.serverUrl || push.sessionId !== active.sessionId) return;
  if (push.type === 'state') {
    const phase = String(push.phase ?? '');
    if (phase === 'connected') {
      const wasConnected = active.connection === 'connected';
      active.connection = 'connected';
      active.connectionMessage = '';
      if (!wasConnected) {
        // Re-attaching: the server replays the current turn, so replace the local projection.
        if (active.live) active.live = null;
        void subscribeAndLoad();
      }
      paintBanner();
      paintStatus();
      return;
    }
    if (phase === 'reconnecting') {
      active.connection = 'reconnecting';
      active.connectionMessage = '';
    } else if (phase === 'rejected') {
      active.connection = 'rejected';
      active.connectionMessage = String(push.message ?? 'connection rejected');
    } else if (phase === 'closed') {
      active.connection = 'closed';
    }
    paintBanner();
    paintStatus();
    return;
  }
  if (push.type !== 'event' || !push.event) return;
  handleRemoteEvent(push.event);
}

function handleRemoteEvent(event: Record<string, unknown>): void {
  if (!active) return;
  const type = String(event.type ?? '');
  switch (type) {
    case 'status_change': {
      const status = String(event.status ?? '').toLowerCase();
      if (status === 'running') active.status = 'running';
      else if (status === 'idle' || status === 'error') active.status = status === 'error' ? 'error' : 'idle';
      paintStatus();
      updateHeader();
      break;
    }
    case 'text_chunk': {
      const live = ensureLive();
      live.text += String(event.content ?? '');
      renderLive(live);
      break;
    }
    case 'reasoning_chunk': {
      const live = ensureLive();
      live.reasoning += String(event.content ?? '');
      const node = liveThinking(live);
      if (live.thinkingBody) {
        live.thinkingBody.textContent = live.reasoning;
      }
      if (!node.isConnected) paintChat();
      break;
    }
    case 'tool_start': {
      const live = ensureLive();
      const callId = String(event.call_id ?? event.callId ?? '');
      if (callId && !live.tools.has(callId)) {
        const row = el('div', 'remote-tool-row');
        const name = String(event.tool_name ?? event.toolName ?? 'tool');
        row.append(el('span', 'remote-tool-name', name));
        const status = el('span', 'remote-tool-status running', 'running');
        row.append(status);
        liveToolList(live).append(row);
        live.tools.set(callId, { name, status: 'running', node: row, statusNode: status });
        if (!liveToolList(live).isConnected) paintChat();
      }
      break;
    }
    case 'tool_result': {
      const live = ensureLive();
      const callId = String(event.call_id ?? event.callId ?? '');
      const tool = live.tools.get(callId);
      if (tool) {
        const ok = String(event.status ?? 'done').toLowerCase();
        tool.status = ok;
        tool.statusNode.textContent = ok;
        tool.statusNode.className = `remote-tool-status ${ok === 'error' || ok === 'failed' ? 'error' : 'ok'}`;
      }
      break;
    }
    case 'turn_complete': {
      active.status = 'idle';
      active.live = null;
      paintStatus();
      updateHeader();
      void refreshAfterTurn();
      break;
    }
    case 'error': {
      active.status = 'error';
      active.sendError = String(event.message ?? 'The server agent reported an error');
      active.live = null;
      paintStatus();
      paintChat();
      break;
    }
    case 'custom': {
      if (event.card) {
        active.messages.push({ role: 'assistant', content: '', events: [{ card: String(event.card) }] });
        paintChat();
      }
      break;
    }
    case 'plan_update': {
      const items = Array.isArray(event.todos) ? (event.todos as Array<Record<string, unknown>>) : [];
      const live = ensureLive();
      const box = el('div', 'remote-plan');
      for (const item of items) {
        const row = el('div', `remote-plan-item ${String(item.status ?? 'pending').toLowerCase()}`);
        row.textContent = String(item.content ?? '');
        box.append(row);
      }
      const previous = live.toolList?.querySelector('.remote-plan');
      previous?.remove();
      liveToolList(live).append(box);
      if (!liveToolList(live).isConnected) paintChat();
      break;
    }
    default:
      break;
  }
}

// ---------- sidebar ----------

export function renderRemoteSidebar(nav: HTMLElement): void {
  const section = el('div', 'remote-sidebar');
  const title = el('div', 'remote-sidebar-title');
  title.append(el('span', undefined, 'Server agents'));
  const refresh = el('button', 'remote-sidebar-refresh', '↻');
  refresh.title = 'Reload server conversations';
  refresh.onclick = () => {
    sessionsLoaded = false;
    void loadSessions(true).then(() => host?.requestRenderSidebar());
  };
  title.append(refresh);
  section.append(title);

  const create = el('button', 'remote-sidebar-new', '+ Chat with a server agent…');
  create.onclick = () => void startRemoteConversation();
  section.append(create);

  if (!cachedServers) {
    section.append(el('div', 'remote-sidebar-empty', 'Checking sign-in…'));
    void loadServers().then(() => loadSessions()).then(() => host?.requestRenderSidebar());
    nav.append(section);
    return;
  }
  if (!activeServers().length) {
    section.append(el('div', 'remote-sidebar-empty', 'Sign in to a server to chat with its agents.'));
    nav.append(section);
    return;
  }
  if (sessionsLoading) {
    section.append(el('div', 'remote-sidebar-empty', 'Loading conversations…'));
    nav.append(section);
    return;
  }
  if (!sessionsLoaded) {
    section.append(el('div', 'remote-sidebar-empty', 'Loading conversations…'));
    void loadSessions().then(() => host?.requestRenderSidebar());
    nav.append(section);
    return;
  }
  if (!cachedSessions.length) {
    section.append(el('div', 'remote-sidebar-empty', 'No server conversations yet.'));
  } else {
    for (const row of cachedSessions.slice(0, 8)) {
      const button = el('button', `remote-sidebar-row${active?.sessionId === row.id ? ' active' : ''}`);
      button.append(el('span', 'remote-sidebar-row-title', row.title || row.agentName || 'Conversation'));
      button.append(el('span', 'remote-sidebar-row-agent', row.agentName || 'Server agent'));
      button.title = `${row.agentName ?? ''} · ${formatTime(row.lastMessageAt)}`;
      button.onclick = () => openRemoteConversation(cachedServerUrl, row.id, row.agentName, row.title);
      section.append(button);
    }
    if (cachedSessions.length > 8) {
      const more = el('div', 'remote-sidebar-empty', `+${cachedSessions.length - 8} more on the server`);
      section.append(more);
    }
  }
  nav.append(section);
}

// ---------- agent picker ----------

export async function startRemoteConversation(): Promise<void> {
  if (pickerEl) return;
  const servers = await loadServers(true);
  if (!servers.length) {
    toast('Sign in to a server first (bottom-left profile)');
    return;
  }
  const server = preferredServer();
  if (!server) {
    toast('Sign in to a server first (bottom-left profile)');
    return;
  }
  try {
    const capabilities = await window.coreai.remoteCapabilities(server.serverUrl);
    if (capabilities?.direct_conversation !== true) {
      toast('Direct server conversations are not enabled on this server yet');
      return;
    }
  } catch (error) {
    toast(`Could not check server capabilities: ${cleanError(error)}`, 'error');
    return;
  }
  const overlay = el('div', 'remote-picker-overlay');
  pickerEl = overlay;
  const modal = el('div', 'remote-picker');
  const head = el('div', 'remote-picker-head');
  head.append(el('h2', undefined, 'Chat with a server agent'));
  const close = el('button', 'remote-picker-close', '×');
  close.onclick = () => closePicker();
  head.append(close);
  modal.append(head);
  modal.append(el('div', 'remote-picker-server', `${server.name ?? ''} · ${serverHost(server.serverUrl)} · ${server.userId ?? ''}`.trim()));
  const search = el('input', 'remote-picker-search') as HTMLInputElement;
  search.placeholder = 'Search agents…';
  search.oninput = () => {
    pickerAgentFilter = search.value.toLowerCase();
    paintPickerList(list, server.serverUrl);
  };
  modal.append(search);
  const list = el('div', 'remote-picker-list');
  modal.append(list);
  modal.append(el('div', 'remote-picker-note', 'Messages go to this server. The agent cannot access this computer’s workspace, browser or credentials.'));
  overlay.append(modal);
  overlay.onclick = (event) => {
    if (event.target === overlay) closePicker();
  };
  document.body.append(overlay);
  search.focus();
  list.append(el('div', 'remote-sidebar-empty', 'Loading agents…'));
  try {
    pickerAgents = (await window.coreai.remoteAgents(server.serverUrl, '', 100)) as RemoteAgent[];
  } catch (error) {
    pickerAgents = [];
    list.replaceChildren(el('div', 'remote-picker-error', `Could not load agents: ${cleanError(error)}`));
    return;
  }
  paintPickerList(list, server.serverUrl);
}

function paintPickerList(list: HTMLElement, serverUrl: string): void {
  list.replaceChildren();
  const agents = pickerAgents.filter((agent) =>
    !pickerAgentFilter || `${agent.name} ${agent.description ?? ''}`.toLowerCase().includes(pickerAgentFilter));
  if (!agents.length) {
    list.append(el('div', 'remote-sidebar-empty', 'No agents visible to this account.'));
    return;
  }
  for (const agent of agents) {
    const row = el('button', 'remote-picker-agent');
    const main = el('div', 'remote-picker-agent-main');
    main.append(el('div', 'remote-picker-agent-name', agent.name));
    if (agent.description) main.append(el('div', 'remote-picker-agent-desc', agent.description));
    row.append(main);
    row.onclick = () => void openPickedAgent(serverUrl, agent);
    list.append(row);
  }
}

function closePicker(): void {
  pickerEl?.remove();
  pickerEl = null;
  pickerAgents = [];
  pickerAgentFilter = '';
}

async function openPickedAgent(serverUrl: string, agent: RemoteAgent): Promise<void> {
  closePicker();
  try {
    const created = await window.coreai.remoteCreate(serverUrl, agent.id);
    if (!created?.sessionId) throw new Error('server did not return a session id');
    sessionsLoaded = false;
    openRemoteConversation(serverUrl, created.sessionId, created.agentName ?? agent.name, '');
  } catch (error) {
    toast(`Could not start the conversation: ${cleanError(error)}`, 'error');
  }
}
