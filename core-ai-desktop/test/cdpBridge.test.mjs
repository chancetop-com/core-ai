// Protocol tests for the controlled CDP bridge: HTTP endpoints, token/Host/Origin gating,
// browser-level sessions, event forwarding, tab lifecycle, page-level sockets and raw framing
// (fragmentation + ping/pong). Run with `npm test` (builds dist/test/cdpBridge.js first).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import http from 'node:http';
import net from 'node:net';
import bridgeModule from '../dist/test/cdpBridge.js';

const { CdpBridge } = bridgeModule;

const TOKEN = 'a'.repeat(32);

function createHost() {
  const targets = [{ targetId: '1', title: 'one', url: 'about:blank' }];
  const listeners = new Map();
  const calls = [];
  let created = 0;
  return {
    calls,
    targets,
    emit(targetId, method, params) {
      for (const listener of listeners.get(targetId) ?? []) listener(method, params);
    },
    listTargets: () => targets.map((target) => ({ ...target })),
    activateTarget(targetId) {
      calls.push(['activate', targetId]);
    },
    async createTarget(url, background) {
      created += 1;
      const target = { targetId: String(100 + created), title: '', url: url || 'about:blank' };
      targets.push(target);
      calls.push(['create', url, background]);
      return { ...target };
    },
    async closeTarget(targetId) {
      const index = targets.findIndex((target) => target.targetId === targetId);
      if (index >= 0) targets.splice(index, 1);
      calls.push(['close', targetId]);
    },
    async sendCommand(targetId, method, params) {
      calls.push(['command', targetId, method, params]);
      return { echo: { targetId, method, params } };
    },
    subscribe(targetId, listener) {
      const set = listeners.get(targetId) ?? new Set();
      set.add(listener);
      listeners.set(targetId, set);
      return () => set.delete(listener);
    },
  };
}

async function startBridge(host) {
  const bridge = new CdpBridge({
    host,
    port: 0,
    token: TOKEN,
    chromeVersion: '140.0.7339.186',
    userAgent: 'test-ua',
    v8Version: '14.0',
  });
  await bridge.start();
  return bridge;
}

let messageId = 0;

function connectWs(url) {
  const socket = new WebSocket(url);
  const queue = [];
  const waiters = [];
  socket.onmessage = (event) => {
    const message = JSON.parse(String(event.data));
    const index = waiters.findIndex((waiter) => waiter.predicate(message));
    if (index >= 0) {
      waiters.splice(index, 1)[0].resolve(message);
    } else {
      queue.push(message);
    }
  };
  const next = (predicate = () => true, timeoutMs = 5_000) => new Promise((resolve, reject) => {
    const index = queue.findIndex(predicate);
    if (index >= 0) {
      resolve(queue.splice(index, 1)[0]);
      return;
    }
    const waiter = { predicate, resolve: null };
    const timer = setTimeout(() => {
      const waiterIndex = waiters.indexOf(waiter);
      if (waiterIndex >= 0) waiters.splice(waiterIndex, 1);
      reject(new Error('timed out waiting for a websocket message'));
    }, timeoutMs);
    waiter.resolve = (message) => {
      clearTimeout(timer);
      resolve(message);
    };
    waiters.push(waiter);
  });
  const ready = new Promise((resolve, reject) => {
    socket.onopen = () => resolve();
    socket.onerror = (error) => reject(error instanceof Error ? error : new Error('websocket error'));
  });
  return {
    socket,
    next,
    ready,
    send(method, params, sessionId) {
      messageId += 1;
      const id = messageId;
      const payload = { id, method, params: params ?? {} };
      if (sessionId) payload.sessionId = sessionId;
      socket.send(JSON.stringify(payload));
      return next((message) => message.id === id);
    },
  };
}

function httpStatus(port, path, headers) {
  return new Promise((resolve) => {
    const request = http.request({ host: '127.0.0.1', port, path, headers }, (response) => {
      response.resume();
      resolve(response.statusCode);
    });
    request.on('error', () => resolve(-1));
    request.end();
  });
}

