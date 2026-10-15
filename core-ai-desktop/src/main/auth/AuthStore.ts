// ~/.core-ai/auth.json is the shared sign-in store: the desktop writes it, the CLI engine reads
// it (AuthConfig). Entries keep a per-server `insecure` flag — the explicit opt-in to skip TLS
// verification for a local server, which every server-facing client of the engine honors.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface AuthEntry {
  server_url: string;
  api_key: string;
  user_id?: string | null;
  name?: string | null;
  role?: string | null;
  login_at?: string | null;
  active?: boolean;
  insecure?: boolean;
}

export interface AuthStatus {
  loggedIn: boolean;
  name: string | null;
  userId: string | null;
  role: string | null;
  serverUrl: string | null;
  loginAt: string | null;
  insecure: boolean;
}

export interface AuthServerEntry {
  serverUrl: string;
  name: string | null;
  userId: string | null;
  active: boolean;
  loginAt: string | null;
  insecure: boolean;
}

export function authFile(): string {
  return path.join(os.homedir(), '.core-ai', 'auth.json');
}

export function loadAuthEntries(file: string = authFile()): AuthEntry[] {
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8')) as AuthEntry | AuthEntry[];
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return [];
  }
}

export function writeAuthEntries(entries: AuthEntry[], file: string = authFile()): void {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(entries, null, 2));
}

export function activeAuthEntry(entries: AuthEntry[]): AuthEntry | null {
  return entries.find((entry) => entry.active && entry.api_key) ?? null;
}

export function authStatusOf(entries: AuthEntry[]): AuthStatus {
  const entry = activeAuthEntry(entries);
  if (!entry) return { loggedIn: false, name: null, userId: null, role: null, serverUrl: null, loginAt: null, insecure: false };
  return {
    loggedIn: true,
    name: entry.name ?? null,
    userId: entry.user_id ?? null,
    role: entry.role ?? null,
    serverUrl: entry.server_url ?? null,
    loginAt: entry.login_at ?? null,
    insecure: entry.insecure === true,
  };
}

export function authServersOf(entries: AuthEntry[]): AuthServerEntry[] {
  return entries
    .filter((entry) => entry.server_url && entry.api_key)
    .map((entry) => ({
      serverUrl: entry.server_url,
      name: entry.name ?? null,
      userId: entry.user_id ?? null,
      active: Boolean(entry.active),
      loginAt: entry.login_at ?? null,
      insecure: entry.insecure === true,
    }))
    .sort((a, b) => Number(b.active) - Number(a.active) || (b.loginAt ?? '').localeCompare(a.loginAt ?? ''));
}

/** The stored entry for a fresh login; the previous role survives, the TLS opt-in is the new one. */
export function withSavedEntry(entries: AuthEntry[], serverUrl: string, apiKey: string, userId: string | null,
                               name: string | null, insecure: boolean): AuthEntry[] {
  const previous = entries.find((entry) => entry.server_url === serverUrl);
  const kept = entries.filter((entry) => entry.server_url !== serverUrl).map((entry) => ({ ...entry, active: false }));
  kept.push({
    server_url: serverUrl,
    api_key: apiKey,
    user_id: userId,
    name,
    role: previous?.role ?? null,
    login_at: new Date().toISOString(),
    active: true,
    insecure,
  });
  return kept;
}

export function withActive(entries: AuthEntry[], serverUrl: string): AuthEntry[] {
  return entries.map((entry) => ({ ...entry, active: entry.server_url === serverUrl }));
}

export function withInsecure(entries: AuthEntry[], serverUrl: string, insecure: boolean): AuthEntry[] {
  return entries.map((entry) => (entry.server_url === serverUrl ? { ...entry, insecure } : entry));
}

export function without(entries: AuthEntry[], serverUrl: string): AuthEntry[] {
  return entries.filter((entry) => entry.server_url !== serverUrl);
}
