// Thin wrapper around the OCG CLI for the P0 bridge. Once OCG exposes the versioned
// local HTTP API, this module is replaced by OcgClient without touching the bridge.
import { randomUUID } from 'node:crypto';
import { rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { runOcg } from '../skills/RuntimeTools';

export interface OcgInboundMedia {
  path: string | null;
  url: string | null;
  mime: string | null;
}

export interface OcgInboundMessage {
  id: string;
  channel: string;
  accountId: string;
  target: string | null;
  sessionKey: string;
  chatType: 'direct' | 'group' | 'channel' | 'unknown';
  sender: { id: string | null; name: string | null };
  groupSubject: string | null;
  text: string;
  media: OcgInboundMedia[];
  platformMessageId: string | null;
  replyToId: string | null;
  wasMentioned: boolean | null;
  receivedAt: number;
  replyable: boolean;
}

export interface OcgResult<T> {
  ok: boolean;
  data?: T;
  error?: string;
  code?: string;
}

export interface OcgTarget {
  channel: string;
  accountId?: string;
  target: string;
  chatType?: string;
  senderName?: string | null;
  groupSubject?: string | null;
  lastSeenAt?: number;
  count?: number;
}

function parseJson(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const candidates = [trimmed];
  let index = trimmed.lastIndexOf('\n{');
  while (index >= 0) {
    candidates.push(trimmed.slice(index + 1));
    index = trimmed.lastIndexOf('\n{', index - 1);
  }
  for (const candidate of candidates) {
    try { return JSON.parse(candidate); } catch { /* try the next slice */ }
  }
  return null;
}

async function runJson<T>(args: string[], timeoutMs: number): Promise<OcgResult<T>> {
  const result = await runOcg(args, timeoutMs);
  const data = parseJson(result.stdout) ?? parseJson(result.stderr) as Record<string, unknown> | null;
  if (!data || typeof data !== 'object') {
    const detail = (result.stderr || result.stdout).trim();
    return { ok: false, error: detail || `ocg exited with ${result.code}` };
  }
  const body = data as Record<string, unknown>;
  if (body.ok === false) {
    return {
      ok: false,
      code: typeof body.code === 'string' ? body.code : undefined,
      error: typeof body.message === 'string' ? body.message : 'ocg command failed',
    };
  }
  return { ok: true, data: data as T };
}

export async function ocgWait(consumer: string, timeoutSeconds: number): Promise<OcgResult<{ messages: OcgInboundMessage[] }>> {
  return runJson(
    ['inbox', 'wait', '--timeout', String(timeoutSeconds), '--consumer', consumer, '--json'],
    (timeoutSeconds + 20) * 1000,
  );
}

export async function ocgReply(id: string, text: string, isError = false, consumer?: string, clientReplyId?: string): Promise<OcgResult<Record<string, unknown>>> {
  // Never pass reply text through cmd.exe: %, &, quotes and long bodies are unsafe there.
  const file = path.join(os.tmpdir(), `coreai-channel-reply-${randomUUID()}.txt`);
  writeFileSync(file, text, 'utf8');
  const args = ['reply', id, '--text-file', file, '--json'];
  if (isError) args.push('--error');
  if (consumer) args.push('--consumer', consumer);
  if (clientReplyId) args.push('--client-reply-id', clientReplyId);
  try {
    return await runJson(args, 90_000);
  } finally {
    try { rmSync(file, { force: true }); } catch { /* temp file cleanup is best-effort */ }
  }
}

export async function ocgAck(id: string, consumer?: string): Promise<OcgResult<Record<string, unknown>>> {
  const args = ['inbox', 'ack', id, '--json'];
  if (consumer) args.push('--consumer', consumer);
  return runJson(args, 30_000);
}

/** Proactive send for progress hints; OCG requires sendSecret/callbackSecret to enable it. */
export async function ocgSend(channel: string, to: string, text: string, accountId?: string): Promise<OcgResult<Record<string, unknown>>> {
  const args = ['send', '--channel', channel, '--to', to, '--text', text, '--json'];
  if (accountId) args.push('--account', accountId);
  return runJson(args, 60_000);
}

/** Recently seen peers: the only addresses the user can pick without knowing platform ids. */
export function normalizeOcgTargets(value: unknown): OcgTarget[] {
  if (!Array.isArray(value)) return [];
  const targets: OcgTarget[] = [];
  for (const candidate of value) {
    if (!candidate || typeof candidate !== 'object') continue;
    const record = candidate as Record<string, unknown>;
    const channel = typeof record.channel === 'string' ? record.channel.trim() : '';
    const target = typeof record.target === 'string' ? record.target.trim() : '';
    if (!channel || !target) continue;
    const entry: OcgTarget = { channel, target };
    if (typeof record.accountId === 'string' && record.accountId.trim()) entry.accountId = record.accountId.trim();
    if (typeof record.chatType === 'string' && record.chatType.trim()) entry.chatType = record.chatType.trim();
    if (typeof record.senderName === 'string') entry.senderName = record.senderName;
    if (typeof record.groupSubject === 'string') entry.groupSubject = record.groupSubject;
    if (typeof record.lastSeenAt === 'number') entry.lastSeenAt = record.lastSeenAt;
    if (typeof record.count === 'number') entry.count = record.count;
    targets.push(entry);
  }
  return targets;
}

export async function ocgTargets(): Promise<OcgResult<{ targets: OcgTarget[] }>> {
  const result = await runJson<{ targets?: unknown }>(['targets', 'list', '--json'], 20_000);
  if (!result.ok) return { ok: false, code: result.code, error: result.error };
  return { ok: true, data: { targets: normalizeOcgTargets(result.data?.targets) } };
}

export async function ocgDaemonStatus(): Promise<OcgResult<Record<string, unknown>>> {
  return runJson(['daemon', 'status', '--json'], 15_000);
}