test('serves /json/version and /json, token-prefixed only', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  try {
    const base = bridge.endpointUrl();
    const version = await (await fetch(`${base}/json/version`)).json();
    assert.match(version.webSocketDebuggerUrl, new RegExp(`^ws://127\\.0\\.0\\.1:\\d+/${TOKEN}/devtools/browser/`));
    assert.equal(version.Browser, 'Chrome/140.0.7339.186');
    const list = await (await fetch(`${base}/json`)).json();
    assert.equal(list[0].id, '1');
    assert.ok(list[0].webSocketDebuggerUrl.includes('/devtools/page/1'));
    const listAlias = await (await fetch(`${base}/json/list`)).json();
    assert.equal(listAlias.length, 1);
    assert.equal((await fetch(`http://127.0.0.1:${bridge.listeningPort}/deadbeef/json/version`)).status, 404);
  } finally {
    await bridge.stop();
  }
});

test('rejects non-loopback Host headers and browser Origins', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  try {
    const port = bridge.listeningPort;
    assert.equal(await httpStatus(port, `/${TOKEN}/json/version`, { Host: 'evil.example' }), 403);
    assert.equal(await httpStatus(port, `/${TOKEN}/json/version`, { Origin: 'http://evil.example' }), 403);
    assert.equal(await httpStatus(port, `/${TOKEN}/json/version`, { Host: `127.0.0.1:${port}` }), 200);
  } finally {
    await bridge.stop();
  }
});

test('browser socket: targets, attach, session commands, events, unknown session', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  try {
    const version = await (await fetch(`${bridge.endpointUrl()}/json/version`)).json();
    const conn = connectWs(version.webSocketDebuggerUrl);
    await conn.ready;

    const targets = await conn.send('Target.getTargets');
    assert.equal(targets.result.targetInfos.length, 1);
    assert.equal(targets.result.targetInfos[0].targetId, '1');
    assert.equal(targets.result.targetInfos[0].type, 'page');

    const attach = await conn.send('Target.attachToTarget', { targetId: '1', flatten: true });
    const sessionId = attach.result.sessionId;
    assert.ok(sessionId);
    assert.deepEqual(host.calls.find((call) => call[0] === 'activate'), ['activate', '1']);
    const attached = await conn.next((message) => message.method === 'Target.attachedToTarget');
    assert.equal(attached.params.sessionId, sessionId);

    const echo = await conn.send('Runtime.evaluate', { expression: '1+1' }, sessionId);
    assert.deepEqual(echo.result.echo.method, 'Runtime.evaluate');
    assert.equal(echo.result.echo.targetId, '1');

    host.emit('1', 'Page.loadEventFired', { timestamp: 7 });
    const event = await conn.next((message) => message.method === 'Page.loadEventFired');
    assert.equal(event.sessionId, sessionId);
    assert.equal(event.params.timestamp, 7);

    const missing = await conn.send('Runtime.evaluate', {}, 'session-nope');
    assert.match(missing.error.message, /Session with given id not found/);

    const versionCommand = await conn.send('Browser.getVersion');
    assert.match(versionCommand.result.product, /Chrome\//);

    const unknown = await conn.send('Target.whatever');
    assert.match(unknown.error.message, /wasn't found/);
    conn.socket.close();
  } finally {
    await bridge.stop();
  }
});

test('create/close target lifecycle drops the sessions bound to it', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  try {
    const version = await (await fetch(`${bridge.endpointUrl()}/json/version`)).json();
    const conn = connectWs(version.webSocketDebuggerUrl);
    await conn.ready;

    const created = await conn.send('Target.createTarget', { url: 'https://example.com/', background: true });
    const targetId = created.result.targetId;
    assert.ok(targetId);
    assert.deepEqual(host.calls.find((call) => call[0] === 'create'), ['create', 'https://example.com/', true]);

    const attach = await conn.send('Target.attachToTarget', { targetId, flatten: true });
    const sessionId = attach.result.sessionId;

    await conn.send('Target.closeTarget', { targetId });
    const detached = await conn.next((message) => message.method === 'Target.detachedFromTarget');
    assert.equal(detached.params.targetId, targetId);

    const gone = await conn.send('Runtime.evaluate', {}, sessionId);
    assert.match(gone.error.message, /Session with given id not found/);

    const targets = await conn.send('Target.getTargets');
    assert.equal(targets.result.targetInfos.some((target) => target.targetId === targetId), false);
    conn.socket.close();
  } finally {
    await bridge.stop();
  }
});

