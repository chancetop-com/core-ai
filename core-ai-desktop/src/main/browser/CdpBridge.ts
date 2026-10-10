// Controlled CDP facade over the desktop's embedded browser (design §4.9 P2): the app owns a
// WebContentsView, main drives it through webContents.debugger (in-process CDP, no second
// browser), and the local browser-use harness still connects the only way it knows — a
// Chrome-style DevTools endpoint. This bridge is that endpoint, scoped to the embedded tabs:
// loopback-only, token-prefixed paths, Host/Origin validated, alive only while the browser runs.
//
// It speaks just enough of the browser-level protocol for the harness daemon: Target.* for tab
// discovery/attach/create/close, Browser.getVersion, and session-scoped commands (flat sessions)
// relayed 1:1 to the matching tab's debugger. Page-level WS endpoints from /json are supported
// for generic CDP tooling. No 'electron' import here — main/ is the only Electron-facing layer.
import http from 'node:http';
import type { IncomingMessage } from 'node:http';
import type { Socket } from 'node:net';
import { acceptWebSocket, isWebSocketUpgrade, webSocketVersionSupported, WsConnection } from './wsServer';

const HOST = '127.0.0.1';
const COMMAND_TIMEOUT_MS = 120_000;

export interface HostTarget {
  targetId: string;
  title: string;
  url: string;
}

/** What the bridge needs from the embedded browser; implemented by BrowserManager. */
export interface BrowserHost {
  listTargets(): HostTarget[];
  activateTarget(targetId: string): void;
  createTarget(url: string, background: boolean): Promise<HostTarget>;
  closeTarget(targetId: string): Promise<void>;
  sendCommand(targetId: string, method: string, params: Record<string, unknown>): Promise<unknown>;
  subscribe(targetId: string, listener: (method: string, params: unknown) => void): () => void;
}

export interface CdpBridgeOptions {
  host: BrowserHost;
  /** Preferred port (0 picks a free one). */
  port: number;
  /** Per-install secret; path prefix of every endpoint. */
  token: string;
  chromeVersion: string;
  userAgent: string;
  v8Version: string;
}

interface Session {
  targetId: string;
  unsubscribe: () => void;
}

interface CdpMessage {
  id?: number;
  method?: string;
  params?: Record<string, unknown>;
  sessionId?: string;
}

export class CdpBridge {
  private server: http.Server | null = null;
  private port = 0;
  private readonly connections = new Set<BridgeConnection>();
  private sessionCounter = 0;

  constructor(private readonly options: CdpBridgeOptions) {}

  get listeningPort(): number {
    return this.port;
  }

  /** The value injected as BU_CDP_URL: an HTTP DevTools endpoint with the token in the path. */
  endpointUrl(): string {
    return `http://${HOST}:${this.port}/${this.options.token}`;
  }

  start(): Promise<void> {
    return new Promise((resolve, reject) => {
      const server = http.createServer((request, response) => this.handleHttp(request, response));
      server.on('upgrade', (request, socket, head) => this.handleUpgrade(request, socket as Socket, head));
      server.on('clientError', (_error, socket) => socket.destroy());
      const onListenError = (error: Error): void => reject(error);
      server.once('error', onListenError);
      server.listen(this.options.port, HOST, () => {
        server.off('error', onListenError);
        this.server = server;
        const address = server.address();
        this.port = typeof address === 'object' && address ? address.port : this.options.port;
        resolve();
      });
    });
  }

