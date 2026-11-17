// ~/.core-ai/llm-providers.json is the direct-provider credential store shared with the CLI engine.
// It is deliberately separate from auth.json (server sign-in / environment switching) and from
// agent.properties (non-auth tuning): switching environments must never rewrite direct providers.
import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const PROVIDER_TYPES = ['deepseek', 'openai', 'openrouter'] as const;
export type ProviderType = (typeof PROVIDER_TYPES)[number];

export interface ReasoningEffortMap {
  [internalLevel: string]: string;
}

export interface LlmProviderProfile {
  id: string;
  type: ProviderType;
  label: string;
  baseUrl: string;
  apiKey?: string;
  models: string[];
  defaultModel?: string | null;
  reasoningLevels?: string[];
  reasoningEffortMap?: ReasoningEffortMap;
}

export interface ProviderStoreFile {
  version: number;
  /** "server" or a profile id; null/absent means the legacy/default resolution. */
  default?: string | null;
  serverModel?: string | null;
  profiles: LlmProviderProfile[];
}

export interface ProviderProfileView extends Omit<LlmProviderProfile, 'apiKey'> {
  hasApiKey: boolean;
  apiKeyHint: string | null;
}

export interface ProviderStoreView {
  storePath: string;
  signedIn?: boolean;
  defaultConnection: string | null;
  serverModel: string | null;
  profiles: ProviderProfileView[];
  error?: string;
}

export interface ProviderDraft {
  type: string;
  label?: string;
  baseUrl: string;
  models?: string[];
  defaultModel?: string | null;
  reasoningLevels?: string[];
}

export function providersFile(): string {
  return path.join(os.homedir(), '.core-ai', 'llm-providers.json');
}

export function blankStore(): ProviderStoreFile {
  return { version: 1, default: null, serverModel: null, profiles: [] };
}

