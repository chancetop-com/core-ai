// OCG gates proactive send behind sendSecret/callbackSecret. The desktop's completion
// notifications use that endpoint, so the app provisions a local secret itself instead
// of asking the user to edit ocg.json.
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface EnsureSendSecretResult {
  ok: boolean;
  changed: boolean;
  error?: string;
}

export function ocgConfigPath(): string {
  return process.env.OCG_CONFIG_PATH?.trim() || path.join(os.homedir(), '.openclaw-channel-gateway', 'ocg.json');
}

export function ensureOcgSendSecret(configPath = ocgConfigPath()): EnsureSendSecretResult {
  if (!existsSync(configPath)) return { ok: true, changed: false };

  let raw: string;
  try {
    raw = readFileSync(configPath, 'utf8');
  } catch (error) {
    return { ok: false, changed: false, error: error instanceof Error ? error.message : String(error) };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, changed: false, error: `${configPath} is not valid JSON` };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, changed: false, error: `${configPath} is not a JSON object` };
  }

  const config = parsed as Record<string, unknown>;
  const sendSecret = typeof config.sendSecret === 'string' ? config.sendSecret.trim() : '';
  const callbackSecret = typeof config.callbackSecret === 'string' ? config.callbackSecret.trim() : '';
  if (sendSecret || callbackSecret) return { ok: true, changed: false };

  config.sendSecret = randomBytes(32).toString('hex');
  try {
    writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`, 'utf8');
  } catch (error) {
    return { ok: false, changed: false, error: error instanceof Error ? error.message : String(error) };
  }
  return { ok: true, changed: true };
}