  async stop(): Promise<void> {
    for (const connection of [...this.connections]) {
      connection.close(1001);
    }
    this.connections.clear();
    const server = this.server;
    this.server = null;
    if (!server) return;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, 1_000);
      server.close(() => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  private checkRequest(request: IncomingMessage): number {
    const host = String(request.headers.host ?? '').toLowerCase();
    const hostname = host.replace(/:\d+$/, '');
    if (hostname !== '127.0.0.1' && hostname !== 'localhost' && hostname !== '[::1]' && hostname !== '::1') {
      return 403;
    }
    const portMatch = /:(\d+)$/.exec(host);
    if (portMatch && Number(portMatch[1]) !== this.port) return 403;
    // Browser pages always attach an Origin; CDP clients (and the harness) never do.
    if (request.headers.origin) return 403;
    return 200;
  }

  private tokenPath(request: IncomingMessage): string | null {
    const path = String(request.url ?? '/');
    const prefix = `/${this.options.token}/`;
    if (!path.startsWith(prefix)) return null;
    return path.slice(prefix.length);
  }

  private handleHttp(request: IncomingMessage, response: http.ServerResponse): void {
    const status = this.checkRequest(request);
    if (status !== 200) {
      response.writeHead(status).end();
      return;
    }
    const rest = this.tokenPath(request);
    if (!rest) {
      response.writeHead(404).end();
      return;
    }
    if (request.method !== 'GET') {
      response.writeHead(405).end();
      return;
    }
    if (rest === 'json/version') {
      response.writeHead(200, { 'Content-Type': 'application/json; charset=UTF-8' });
      response.end(JSON.stringify({
        Browser: `Chrome/${this.options.chromeVersion}`,
        'Protocol-Version': '1.3',
        'User-Agent': this.options.userAgent,
        'V8-Version': this.options.v8Version,
        'WebKit-Version': '537.36',
        webSocketDebuggerUrl: `ws://${HOST}:${this.port}/${this.options.token}/devtools/browser/${this.options.token.slice(0, 8)}`,
      }));
      return;
    }
    if (rest === 'json' || rest === 'json/list') {
      response.writeHead(200, { 'Content-Type': 'application/json; charset=UTF-8' });
      response.end(JSON.stringify(this.options.host.listTargets().map((target) => ({
        id: target.targetId,
        type: 'page',
        title: target.title,
        url: target.url,
        webSocketDebuggerUrl: `ws://${HOST}:${this.port}/${this.options.token}/devtools/page/${target.targetId}`,
      }))));
      return;
    }
    response.writeHead(404).end();
  }

  private handleUpgrade(request: IncomingMessage, socket: Socket, head: Buffer): void {
    const reject = (status: number, message: string): void => {
      socket.write(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
      socket.destroy();
    };
    if (this.checkRequest(request) !== 200) {
      reject(403, 'Forbidden');
      return;
    }
    const rest = this.tokenPath(request);
    if (!rest || !isWebSocketUpgrade(request)) {
      reject(404, 'Not Found');
      return;
    }
    if (!webSocketVersionSupported(request)) {
      socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nSec-WebSocket-Version: 13\r\nContent-Length: 0\r\n\r\n');
      socket.destroy();
      return;
    }
    const browserMatch = /^devtools\/browser\/[^/]+$/.exec(rest);
    const pageMatch = /^devtools\/page\/(.+)$/.exec(rest);
    if (!browserMatch && !pageMatch) {
      reject(404, 'Not Found');
      return;
    }
    const pageTargetId = pageMatch ? decodeURIComponent(pageMatch[1]) : null;
    const ws = acceptWebSocket(request, socket, head);
    const connection = new BridgeConnection(this, ws, pageTargetId);
    this.connections.add(connection);
    ws.onclose = () => this.connections.delete(connection);
    if (pageTargetId) this.attachImplicitSession(connection, pageTargetId);
  }

  /** Page-level sockets get one implicit session: commands and events without a sessionId. */
  private attachImplicitSession(connection: BridgeConnection, targetId: string): void {
    try {
      const unsubscribe = this.options.host.subscribe(targetId, (method, params) => {
        connection.sendEvent(method, params, null);
      });
      connection.setImplicitSession({ targetId, unsubscribe });
    } catch (error) {
      console.error(`[browser] page session for ${targetId} failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  // ---------- browser-level protocol (shared by all connections) ----------

  async handleBrowserCommand(connection: BridgeConnection, id: number | null, method: string, params: Record<string, unknown>): Promise<void> {
    switch (method) {
      case 'Target.getTargets':
        connection.reply(id, { targetInfos: this.targetInfos() });
        return;
      case 'Target.getTargetInfo': {
        const info = this.findTargetInfo(String(params.targetId ?? ''));
        if (!info) connection.replyError(id, -32602, 'No target with given id found');
        else connection.reply(id, { targetInfo: info });
        return;
      }
      case 'Target.attachToTarget': {
        const targetId = String(params.targetId ?? '');
        if (!this.options.host.listTargets().some((target) => target.targetId === targetId)) {
          connection.replyError(id, -32602, 'No target with given id found');
          return;
        }
        const sessionId = this.attachSession(connection, targetId);
        this.options.host.activateTarget(targetId);
        const targetInfo = this.findTargetInfo(targetId);
        this.broadcastEvent('Target.attachedToTarget', { sessionId, targetInfo, waitingForDebugger: false });
        connection.reply(id, { sessionId });
        return;
      }
      case 'Target.detachFromTarget': {
        const sessionId = String(params.sessionId ?? '');
        const targetId = connection.sessionTargetId(sessionId);
        if (connection.dropSession(sessionId)) {
          this.broadcastEvent('Target.detachedFromTarget', { sessionId, targetId });
        }
        connection.reply(id, {});
        return;
      }
      case 'Target.createTarget': {
        const target = await this.options.host.createTarget(String(params.url ?? 'about:blank'), Boolean(params.background));
        this.broadcastEvent('Target.targetCreated', { targetInfo: this.infoOf(target.targetId) ?? target });
        this.broadcastEvent('Target.targetInfoChanged', { targetInfo: this.infoOf(target.targetId) ?? target });
        connection.reply(id, { targetId: target.targetId });
        return;
      }
      case 'Target.closeTarget': {
        const targetId = String(params.targetId ?? '');
        if (!this.options.host.listTargets().some((target) => target.targetId === targetId)) {
          connection.replyError(id, -32602, 'No target with given id found');
          return;
        }
        await this.closeTargetEverywhere(targetId);
        connection.reply(id, {});
        return;
      }
      case 'Target.activateTarget':
        this.options.host.activateTarget(String(params.targetId ?? ''));
        connection.reply(id, {});
        return;
      case 'Target.setDiscoverTargets':
        connection.setDiscovery(Boolean(params.discover), this.targetInfos());
        connection.reply(id, {});
        return;
      case 'Target.setAutoAttach':
        // Auto-attach is not supported; the harness attaches explicitly.
        connection.reply(id, {});
        return;
      case 'Target.getBrowserContexts':
        connection.reply(id, { browserContextIds: [] });
        return;
      case 'Browser.getVersion':
        connection.reply(id, {
          protocolVersion: '1.3',
          product: `Chrome/${this.options.chromeVersion}`,
          revision: '@core-ai-desktop',
          userAgent: this.options.userAgent,
          jsVersion: this.options.v8Version,
        });
        return;
      default:
        connection.replyError(id, -32601, `'${method}' wasn't found`);
    }
  }

  async runTargetCommand(targetId: string, method: string, params: Record<string, unknown>): Promise<unknown> {
    let timer: NodeJS.Timeout | null = null;
    try {
      return await Promise.race([
        this.options.host.sendCommand(targetId, method, params),
        new Promise((_resolve, reject) => {
          timer = setTimeout(() => reject(new Error(`CDP command timed out: ${method}`)), COMMAND_TIMEOUT_MS);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private attachSession(connection: BridgeConnection, targetId: string): string {
    const sessionId = `session-${(this.sessionCounter += 1)}`;
    const unsubscribe = this.options.host.subscribe(targetId, (method, params) => {
      connection.sendEvent(method, params, sessionId);
    });
    connection.addSession(sessionId, { targetId, unsubscribe });
    return sessionId;
  }

  private async closeTargetEverywhere(targetId: string): Promise<void> {
    for (const connection of [...this.connections]) {
      connection.dropSessionsForTarget(targetId);
    }
    await this.options.host.closeTarget(targetId);
    for (const connection of [...this.connections]) {
      connection.notifyTargetGone(targetId);
    }
  }

  private targetInfos(): Array<Record<string, unknown>> {
    return this.options.host.listTargets().map((target) => ({
      targetId: target.targetId,
      type: 'page',
      title: target.title,
      url: target.url,
      attached: this.isTargetAttached(target.targetId),
    }));
  }

  private isTargetAttached(targetId: string): boolean {
    for (const connection of this.connections) {
      if (connection.hasSessionsFor(targetId)) return true;
    }
    return false;
  }

  private findTargetInfo(targetId: string): Record<string, unknown> | null {
    return this.infoOf(targetId);
  }

  private infoOf(targetId: string): Record<string, unknown> | null {
    const target = this.options.host.listTargets().find((entry) => entry.targetId === targetId);
    if (!target) return null;
    return { targetId: target.targetId, type: 'page', title: target.title, url: target.url, attached: this.isTargetAttached(target.targetId) };
  }

  broadcastEvent(method: string, params: Record<string, unknown>): void {
    for (const connection of [...this.connections]) {
      connection.sendEvent(method, params, null, true);
    }
  }
}

/** One upgraded WebSocket: browser-level by default, or page-level when bound to a target. */
class BridgeConnection {
  private readonly sessions = new Map<string, Session>();
  private implicitSession: Session | null = null;
  private discovery = false;

  constructor(
    private readonly bridge: CdpBridge,
    private readonly ws: WsConnection,
    private readonly implicitTargetId: string | null,
  ) {
    ws.onmessage = (data) => this.onMessage(data);
  }

  close(code: number): void {
    this.dropAllSessions();
    this.ws.close(code);
  }

  reply(id: number | null, result: unknown): void {
    if (id === null) return;
    this.ws.sendText(JSON.stringify({ id, result }));
  }

  replyError(id: number | null, code: number, message: string): void {
    if (id === null) return;
    this.ws.sendText(JSON.stringify({ id, error: { code, message } }));
  }

  sendEvent(method: string, params: unknown, sessionId: string | null, browserOnly = false): void {
    if (browserOnly && this.implicitTargetId) return;
    const message: Record<string, unknown> = { method, params: params ?? {} };
    if (sessionId) message.sessionId = sessionId;
    this.ws.sendText(JSON.stringify(message));
  }

  addSession(sessionId: string, session: Session): void {
    this.sessions.set(sessionId, session);
  }

  setImplicitSession(session: Session): void {
    this.implicitSession = session;
  }

  sessionTargetId(sessionId: string): string {
    return this.sessions.get(sessionId)?.targetId ?? '';
  }

  dropSession(sessionId: string): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;
    this.sessions.delete(sessionId);
    try {
      session.unsubscribe();
    } catch {
      // the target may already be gone
    }
    return true;
  }

  hasSessionsFor(targetId: string): boolean {
    for (const session of this.sessions.values()) {
      if (session.targetId === targetId) return true;
    }
    return false;
  }

  dropSessionsForTarget(targetId: string): void {
    for (const [sessionId, session] of [...this.sessions]) {
      if (session.targetId === targetId) {
        this.sessions.delete(sessionId);
        try {
          session.unsubscribe();
        } catch {
          // already gone
        }
        this.sendEvent('Target.detachedFromTarget', { sessionId, targetId }, null);
      }
    }
    if (this.implicitSession && this.implicitSession.targetId === targetId) {
      try {
        this.implicitSession.unsubscribe();
      } catch {
        // already gone
      }
      this.implicitSession = null;
    }
  }

  notifyTargetGone(targetId: string): void {
    if (this.discovery) {
      this.sendEvent('Target.targetDestroyed', { targetId }, null);
    }
  }

  setDiscovery(enabled: boolean, existing: Array<Record<string, unknown>>): void {
    this.discovery = enabled;
    if (!enabled) return;
    for (const targetInfo of existing) {
      this.sendEvent('Target.targetCreated', { targetInfo }, null);
    }
  }

  private dropAllSessions(): void {
    for (const session of this.sessions.values()) {
      try {
        session.unsubscribe();
      } catch {
        // already gone
      }
    }
    this.sessions.clear();
    if (this.implicitSession) {
      try {
        this.implicitSession.unsubscribe();
      } catch {
        // already gone
      }
      this.implicitSession = null;
    }
  }

  private onMessage(data: string): void {
    let message: CdpMessage;
    try {
      message = JSON.parse(data) as CdpMessage;
    } catch {
      return;
    }
    const id = typeof message.id === 'number' ? message.id : null;
    const method = typeof message.method === 'string' ? message.method : '';
    if (!method) return;
    const params = (message.params ?? {}) as Record<string, unknown>;
    const sessionId = typeof message.sessionId === 'string' && message.sessionId ? message.sessionId : null;
    void this.dispatch(id, method, params, sessionId);
  }

  private async dispatch(id: number | null, method: string, params: Record<string, unknown>, sessionId: string | null): Promise<void> {
    try {
      if (sessionId) {
        const session = this.sessions.get(sessionId);
        if (!session) {
          this.replyError(id, -32001, 'Session with given id not found.');
          return;
        }
        if (method.startsWith('Target.')) {
          await this.bridge.handleBrowserCommand(this, id, method, params);
          return;
        }
        this.reply(id, await this.bridge.runTargetCommand(session.targetId, method, params));
        return;
      }
      if (this.implicitTargetId) {
        if (method.startsWith('Target.')) {
          await this.bridge.handleBrowserCommand(this, id, method, params);
          return;
        }
        this.reply(id, await this.bridge.runTargetCommand(this.implicitTargetId, method, params));
        return;
      }
      await this.bridge.handleBrowserCommand(this, id, method, params);
    } catch (error) {
      this.replyError(id, -32000, error instanceof Error ? error.message : String(error));
    }
  }
}
