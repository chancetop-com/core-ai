// Remote direct-conversation view: the desktop's first-class window onto a server-side root agent.
// It owns no agent loop and no local workspace; messages and events are routed by the main process.
// The transcript itself is the local chat view, hosted through RemoteChatHost.createTranscript.
import { normalizeRemoteEvent, normalizeRemoteMessages, type RemoteEvent, type RemoteMessage } from './remoteProtocol';

export interface RemoteTranscriptHandle {
  root: HTMLElement;
  renderHistory(messages: RemoteMessage[], before?: HTMLElement | null): void;
  applyEvent(event: RemoteEvent): void;
  beginTurn(): void;
  scrollToBottom(force?: boolean): void;
  dispose(): void;
}

export interface RemoteChatHost {
  requestRenderContent(): void;
  requestRenderSidebar(): void;
  selectSessionsTab(tab: 'local' | 'server'): void;
  toast(text: string, kind?: string): void;
  /** The local chat transcript, hosted for a server conversation; the remote view owns no renderer. */
  createTranscript(sessionId: string, agentName: string): RemoteTranscriptHandle;
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

/** The attachment descriptor the server accepts on send; the same shape the web composer produces. */
export interface RemoteAttachmentPayload {
  url: string;
  type: 'IMAGE' | 'VIDEO' | 'PDF' | 'FILE';
  fileName: string;
  contentType: string;
  category: 'multimodal' | 'sandbox';
  container: string;
  blobName: string;
}

type AttachmentKind = 'image' | 'video' | 'pdf' | 'file';

/** An uploaded-by-main file shown in the composer tray and on the optimistic user message. */
interface PendingRemoteAttachment {
  id: string;
  name: string;
  size: number;
  kind: AttachmentKind;
  preview: string | null;
  status: 'uploading' | 'ready' | 'failed';
  descriptor?: RemoteAttachmentPayload;
}

/** A user message the client added itself; history does not persist attachment metadata yet. */
interface RemoteChatMessage extends RemoteMessage {
  attachments?: PendingRemoteAttachment[];
}

const REMOTE_ICONS = {
  paperclip: '<path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',
  file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
  x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
} as const;

function remoteIcon(name: keyof typeof REMOTE_ICONS): SVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('ic');
  svg.innerHTML = REMOTE_ICONS[name];
  return svg;
}

function attachmentKindOf(contentType: string, fileName: string): AttachmentKind {
  if (contentType.startsWith('image/')) return 'image';
  if (contentType.startsWith('video/')) return 'video';
  if (contentType === 'application/pdf' || /\.pdf$/i.test(fileName)) return 'pdf';
  return 'file';
}

function baseNameOf(filePath: string): string {
  const parts = filePath.split(/[\\/]/);
  return parts[parts.length - 1] || filePath;
}

