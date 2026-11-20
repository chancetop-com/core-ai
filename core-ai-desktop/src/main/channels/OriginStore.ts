// The only place the original channel address lives while an inbound turn is running. The engine
// receives neither the target nor the sender; it can only ask the host to register a watch.
import { randomUUID } from 'node:crypto';
import type { OcgInboundMessage } from './OcgCli';

export interface OriginGrant {
  originId: string;
  inboundMessageId: string;
  workspace: string;
  sessionId: string;
  channel: string;
  accountId: string;
  target: string;
  chatType: string;
  senderId: string | null;
  acceptedAt: number;
  expiresAt: number;
}

export interface OriginInput {
  message: OcgInboundMessage;
  workspace: string;
  sessionId: string;
}

const DEFAULT_TTL_MS = 30 * 60 * 1000;

export class OriginStore {
  private readonly byOrigin = new Map<string, OriginGrant>();
  private readonly byInbound = new Map<string, string>();
  private readonly bySession = new Map<string, string>();

  begin(input: OriginInput): OriginGrant | null {
    const { message } = input;
    if (!message.target || message.chatType !== 'direct') return null;
    const now = Date.now();
    const grant: OriginGrant = {
      originId: `origin-${randomUUID()}`,
      inboundMessageId: message.id,
      workspace: input.workspace,
      sessionId: input.sessionId,
      channel: message.channel,
      accountId: message.accountId,
      target: message.target,
      chatType: message.chatType,
      senderId: message.sender?.id ?? null,
      acceptedAt: now,
      expiresAt: now + DEFAULT_TTL_MS,
    };
    this.byOrigin.set(grant.originId, grant);
    this.byInbound.set(grant.inboundMessageId, grant.originId);
    this.bySession.set(grant.sessionId, grant.originId);
    return grant;
  }

  getForSession(sessionId: string): OriginGrant | null {
    const originId = this.bySession.get(sessionId);
    if (!originId) return null;
    const grant = this.byOrigin.get(originId);
    if (!grant) return null;
    if (grant.expiresAt <= Date.now()) {
      this.clear(grant.inboundMessageId);
      return null;
    }
    return grant;
  }

  get(originId: string): OriginGrant | null {
    return this.byOrigin.get(originId) ?? null;
  }

  clear(inboundMessageId: string): void {
    const originId = this.byInbound.get(inboundMessageId);
    if (!originId) return;
    const grant = this.byOrigin.get(originId);
    this.byOrigin.delete(originId);
    this.byInbound.delete(inboundMessageId);
    if (grant && this.bySession.get(grant.sessionId) === originId) this.bySession.delete(grant.sessionId);
  }

  clearAll(): void {
    this.byOrigin.clear();
    this.byInbound.clear();
    this.bySession.clear();
  }
}