test('page-level socket routes sessionless messages and events to its target', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  try {
    const conn = connectWs(`ws://127.0.0.1:${bridge.listeningPort}/${TOKEN}/devtools/page/1`);
    await conn.ready;

    const navigate = await conn.send('Page.navigate', { url: 'https://example.com/' });
    assert.equal(navigate.result.echo.method, 'Page.navigate');
    assert.equal(navigate.result.echo.targetId, '1');

    host.emit('1', 'Page.frameNavigated', { frame: {} });
    const event = await conn.next((message) => message.method === 'Page.frameNavigated');
    assert.equal(event.sessionId, undefined);
    conn.socket.close();
  } finally {
    await bridge.stop();
  }
});

test('raw framing: fragmented text frames and ping/pong', async () => {
  const host = createHost();
  const bridge = await startBridge(host);
  const port = bridge.listeningPort;
  try {
    const { socket, rest } = await rawHandshake(port);
    let buffer = rest;
    const readFrame = rawFrameReader(() => buffer, (next) => {
      buffer = next;
    }, socket);

    socket.write(maskedFrame(0x9, Buffer.from('abc')));
    const pong = await readFrame();
    assert.equal(pong.opcode, 0xa);
    assert.equal(pong.payload.toString(), 'abc');

    const text = Buffer.from('{"id":7,"method":"Target.getTargets","params":{}}');
    const split = 20;
    socket.write(maskedFrame(0x1, text.subarray(0, split), false));
    socket.write(maskedFrame(0x0, text.subarray(split), true));
    const response = await readFrame();
    assert.equal(response.opcode, 0x1);
    const parsed = JSON.parse(response.payload.toString());
    assert.equal(parsed.id, 7);
    assert.equal(parsed.result.targetInfos[0].targetId, '1');

    socket.destroy();
  } finally {
    await bridge.stop();
  }
});

function rawHandshake(port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      const key = crypto.randomBytes(16).toString('base64');
      socket.write(`GET /${TOKEN}/devtools/browser/raw HTTP/1.1\r\n`
        + `Host: 127.0.0.1:${port}\r\n`
        + 'Upgrade: websocket\r\n'
        + 'Connection: Upgrade\r\n'
        + `Sec-WebSocket-Key: ${key}\r\n`
        + 'Sec-WebSocket-Version: 13\r\n\r\n');
    });
    let buffer = Buffer.alloc(0);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const end = buffer.indexOf('\r\n\r\n');
      if (end < 0) return;
      socket.off('data', onData);
      const statusLine = buffer.subarray(0, end).toString();
      if (!/101 Switching Protocols/.test(statusLine)) {
        reject(new Error(`handshake failed: ${statusLine}`));
        return;
      }
      resolve({ socket, rest: buffer.subarray(end + 4) });
    };
    socket.on('data', onData);
    socket.on('error', reject);
  });
}

function rawFrameReader(getBuffer, setBuffer, socket) {
  return () => new Promise((resolve, reject) => {
    const attempt = () => {
      const buffer = getBuffer();
      if (buffer.length < 2) return false;
      const opcode = buffer[0] & 0x0f;
      let length = buffer[1] & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (buffer.length < 4) return false;
        length = buffer.readUInt16BE(2);
        offset = 4;
      }
      if (buffer.length < offset + length) return false;
      const payload = buffer.subarray(offset, offset + length);
      setBuffer(buffer.subarray(offset + length));
      resolve({ opcode, payload });
      return true;
    };
    if (attempt()) return;
    const timer = setTimeout(() => reject(new Error('raw frame timeout')), 3_000);
    const onData = (chunk) => {
      setBuffer(Buffer.concat([getBuffer(), chunk]));
      if (attempt()) {
        clearTimeout(timer);
        socket.off('data', onData);
      }
    };
    socket.on('data', onData);
  });
}

function maskedFrame(opcode, payload, fin = true) {
  const mask = crypto.randomBytes(4);
  const masked = Buffer.from(payload);
  for (let index = 0; index < masked.length; index += 1) {
    masked[index] ^= mask[index & 3];
  }
  const header = Buffer.from([(fin ? 0x80 : 0x00) | opcode, 0x80 | masked.length]);
  return Buffer.concat([header, mask, masked]);
}