function fmtBytes(size: number): string {
  if (!Number.isFinite(size) || size <= 0) return '';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(0)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

interface RemoteChatState {
  serverUrl: string;
  sessionId: string;
  agentName: string;
  title: string;
  status: 'idle' | 'running' | 'error';
  connection: 'connecting' | 'connected' | 'reconnecting' | 'rejected' | 'closed';
  connectionMessage: string;
  attachmentUpload: boolean;
  attachments: PendingRemoteAttachment[];
  messages: RemoteChatMessage[];
  /** The shared local transcript renderer; this view only feeds it server events. */
  transcript: RemoteTranscriptHandle | null;
  turnActive: boolean;
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
let statusEl: HTMLElement | null = null;
let composerEl: HTMLTextAreaElement | null = null;
let sendButton: HTMLButtonElement | null = null;
let attachButton: HTMLButtonElement | null = null;
let attachTrayEl: HTMLElement | null = null;
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
const capabilityCache = new Map<string, Record<string, unknown>>();

async function attachmentUploadSupported(serverUrl: string): Promise<boolean> {
  let capabilities = capabilityCache.get(serverUrl);
  if (!capabilities) {
    try {
      capabilities = await window.coreai.remoteCapabilities(serverUrl);
    } catch {
      capabilities = {};
    }
    capabilityCache.set(serverUrl, capabilities);
  }
  return capabilities?.attachment_upload === true;
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function toast(text: string, kind = ''): void {
  host?.toast(text, kind);
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
    active.transcript?.dispose();
  }
  active = null;
  mounted = null;
  mountedKey = '';
  composerEl = null;
  sendButton = null;
  attachButton = null;
  attachTrayEl = null;
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
  host?.selectSessionsTab('server');
  active = {
    serverUrl,
    sessionId,
    agentName: agentName ?? 'Server agent',
    title: title ?? '',
    status: 'idle',
    connection: 'connecting',
    connectionMessage: '',
    attachmentUpload: false,
    attachments: [],
    messages: [],
    transcript: null,
    turnActive: false,
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
  current.attachmentUpload = await attachmentUploadSupported(current.serverUrl);
  paintChat();
}

async function loadHistoryPage(current: RemoteChatState, beforeSeq: number | null): Promise<boolean> {
  const token = loadToken;
  try {
    const history = await window.coreai.remoteHistory(current.serverUrl, current.sessionId, beforeSeq, 50);
    if (token !== loadToken || active !== current) return false;
    const rows = normalizeRemoteMessages(history?.messages);
    if (beforeSeq == null) {
      mergeLocalAttachments(rows, current.messages);
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

/**
 * History carries no attachment metadata, so a reloaded snapshot would drop the chips the client
 * showed for its own messages. Match user messages in order (the stored text is the sent text plus
 * server-appended hints) and carry the local descriptors over.
 */
function mergeLocalAttachments(rows: RemoteMessage[], previous: RemoteChatMessage[]): void {
  const sent = previous.filter((message) => message.role === 'user' && message.attachments?.length);
  let cursor = 0;
  for (const row of rows) {
    if (row.role !== 'user' || cursor >= sent.length) continue;
    const match = sent.findIndex((message, index) => index >= cursor && sameUserMessage(message.content, row.content));
    if (match < 0) continue;
    (row as RemoteChatMessage).attachments = sent[match].attachments;
    cursor = match + 1;
  }
}

function sameUserMessage(sent: string, stored: string): boolean {
  const left = (sent ?? '').trim();
  const right = (stored ?? '').trim();
  if (!left || !right) return !left && !right;
  return right.startsWith(left) || left.startsWith(right);
}

function cleanError(error: unknown): string {
  const message = error instanceof Error ? error.message : String((error as { message?: string })?.message ?? error);
  return message.replace(/^Error invoking remote method '[^']*':\s*(Error:\s*)?/, '');
}

function loadEarlierButton(current: RemoteChatState): HTMLElement {
  const more = el('button', 'remote-more', 'Load earlier messages');
  more.onclick = async () => {
    more.disabled = true;
    const ok = await loadHistoryPage(current, current.oldestSeq);
    if (ok && active === current) renderRemoteMessages(current, 'preserve');
  };
  return more;
}

/** Renders one conversation into the shared transcript, keeping the paging affordance on top. */
function renderRemoteMessages(current: RemoteChatState, behavior: 'bottom' | 'preserve' = 'bottom'): void {
  if (!current.transcript) return;
  const scroller = current.transcript.root.querySelector('.transcript') as HTMLElement | null;
  const previousHeight = scroller?.scrollHeight ?? 0;
  const previousTop = scroller?.scrollTop ?? 0;
  current.transcript.renderHistory(current.messages, current.historyHasMore ? loadEarlierButton(current) : null);
  if (behavior === 'preserve' && scroller) {
    scroller.scrollTop = previousTop + (scroller.scrollHeight - previousHeight);
    return;
  }
  current.transcript.scrollToBottom(true);
}

function paintChat(): void {
  if (!active) return;
  if (!mounted || mountedKey !== chatKey()) {
    host?.requestRenderContent();
    return;
  }
  // While a turn streams, the transcript owns its own DOM; re-rendering history here would erase
  // the live bubble. History is repainted once the turn settles.
  if (!active.turnActive) renderRemoteMessages(active, 'preserve');
  updateHeader();
  paintBanner();
  paintStatus();
}

function chatKey(): string {
  return active ? `${active.serverUrl}\u0000${active.sessionId}` : '';
}

export function mountRemoteHome(container: HTMLElement): void {
  container.replaceChildren();
  const root = el('div', 'chat remote-chat remote-home-chat');
  const main = el('div', 'chat-main');
  const empty = el('div', 'remote-empty remote-home');
  empty.append(el('div', 'remote-empty-title', 'Talk to a server agent'));
  empty.append(el('div', 'remote-empty-body',
    'A direct conversation runs on the selected server. The agent cannot access this computer’s workspace, browser or credentials.'));
  const choose = el('button', 'remote-home-action', 'Chat with a server agent…');
  choose.onclick = () => void startRemoteConversation();
  empty.append(choose);
  main.append(empty);
  root.append(main);
  container.replaceChildren(root);
}

export function mountRemoteContent(container: HTMLElement): void {
  if (!active) {
    container.replaceChildren();
    return;
  }
  if (mounted && chatKey() === mountedKey) {
    container.replaceChildren(mounted);
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
  // Same transcript component as a local session: streaming, reasoning sections, tool rows,
  // per-origin chips and history all come from the shared view.
  if (!active.transcript) active.transcript = host?.createTranscript(active.sessionId, active.agentName) ?? null;
  if (active.transcript) renderRemoteMessages(active, 'preserve');

  const composer = el('div', 'composer remote-composer');
  const card = el('div', 'composer-card');
  const inputRow = el('div', 'composer-input-row');
  attachButton = el('button', 'composer-attach-btn') as HTMLButtonElement;
  attachButton.append(remoteIcon('paperclip'));
  attachButton.title = 'Attach a file — it uploads to the server agent';
  attachButton.onclick = () => void chooseRemoteAttachments();
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
  attachTrayEl = el('div', 'composer-attachments');
  attachTrayEl.hidden = true;
  inputRow.append(attachButton, composerEl);
  card.append(inputRow, attachTrayEl, statusEl);
  composer.append(card);

  main.append(header, bannerEl);
  if (active.transcript) main.append(active.transcript.root);
  main.append(composer);
  root.append(main);
  mounted = root;
  mountedKey = chatKey();
  container.replaceChildren(root);
  updateHeader();
  paintBanner();
  paintStatus();
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

const MAX_REMOTE_ATTACHMENTS = 10;

/**
 * Composer tray: files are uploaded by the main process to this conversation's storage prefix and
 * only become sendable once every descriptor is ready. Removal before send just drops the chip.
 */
function paintComposerAttachments(): void {
  if (attachButton) {
    const supported = active?.attachmentUpload === true;
    attachButton.hidden = !supported;
    attachButton.disabled = !active || active.connection !== 'connected' || active.sending;
  }
  if (!attachTrayEl) return;
  attachTrayEl.replaceChildren();
  const attachments = active?.attachments ?? [];
  attachTrayEl.hidden = attachments.length === 0;
  for (const attachment of attachments) attachTrayEl.append(pendingAttachmentEl(attachment, true));
}

function pendingAttachmentEl(attachment: PendingRemoteAttachment, removable: boolean): HTMLElement {
  if (attachment.kind === 'image' && attachment.preview) {
    const tile = el('div', 'attachment-image-tile');
    tile.title = `${attachment.name}${attachment.size ? ` · ${fmtBytes(attachment.size)}` : ''}`;
    tile.classList.toggle('uploading', attachment.status === 'uploading');
    const image = el('img', undefined) as HTMLImageElement;
    image.src = attachment.preview;
    image.alt = attachment.name;
    image.onclick = () => openRemotePreview(attachment.preview as string, attachment.name, attachment.size);
    tile.append(image);
    if (removable) tile.append(removeAttachmentButton(attachment));
    return tile;
  }
  const chip = el('div', 'attachment-chip');
  chip.title = `${attachment.name}${attachment.size ? ` · ${fmtBytes(attachment.size)}` : ''}`;
  chip.classList.toggle('uploading', attachment.status === 'uploading');
  chip.classList.toggle('failed', attachment.status === 'failed');
  chip.append(remoteIcon('file'));
  chip.append(el('span', 'attachment-name', attachment.name));
  chip.append(el('span', 'attachment-size',
    attachment.status === 'uploading' ? 'Uploading…' : attachment.status === 'failed' ? 'Failed' : fmtBytes(attachment.size)));
  if (removable) chip.append(removeAttachmentButton(attachment));
  return chip;
}

function removeAttachmentButton(attachment: PendingRemoteAttachment): HTMLButtonElement {
  const remove = el('button', 'attachment-remove') as HTMLButtonElement;
  remove.type = 'button';
  remove.append(remoteIcon('x'));
  remove.title = 'Remove attachment';
  remove.onclick = (event) => {
    event.stopPropagation();
    removeRemoteAttachment(attachment.id);
  };
  return remove;
}

function openRemotePreview(src: string, name: string, size: number): void {
  const overlay = el('div', 'attachment-lightbox');
  const inner = el('div', 'lightbox-inner');
  const figure = el('figure', 'lightbox-figure');
  const image = el('img', undefined) as HTMLImageElement;
  image.src = src;
  image.alt = name;
  figure.append(image, el('figcaption', 'lightbox-caption', `${name}${size ? ` · ${fmtBytes(size)}` : ''}`));
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

async function chooseRemoteAttachments(): Promise<void> {
  const current = active;
  if (!current || !current.attachmentUpload) return;
  if (current.connection !== 'connected') {
    toast('The connection is not ready — reconnect before attaching files');
    return;
  }
  let picked: { paths?: string[] } | null = null;
  try {
    picked = await window.coreai.pickAttachments();
  } catch (error) {
    toast(`Attach failed: ${cleanError(error)}`, 'error');
    return;
  }
  const paths = (picked?.paths ?? []).filter((entry) => typeof entry === 'string' && entry.trim());
  if (!paths.length || active !== current) return;
  for (const filePath of paths) {
    if (current.attachments.length >= MAX_REMOTE_ATTACHMENTS) {
      toast(`At most ${MAX_REMOTE_ATTACHMENTS} attachments per message`);
      break;
    }
    const name = baseNameOf(filePath);
    const attachment: PendingRemoteAttachment = {
      id: crypto.randomUUID(),
      name,
      size: 0,
      kind: attachmentKindOf('', name),
      preview: null,
      status: 'uploading',
    };
    current.attachments.push(attachment);
    paintComposerAttachments();
    void uploadRemoteAttachment(current, attachment, filePath);
  }
  composerEl?.focus();
}

async function uploadRemoteAttachment(current: RemoteChatState, attachment: PendingRemoteAttachment, filePath: string): Promise<void> {
  try {
    const upload = await window.coreai.remoteUploadAttachment(current.serverUrl, current.sessionId, filePath);
    if (active !== current || !current.attachments.includes(attachment)) return;
    attachment.status = 'ready';
    attachment.name = upload.fileName || attachment.name;
    attachment.size = upload.size;
    attachment.kind = attachmentKindOf(upload.contentType, attachment.name);
    attachment.preview = upload.preview ?? null;
    attachment.descriptor = {
      url: upload.url,
      type: upload.type,
      fileName: upload.fileName,
      contentType: upload.contentType,
      category: upload.category,
      container: upload.container,
      blobName: upload.blobName,
    };
  } catch (error) {
    if (active !== current || !current.attachments.includes(attachment)) return;
    current.attachments = current.attachments.filter((entry) => entry !== attachment);
    toast(`Upload failed for ${attachment.name}: ${cleanError(error)}`, 'error');
    paintChat();
    return;
  }
  paintChat();
}

function removeRemoteAttachment(id: string): void {
  if (!active) return;
  active.attachments = active.attachments.filter((entry) => entry.id !== id);
  paintComposerAttachments();
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
    const uploading = active.attachments.some((attachment) => attachment.status === 'uploading');
    sendButton.textContent = active.status === 'running' ? 'Stop' : 'Send';
    sendButton.disabled = active.status === 'running'
      ? active.connection !== 'connected'
      : active.connection !== 'connected' || active.sending || uploading;
  }
  paintComposerAttachments();
}

async function sendRemoteMessage(): Promise<void> {
  const current = active;
  if (!current || !composerEl) return;
  if (current.attachments.some((attachment) => attachment.status === 'uploading')) {
    toast('Attachments are still uploading — wait for them to finish');
    return;
  }
  const text = composerEl.value.trim();
  const ready = current.attachments.filter((attachment) => attachment.status === 'ready' && attachment.descriptor);
  if (!text && ready.length === 0) return;
  if (current.status === 'running') {
    toast('The server agent is still running; stop it before sending another message');
    return;
  }
  if (current.connection !== 'connected') {
    toast('The connection is not ready — reconnect before sending');
    return;
  }
  current.sending = true;
  current.sendError = null;
  composerEl.value = '';
  current.attachments = [];
  current.messages.push({ role: 'user', content: text, timestamp: new Date().toISOString(), attachments: ready });
  current.turnActive = true;
  current.status = 'running';
  renderRemoteMessages(current);
  current.transcript?.beginTurn();
  paintChat();
  try {
    await window.coreai.remoteSend(current.serverUrl, current.sessionId, text,
      ready.map((attachment) => attachment.descriptor as RemoteAttachmentPayload));
  } catch (error) {
    const message = cleanError(error);
    if (/SESSION_BUSY/i.test(message)) {
      toast('Another turn is already running on this session');
      current.status = 'running';
    } else if (/REQUEST_IN_PROGRESS/i.test(message)) {
      current.sendError = 'The previous request is still being confirmed. Check the conversation before sending again.';
      current.status = 'idle';
      current.turnActive = false;
      current.messages.pop();
      renderRemoteMessages(current);
    } else if (/timeout|ECONNRESET|socket hang up|network/i.test(message)) {
      // The command may or may not have reached the server; never auto-resend it.
      current.sendError = `Send status unknown: ${message}. The server may still be running this message; check the conversation before retrying.`;
    } else {
      current.sendError = `Send failed: ${message}`;
      current.status = 'idle';
      current.turnActive = false;
      current.messages.pop();
      renderRemoteMessages(current);
    }
  } finally {
    if (active === current) current.sending = false;
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

async function refreshAfterTurn(attempt = 0): Promise<void> {
  const current = active;
  if (!current) return;
  const token = loadToken;
  try {
    const history = await window.coreai.remoteHistory(current.serverUrl, current.sessionId, null, 50);
    if (token !== loadToken || active !== current) return;
    const rows = normalizeRemoteMessages(history?.messages);
    if (current.turnActive && !historyHasAnswer(rows)) {
      // Persistence can land a few hundred milliseconds after turn_complete. Dropping the live
      // projection first would erase thinking/tool cards when the history snapshot wins the race.
      if (attempt < 12) setTimeout(() => void refreshAfterTurn(attempt + 1), 250);
      return;
    }
    mergeLocalAttachments(rows, current.messages);
    current.messages = rows;
    current.turnActive = false;
    current.historyHasMore = history?.hasMore === true;
    current.oldestSeq = typeof history?.oldestSeq === 'number' ? history.oldestSeq : null;
    renderRemoteMessages(current);
    paintChat();
    sessionsLoaded = false;
    host?.requestRenderSidebar();
  } catch {
    // The terminal event already updated the UI; a failed history refresh is retried on reconnect.
  }
}

function historyHasAnswer(messages: RemoteMessage[]): boolean {
  const lastUser = messages.map((message) => message.role).lastIndexOf('user');
  return messages.some((message, index) => index > lastUser && message.role !== 'user');
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
        active.turnActive = false;
        renderRemoteMessages(active, 'preserve');
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

function handleRemoteEvent(raw: Record<string, unknown>): void {
  if (!active || !active.transcript) return;
  const event = normalizeRemoteEvent(raw);
  if (!event) return;
  switch (event.type) {
    case 'status_change': {
      const status = String(event.status ?? '').toLowerCase();
      if (status === 'running') {
        active.status = 'running';
        active.turnActive = true;
        active.transcript.beginTurn();
      } else if (status === 'idle' || status === 'error') {
        active.status = status === 'error' ? 'error' : 'idle';
      }
      paintStatus();
      updateHeader();
      break;
    }
    case 'turn_complete':
      active.transcript.applyEvent(event);
      active.status = 'idle';
      paintStatus();
      updateHeader();
      void refreshAfterTurn();
      break;
    case 'error':
      active.transcript.applyEvent(event);
      active.status = 'error';
      active.sendError = String(event.message ?? 'The server agent reported an error');
      paintStatus();
      paintChat();
      break;
    default:
      active.transcript.applyEvent(event);
      break;
  }
}

// ---------- sidebar ----------

export function renderRemoteSidebar(nav: HTMLElement): void {
  const section = el('div', 'remote-sidebar');
  const server = preferredServer();
  if (server) {
    const head = el('div', 'remote-sidebar-host', `${serverHost(server.serverUrl)}${server.userId ? ` · ${server.userId}` : ''}`);
    section.append(head);
  }

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
    section.append(el('div', 'remote-sidebar-empty', 'Only agents enabled for direct chat by the server can be started here.'));
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

export async function refreshRemoteSessions(): Promise<void> {
  sessionsLoaded = false;
  await loadSessions(true);
  host?.requestRenderSidebar();
}

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
  modal.append(el('div', 'remote-picker-note',
    'Only agents enabled for direct conversations by the server are listed. Messages go to this server; the agent cannot access this computer’s workspace, browser or credentials.'));
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
    list.append(el('div', 'remote-picker-empty-title', 'No agents are enabled for direct conversations on this server.'));
    list.append(el('div', 'remote-picker-empty-body', 'Ask a server administrator to add the agent id to SYS_CONVERSATION_AGENTS, then reopen this dialog.'));
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
