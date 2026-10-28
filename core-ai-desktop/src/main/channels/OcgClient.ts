// Versioned local OCG HTTP client. The bridge still accepts the CLI port as a
// fallback so an older daemon (no /ocg/v1) keeps working.
import { readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { OcgPort } from './ChannelBridge';
import type { OcgInboundMessage, OcgResult } from './OcgCli';

export interface OcgHealth {
  ok: boolean;
  apiVersion?: number;
  version?: string;
  features?: string[];
  error?: string;
}

export interface OcgHttpOptions {
  baseUrl?: string;
  token?: string | null;
  configPath?: string;
}

export interface OcgChannelAccount {
  accountId: string;
  running: boolean;
}

export interface OcgChannel {
  channelId: string;
  enabled: boolean;
  agentType: string;
  accounts: OcgChannelAccount[];
}

export interface OcgLoginStart {
  loginId: string;
  qrDataUrl: string;
  qrVersion?: number;
  expiresAt: number;
}

export interface OcgLoginStatus {
  loginId: string;
  channelId?: string;
  accountId?: string;
  status: 'pending' | 'confirmed' | 'expired' | 'failed' | 'cancelled' | string;
  qrDataUrl?: string;
  message?: string;
  expiresAt?: number;
}

const DEFAULT_BASE_URL = 'http://127.0.0.1:3457';

function configPathOf(options: OcgHttpOptions): string {
  if (options.configPath) return options.configPath;
  const fromEnv = process.env.OCG_CONFIG_PATH?.trim();
  if (fromEnv) return fromEnv;
  return path.join(os.homedir(), '.openclaw-channel-gateway', 'ocg.json');
}

function baseUrlOf(options: OcgHttpOptions, configPath: string): string {
  if (options.baseUrl) return options.baseUrl.replace(/\/+$/, '');
  const fromEnv = process.env.OCG_DAEMON_URL?.trim();
  if (fromEnv) return fromEnv.replace(/\/+$/, '');
  try {
    const config = JSON.parse(readFileSync(configPath, 'utf8')) as { callbackPort?: unknown };
    const port = Number(config.callbackPort);
    if (Number.isInteger(port) && port > 0) return `http://127.0.0.1:${port}`;
  } catch { /* fall through to the default port */ }
  return DEFAULT_BASE_URL;
}

function tokenOf(options: OcgHttpOptions, configPath: string): string | null {
  if (options.token !== undefined) return options.token;
  try {
    const token = readFileSync(path.join(path.dirname(configPath), 'ocg-local-token'), 'utf8').trim();
    return token || null;
  } catch {
    return null;
  }
}

function errorOf(body: unknown, status: number): { code?: string; error: string } {
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    return {
      code: typeof record.code === 'string' ? record.code : undefined,
      error: typeof record.message === 'string' ? record.message : `OCG request failed (${status})`,
    };
  }
  return { error: `OCG request failed (${status})` };
}

export class OcgHttpPort implements OcgPort {
  private readonly baseUrl: string;
  private readonly token: string | null;

  constructor(options: OcgHttpOptions = {}) {
    const configPath = configPathOf(options);
    this.baseUrl = baseUrlOf(options, configPath);
    this.token = tokenOf(options, configPath);
  }