export function loadProviderStore(file: string = providersFile()): { store: ProviderStoreFile; error?: string } {
  if (!existsSync(file)) return { store: blankStore() };
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8')) as Partial<ProviderStoreFile>;
    return { store: normalizeStore(parsed) };
  } catch (error) {
    return {
      store: blankStore(),
      error: `Provider store is unreadable: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

export function normalizeStore(raw: Partial<ProviderStoreFile> | null | undefined): ProviderStoreFile {
  const store = blankStore();
  if (!raw || typeof raw !== 'object') return store;
  store.version = Number.isInteger(raw.version) ? Number(raw.version) : 1;
  store.default = typeof raw.default === 'string' && raw.default.trim() ? raw.default.trim() : null;
  store.serverModel = typeof raw.serverModel === 'string' && raw.serverModel.trim() ? raw.serverModel.trim() : null;
  const profiles = Array.isArray(raw.profiles) ? raw.profiles : [];
  const seen = new Set<string>();
  for (const entry of profiles as LlmProviderProfile[]) {
    if (!entry || typeof entry !== 'object') continue;
    const type = String(entry.type ?? '').toLowerCase();
    if (!PROVIDER_TYPES.includes(type as ProviderType)) continue;
    if (seen.has(type)) continue;
    seen.add(type);
    const baseUrl = typeof entry.baseUrl === 'string' ? entry.baseUrl.trim() : '';
    if (!baseUrl) continue;
    store.profiles.push({
      id: type,
      type: type as ProviderType,
      label: typeof entry.label === 'string' && entry.label.trim() ? entry.label.trim() : type,
      baseUrl,
      apiKey: typeof entry.apiKey === 'string' ? entry.apiKey : undefined,
      models: Array.isArray(entry.models) ? entry.models.filter((model): model is string => typeof model === 'string' && model.trim().length > 0).map((model) => model.trim()) : [],
      defaultModel: typeof entry.defaultModel === 'string' && entry.defaultModel.trim() ? entry.defaultModel.trim() : null,
      reasoningLevels: Array.isArray(entry.reasoningLevels) ? entry.reasoningLevels.filter((level): level is string => typeof level === 'string') : [],
      reasoningEffortMap: entry.reasoningEffortMap && typeof entry.reasoningEffortMap === 'object' ? entry.reasoningEffortMap : {},
    });
  }
  return store;
}

export function saveProviderStore(store: ProviderStoreFile, file: string = providersFile()): void {
  mkdirSync(path.dirname(file), { recursive: true });
  const target = path.resolve(file);
  const tmp = `${target}.${process.pid}.${Date.now()}.tmp`;
  const handle = openSync(tmp, 'w');
  try {
    writeFileSync(handle, `${JSON.stringify(store, null, 2)}\n`);
    fsyncSync(handle);
  } finally {
    closeSync(handle);
  }
  renameSync(tmp, target);
  try {
    chmodSync(target, 0o600);
  } catch {
    // Windows / non-POSIX file systems: the user profile ACL applies.
  }
}

export function presets(): Record<ProviderType, LlmProviderProfile> {
  return {
    deepseek: {
      id: 'deepseek', type: 'deepseek', label: 'DeepSeek',
      baseUrl: 'https://api.deepseek.com/v1',
      models: ['deepseek-chat', 'deepseek-reasoner'],
      defaultModel: 'deepseek-chat',
      reasoningLevels: ['low', 'high', 'max'],
      reasoningEffortMap: { low: 'low', high: 'high', max: 'xhigh' },
    },
    openai: {
      id: 'openai', type: 'openai', label: 'OpenAI-compatible',
      baseUrl: 'https://api.openai.com/v1',
      models: [], defaultModel: 'gpt-4o',
      reasoningLevels: [], reasoningEffortMap: {},
    },
    openrouter: {
      id: 'openrouter', type: 'openrouter', label: 'OpenRouter',
      baseUrl: 'https://openrouter.ai/api/v1',
      models: [], defaultModel: 'minimax/minimax-m2.7',
      reasoningLevels: [], reasoningEffortMap: {},
    },
  };
}

export function preset(type: ProviderType): LlmProviderProfile {
  return { ...presets()[type] };
}

export function maskKey(apiKey: string | null | undefined): string | null {
  if (!apiKey) return null;
  return apiKey.length >= 8 ? `••••${apiKey.slice(-4)}` : '••••';
}

export function toProfileView(profile: LlmProviderProfile): ProviderProfileView {
  const { apiKey, ...rest } = profile;
  return { ...rest, hasApiKey: Boolean(apiKey), apiKeyHint: maskKey(apiKey) };
}

export function storeView(store: ProviderStoreFile, file: string = providersFile(), error?: string): ProviderStoreView {
  return {
    storePath: file,
    defaultConnection: store.default ?? null,
    serverModel: store.serverModel ?? null,
    profiles: store.profiles.map(toProfileView),
    error,
  };
}

export function effectiveDefaultModel(profile: LlmProviderProfile): string {
  if (profile.defaultModel && profile.defaultModel.trim()) return profile.defaultModel.trim();
  const fallback: Record<ProviderType, string> = { deepseek: 'deepseek-chat', openai: 'gpt-4o', openrouter: 'minimax/minimax-m2.7' };
  return fallback[profile.type];
}

export function upsertProfile(
  store: ProviderStoreFile,
  draft: ProviderDraft,
  apiKey?: string | null,
  clearApiKey = false,
): LlmProviderProfile {
  const type = String(draft?.type ?? '').toLowerCase();
  if (!PROVIDER_TYPES.includes(type as ProviderType)) throw new Error(`unsupported provider type: ${draft?.type}`);
  const baseUrl = String(draft.baseUrl ?? '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(baseUrl)) throw new Error('base URL must start with http:// or https://');
  if (baseUrl.toLowerCase().includes('/chat/completions')) throw new Error('base URL must be the API root, not the completions path');
  const existing = store.profiles.find((profile) => profile.type === type);
  const defaults = preset(type as ProviderType);
  const models = Array.isArray(draft.models)
    ? draft.models.map((model) => String(model).trim()).filter((model) => model.length > 0)
    : existing?.models ?? defaults.models;
  const profile: LlmProviderProfile = {
    id: type,
    type: type as ProviderType,
    label: (draft.label && draft.label.trim()) || existing?.label || defaults.label,
    baseUrl,
    apiKey: existing?.apiKey,
    models,
    defaultModel: draft.defaultModel !== undefined ? (draft.defaultModel || null) : existing?.defaultModel ?? defaults.defaultModel ?? null,
    reasoningLevels: draft.reasoningLevels ?? existing?.reasoningLevels ?? defaults.reasoningLevels ?? [],
    reasoningEffortMap: existing?.reasoningEffortMap ?? defaults.reasoningEffortMap ?? {},
  };
  if (clearApiKey) profile.apiKey = undefined;
  else if (typeof apiKey === 'string' && apiKey.trim()) profile.apiKey = apiKey.trim();
  const index = store.profiles.findIndex((item) => item.type === type);
  if (index >= 0) store.profiles[index] = profile;
  else store.profiles.push(profile);
  return profile;
}

export function deleteProfile(store: ProviderStoreFile, id: string): boolean {
  const removed = store.profiles.some((profile) => profile.id === id);
  store.profiles = store.profiles.filter((profile) => profile.id !== id);
  if (store.default === id) store.default = null;
  return removed;
}

export function setDefaultConnection(store: ProviderStoreFile, connection: string): void {
  if (connection === 'server') {
    store.default = 'server';
    return;
  }
  if (!store.profiles.some((profile) => profile.id === connection)) {
    throw new Error(`provider is not configured: ${connection}`);
  }
  store.default = connection;
}

/** Fallback used when the selected default profile disappears. */
export function fallbackConnection(store: ProviderStoreFile, signedIn: boolean): string | null {
  if (store.profiles.length > 0) return store.profiles[0].id;
  return signedIn ? 'server' : null;
}
