// Simple OpenClaw-style bindings for IM conversations: match channel/account/peer
// and choose the desktop workspace. The most specific binding wins; ties keep
// config order. Workspace existence is checked by the caller.
import type { OcgInboundMessage } from './OcgCli';

export interface ChannelBinding {
  match: {
    channel?: string;
    accountId?: string;
    peer?: { kind?: string; id?: string };
  };
  workspace: string;
}

export function resolveBindingWorkspace(
  bindings: ChannelBinding[] | undefined,
  message: Pick<OcgInboundMessage, 'channel' | 'accountId' | 'chatType' | 'target'>,
): string | null {
  if (!bindings?.length) return null;
  let bestScore = -1;
  let bestWorkspace: string | null = null;

  for (const binding of bindings) {
    const match = binding?.match;
    if (!match?.channel || match.channel !== message.channel) continue;
    if (!binding.workspace?.trim()) continue;

    let score = 10;
    if (match.accountId === "*") {
      score += 5;
    } else if (match.accountId && match.accountId !== message.accountId) {
      continue;
    } else if (match.accountId) {
      score += 20;
    }

    if (match.peer) {
      if (match.peer.kind && match.peer.kind !== "*" && match.peer.kind !== message.chatType) continue;
      const peerId = message.target ?? "";
      if (match.peer.id && match.peer.id !== "*") {
        if (match.peer.id !== peerId) continue;
        score += 40;
      } else {
        score += 30;
      }
    }

    if (score > bestScore) {
      bestScore = score;
      bestWorkspace = binding.workspace;
    }
  }

  return bestWorkspace;
}
