// Route one OCG channel through the desktop mailbox. The bridge only consumes
// mailbox messages, so an http/acp channel never creates desktop sessions until
// its agentType is switched. ACP dispatch uses a daemon-owned subprocess pool that
// is only stopped with the daemon itself, hence the daemon restart for that case.
import type { CommandResult } from '../skills/RuntimeTools';
import type { OcgChannel } from './OcgClient';

export interface OcgRoutingDeps {
  run: (args: string[], timeoutMs?: number) => Promise<CommandResult>;
  channels: () => Promise<{ ok: boolean; data?: { channels: OcgChannel[] }; error?: string }>;
  restartChannel: (channelId: string) => Promise<{ ok: boolean; error?: string }>;
}

export interface RouteToDesktopResult {
  ok: boolean;
  previous?: string;
  daemonRestarted?: boolean;
  warning?: string;
  error?: string;
}

// Dots would turn the config path into a nested object instead of one channel id.
const CHANNEL_ID_PATTERN = /^[A-Za-z0-9_-]+$/;

function lastLine(result: CommandResult): string {
  const text = (result.stderr || result.stdout).trim();
  if (!text) return '';
  const lines = text.split(/\r?\n/u).filter(Boolean);
  return lines[lines.length - 1] ?? '';
}

export async function routeChannelToDesktop(channelId: string, deps: OcgRoutingDeps): Promise<RouteToDesktopResult> {
  const id = String(channelId ?? '').trim();
  if (!CHANNEL_ID_PATTERN.test(id)) return { ok: false, error: 'invalid channel id' };

  const listed = await deps.channels();
  if (!listed.ok) return { ok: false, error: listed.error ?? 'OCG channel list unavailable' };
  const channel = listed.data?.channels.find((entry) => entry.channelId === id);
  if (!channel) return { ok: false, error: `channel not found: ${id}` };
  const previous = channel.agentType;

  const configured = await deps.run(['config', 'set', `channels.${id}.agentType`, 'mailbox'], 30_000);
  if (configured.code !== 0) {
    return { ok: false, error: lastLine(configured) || `ocg config set exited with ${configured.code}` };
  }

  if (previous !== 'acp') {
    const restarted = await deps.restartChannel(id);
    return restarted.ok
      ? { ok: true, previous }
      : { ok: true, previous, warning: `routing saved, but the channel restart failed: ${restarted.error ?? 'unknown error'}` };
  }

  const stopped = await deps.run(['daemon', 'stop', '--json'], 60_000);
  const started = await deps.run(['daemon', 'start', '--json'], 300_000);
  if (stopped.code !== 0 || started.code !== 0) {
    const failure = stopped.code !== 0 ? stopped : started;
    return { ok: true, previous, warning: `routing saved, but the daemon restart failed: ${lastLine(failure) || 'unknown error'}` };
  }
  return { ok: true, previous, daemonRestarted: true };
}
