// Minimal RFC 6455 server side, sized for the CDP bridge: it only ever serves the local
// browser-use harness (Python websockets) and our own probes. Handles masked client frames,
// fragmentation (websockets splits messages > 32KB into frames), ping/pong and close.
// No extensions are negotiated (permessage-deflate stays off).
import { createHash } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import type { Socket } from 'node:net';

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

const OP_CONTINUATION = 0x0;
const OP_TEXT = 0x1;
const OP_BINARY = 0x2;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

interface Frame {
  fin: boolean;
  opcode: number;
  payload: Buffer;
}

/** Computes the Sec-WebSocket-Accept value for a client key. */
export function webSocketAccept(key: string): string {
  return createHash('sha1').update(key + GUID).digest('base64');
}

export function isWebSocketUpgrade(request: IncomingMessage): boolean {
  return String(request.headers.upgrade ?? '').toLowerCase() === 'websocket';
}

export function webSocketVersionSupported(request: IncomingMessage): boolean {
  return String(request.headers['sec-websocket-version'] ?? '') === '13';
}

/** Writes the 101 handshake response and returns a live connection over the raw socket. */
export function acceptWebSocket(request: IncomingMessage, socket: Socket, head: Buffer): WsConnection {
  const key = String(request.headers['sec-websocket-key'] ?? '');
  socket.setNoDelay(true);
  socket.setTimeout(0);
  socket.write('HTTP/1.1 101 Switching Protocols\r\n'
    + 'Upgrade: websocket\r\n'
    + 'Connection: Upgrade\r\n'
    + `Sec-WebSocket-Accept: ${webSocketAccept(key)}\r\n\r\n`);
  const connection = new WsConnection(socket);
  if (head.length) connection.feed(head);
  return connection;
}

/** One upgraded connection; messages in, messages out, no extensions. */
export class WsConnection {
  onmessage: ((data: string) => void) | null = null;
  onclose: (() => void) | null = null;

  private buffer: Buffer = Buffer.alloc(0);
  private fragments: Buffer[] = [];
  private fragmentOpcode = 0;
  private fragmentBytes = 0;
  private closeSent = false;
  private closed = false;
  private closeNotified = false;

  constructor(private readonly socket: Socket) {
    socket.on('data', (chunk: Buffer) => this.feed(chunk));
    socket.on('error', () => this.finish());
    socket.on('close', () => this.finish());
  }

  feed(chunk: Buffer): void {
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : chunk;
    let frame = this.readFrame();
    while (frame) {
      if (!this.handleFrame(frame)) return;
      frame = this.readFrame();
    }
  }

  sendText(text: string): void {
    this.sendFrame(OP_TEXT, Buffer.from(text, 'utf8'));
  }

  close(code = 1000): void {
    if (this.closed) return;
    this.sendClose(code);
    this.closed = true;
    try {
      this.socket.end();
    } catch {
      // already torn down
    }
  }

  get isClosed(): boolean {
    return this.closed;
  }

  private finish(): void {
    if (this.closeNotified) return;
    this.closeNotified = true;
    this.closed = true;
    this.onclose?.();
  }

  private readFrame(): Frame | null {
    const buffer = this.buffer;
    if (buffer.length < 2) return null;
    const first = buffer[0];
    const second = buffer[1];
    const fin = (first & 0x80) !== 0;
    const opcode = first & 0x0f;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let offset = 2;
    if (length === 126) {
      if (buffer.length < 4) return null;
      length = buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (buffer.length < 10) return null;
      const big = buffer.readBigUInt64BE(2);
      if (big > BigInt(MAX_MESSAGE_BYTES) * 4n) {
        this.protocolError(1009, 'message too big');
        return null;
      }
      length = Number(big);
      offset = 10;
    }
    if (!masked) {
      // A server must reject unmasked client frames.
      this.protocolError(1002, 'client frames must be masked');
      return null;
    }
    if (buffer.length < offset + 4 + length) return null;
    const mask = buffer.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.allocUnsafe(length);
    for (let i = 0; i < length; i += 1) {
      payload[i] = buffer[offset + i] ^ mask[i & 3];
    }
    this.buffer = buffer.subarray(offset + length);
    return { fin, opcode, payload };
  }

  private handleFrame(frame: Frame): boolean {
    const { opcode, payload } = frame;
    if (opcode === OP_PING) {
      this.sendFrame(OP_PONG, payload);
      return !this.closed;
    }
    if (opcode === OP_PONG) return !this.closed;
    if (opcode === OP_CLOSE) {
      this.sendClose(1000);
      this.closed = true;
      try {
        this.socket.end();
      } catch {
        // already torn down
      }
      return false;
    }
    if (opcode === OP_CONTINUATION) {
      if (!this.fragmentOpcode) {
        this.protocolError(1002, 'unexpected continuation frame');
        return false;
      }
      this.pushFragment(payload);
    } else if (opcode === OP_TEXT || opcode === OP_BINARY) {
      if (this.fragmentOpcode) {
        this.protocolError(1002, 'new data frame while fragmented');
        return false;
      }
      this.fragmentOpcode = opcode;
      this.pushFragment(payload);
    } else {
      this.protocolError(1002, `unsupported opcode ${opcode}`);
      return false;
    }
    if (frame.fin) {
      const complete = Buffer.concat(this.fragments, this.fragmentBytes);
      const isText = this.fragmentOpcode === OP_TEXT;
      this.fragments = [];
      this.fragmentBytes = 0;
      this.fragmentOpcode = 0;
      if (isText) this.onmessage?.(complete.toString('utf8'));
      else this.protocolError(1003, 'binary messages are not supported');
    }
    return !this.closed;
  }

  private pushFragment(payload: Buffer): void {
    this.fragmentBytes += payload.length;
    if (this.fragmentBytes > MAX_MESSAGE_BYTES) {
      this.protocolError(1009, 'message too big');
      return;
    }
    this.fragments.push(payload);
  }

  private protocolError(code: number, reason: string): void {
    this.sendClose(code, reason);
    this.closed = true;
    try {
      this.socket.end();
    } catch {
      // already torn down
    }
  }

  private sendClose(code: number, reason = ''): void {
    if (this.closeSent) return;
    this.closeSent = true;
    const reasonBytes = Buffer.from(reason, 'utf8');
    const payload = Buffer.allocUnsafe(2 + reasonBytes.length);
    payload.writeUInt16BE(code, 0);
    reasonBytes.copy(payload, 2);
    this.sendFrame(OP_CLOSE, payload);
  }

  private sendFrame(opcode: number, payload: Buffer): void {
    if (this.socket.destroyed) return;
    const header = Buffer.allocUnsafe(payload.length < 126 ? 2 : payload.length < 65536 ? 4 : 10);
    header[0] = 0x80 | opcode;
    if (payload.length < 126) {
      header[1] = payload.length;
    } else if (payload.length < 65536) {
      header[1] = 126;
      header.writeUInt16BE(payload.length, 2);
    } else {
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    try {
      this.socket.write(header);
      this.socket.write(payload);
    } catch {
      this.finish();
    }
  }
}