  async health(): Promise<OcgHealth> {
    try {
      const response = await fetch(`${this.baseUrl}/ocg/v1/health`, {
        headers: this.headers(),
        signal: AbortSignal.timeout(5_000),
      });
      const body = await response.json().catch(() => null) as Record<string, unknown> | null;
      if (!response.ok || body?.ok !== true) {
        return { ok: false, error: errorOf(body, response.status).error };
      }
      return {
        ok: true,
        apiVersion: typeof body.apiVersion === 'number' ? body.apiVersion : undefined,
        version: typeof body.version === 'string' ? body.version : undefined,
        features: Array.isArray(body.features)
          ? body.features.filter((entry): entry is string => typeof entry === 'string')
          : undefined,
      };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) };
    }
  }

  async wait(consumer: string, timeoutSeconds: number): Promise<OcgResult<{ messages: OcgInboundMessage[] }>> {
    const params = new URLSearchParams({
      consumer,
      timeoutMs: String(Math.max(1, timeoutSeconds) * 1000),
    });
    return this.request<{ messages: OcgInboundMessage[] }>(
      'GET',
      `/ocg/v1/inbox/wait?${params.toString()}`,
      undefined,
      (timeoutSeconds + 20) * 1000,
    );
  }

  async reply(
    id: string,
    text: string,
    isError: boolean,
    consumer: string,
    clientReplyId?: string,
  ): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', `/ocg/v1/inbox/${encodeURIComponent(id)}/reply`, {
      text,
      isError,
      consumer,
      ...(clientReplyId ? { clientReplyId } : {}),
    });
  }

  async ack(id: string, consumer: string): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', `/ocg/v1/inbox/${encodeURIComponent(id)}/ack`, { consumer });
  }

  async send(
    channel: string,
    to: string,
    text: string,
    accountId: string,
  ): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', '/ocg/v1/send', { channel, to, text, accountId });
  }

  async channels(): Promise<OcgResult<{ channels: OcgChannel[] }>> {
    return this.request('GET', '/ocg/v1/channels');
  }

  async startChannel(channelId: string, accountId?: string): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', `/ocg/v1/channels/${encodeURIComponent(channelId)}/start`,
      accountId ? { accountId } : {});
  }

  async stopChannel(channelId: string, accountId?: string): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', `/ocg/v1/channels/${encodeURIComponent(channelId)}/stop`,
      accountId ? { accountId } : {});
  }

  async restartChannel(channelId: string, accountId?: string): Promise<OcgResult<Record<string, unknown>>> {
    return this.request('POST', `/ocg/v1/channels/${encodeURIComponent(channelId)}/restart`,
      accountId ? { accountId } : {});
  }

  async startLogin(channelId: string, accountId = 'default'): Promise<OcgResult<OcgLoginStart>> {
    return this.request('POST', `/ocg/v1/channels/${encodeURIComponent(channelId)}/login`, {
      accountId,
      force: true,
    });
  }

  async loginStatus(channelId: string, loginId: string): Promise<OcgResult<OcgLoginStatus>> {
    return this.request(
      'GET',
      `/ocg/v1/channels/${encodeURIComponent(channelId)}/login/${encodeURIComponent(loginId)}`,
    );
  }

  async cancelLogin(channelId: string, loginId: string): Promise<OcgResult<OcgLoginStatus>> {
    return this.request(
      'DELETE',
      `/ocg/v1/channels/${encodeURIComponent(channelId)}/login/${encodeURIComponent(loginId)}`,
    );
  }

  private headers(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      ...(this.token ? { 'X-OCG-Local-Token': this.token } : {}),
    };
  }

  private async request<T>(
    method: 'GET' | 'POST' | 'DELETE',
    urlPath: string,
    body?: Record<string, unknown>,
    timeoutMs = 60_000,
  ): Promise<OcgResult<T>> {
    try {
      const response = await fetch(`${this.baseUrl}${urlPath}`, {
        method,
        headers: this.headers(),
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(timeoutMs),
      });
      const parsed = await response.json().catch(() => null) as Record<string, unknown> | null;
      if (!response.ok || parsed?.ok !== true) {
        return { ok: false, ...errorOf(parsed, response.status) };
      }
      return { ok: true, data: parsed as T };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) };
    }
  }
}

/**
 * Use /ocg/v1 when the daemon advertises it; otherwise fall back to the CLI
 * per call (an old daemon or a daemon that is not up yet). A fallback result
 * is never cached, so the next call upgrades to HTTP once the daemon is ready.
 */
export function createOcgPort(fallback: OcgPort, options: OcgHttpOptions = {}): OcgPort {
  let http: OcgHttpPort | null = null;
  let probing: Promise<OcgHttpPort | null> | null = null;

  const ensure = async (): Promise<OcgHttpPort | null> => {
    if (http) return http;
    if (!probing) {
      probing = (async () => {
        const candidate = new OcgHttpPort(options);
        const health = await candidate.health();
        if (health.ok && (health.apiVersion ?? 0) >= 1) {
          http = candidate;
          return candidate;
        }
        return null;
      })().finally(() => { probing = null; });
    }
    return probing;
  };

  const pick = async (): Promise<OcgPort> => (await ensure()) ?? fallback;
  return {
    wait: async (consumer, timeoutSeconds) => (await pick()).wait(consumer, timeoutSeconds),
    reply: async (id, text, isError, consumer, clientReplyId) =>
      (await pick()).reply(id, text, isError, consumer, clientReplyId),
    ack: async (id, consumer) => (await pick()).ack(id, consumer),
    send: async (channel, to, text, accountId) => (await pick()).send(channel, to, text, accountId),
  };
}
