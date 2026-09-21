const DIRECT_SENSITIVE_KEYS = new Set([
  'authorization',
  'proxy_authorization',
  'header',
  'headers',
  'env',
]);

const SENSITIVE_WORDS = new Set([
  'token',
  'tokens',
  'secret',
  'secrets',
  'password',
  'passwd',
  'cookie',
  'cookies',
  'credential',
  'credentials',
]);

function normalizedKey(key: string): string {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

export function isSensitiveConfigKey(key: string): boolean {
  const normalized = normalizedKey(key);
  if (DIRECT_SENSITIVE_KEYS.has(normalized)) return true;

  const words = normalized.split('_').filter(Boolean);
  if (words.some(word => SENSITIVE_WORDS.has(word))) return true;

  const wordPairs = words.slice(0, -1).map((word, index) => `${word}_${words[index + 1]}`);
  return wordPairs.some(pair => pair === 'api_key' || pair === 'private_key' || pair === 'access_key');
}

export function containsSensitiveConfig(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsSensitiveConfig);
  if (!isObject(value)) return false;
  return Object.entries(value).some(([key, nestedValue]) => (
    isSensitiveConfigKey(key) || containsSensitiveConfig(nestedValue)
  ));
}

export function containsSensitiveConfigJson(rawJson: string): boolean {
  try {
    return containsSensitiveConfig(JSON.parse(rawJson));
  } catch {
    return false;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasNonBlankString(object: Record<string, unknown>, key: string): boolean {
  return typeof object[key] === 'string' && object[key].trim().length > 0;
}

function invalid(error: string): McpImportPlan {
  return { kind: 'invalid', error };
}

function validateTransport(label: string, config: Record<string, unknown>): string | null {
  const hasCommand = hasNonBlankString(config, 'command');
  const hasUrl = hasNonBlankString(config, 'url');
  if (hasCommand && hasUrl) return `${label} must define only one of 'command' or 'url'.`;
  if (!hasCommand && !hasUrl) return `${label} must define either 'command' or 'url'.`;
  if (hasUrl && typeof config.transport === 'string' && config.transport.toLowerCase() === 'sandbox_hosted') {
    return `${label} cannot use 'sandbox_hosted' transport with a URL.`;
  }
  return null;
}

function validateServers(servers: Record<string, unknown>): string | null {
  for (const [name, config] of Object.entries(servers)) {
    if (!name.trim()) return 'MCP server name must not be blank.';
    if (!isObject(config)) return `MCP server '${name}' configuration must be an object.`;
    const error = validateTransport(`MCP server '${name}'`, config);
    if (error) return error;
  }
  return null;
}

function importNameCandidate(token: unknown): string {
  if (typeof token !== 'string') return '';
  const value = token.trim();
  if (!value || value.startsWith('-') || value.includes('=') || value.includes('://')) return '';
  const base = value.split(/[\\/]/).pop() ?? '';
  return base.replace(/\.(js|mjs|cjs|ts|py|jar|exe)$/i, '');
}

function importNameFromArgs(args: unknown): string {
  const tokens = Array.isArray(args) ? args : typeof args === 'string' ? args.split(/\s+/) : [];
  for (const token of tokens) {
    const candidate = importNameCandidate(token);
    if (candidate) return candidate;
  }
  return '';
}

function importNameFromUrl(url: unknown): string {
  if (typeof url !== 'string' || !url.trim()) return '';
  try {
    return new URL(url.trim()).hostname;
  } catch {
    return '';
  }
}

/** Best-effort default for the name input when an imported config does not carry one. */
export function suggestMcpServerName(config: Record<string, unknown>): string {
  if (typeof config.name === 'string' && config.name.trim()) return config.name.trim();
  return importNameFromArgs(config.args) || importNameFromUrl(config.url);
}

export type McpImportPlan =
  | { kind: 'invalid'; error: string }
  | { kind: 'multi'; servers: Record<string, unknown> }
  | { kind: 'single'; config: Record<string, unknown>; suggestedName: string };

/**
 * Classifies an import payload: either the standard mcpServers wrapper, or a single server
 * config as shown on a server detail page, which needs a name from the import form.
 */
export function planMcpImport(rawJson: string): McpImportPlan {
  if (!rawJson.trim()) return invalid('Paste an MCP configuration first.');

  let root: unknown;
  try {
    root = JSON.parse(rawJson);
  } catch {
    return invalid('MCP configuration must be valid JSON.');
  }
  if (!isObject(root)) return invalid("MCP configuration must contain a non-empty 'mcpServers' object.");

  if ('mcpServers' in root) {
    const servers = root.mcpServers;
    if (!isObject(servers) || Object.keys(servers).length === 0) {
      return invalid("MCP configuration must contain a non-empty 'mcpServers' object.");
    }
    const error = validateServers(servers);
    return error ? invalid(error) : { kind: 'multi', servers };
  }

  const error = validateTransport('MCP configuration', root);
  if (error) return invalid(error);
  return { kind: 'single', config: root, suggestedName: suggestMcpServerName(root) };
}

/**
 * Every import that creates exactly one server is named by the import form; several servers
 * in one payload keep the names declared by the JSON keys.
 */
export function mcpImportNaming(plan: McpImportPlan): { mode: 'single'; suggestedName: string } | { mode: 'multiple' } | null {
  if (plan.kind === 'single') return { mode: 'single', suggestedName: plan.suggestedName };
  if (plan.kind === 'multi') {
    const names = Object.keys(plan.servers);
    return names.length === 1 ? { mode: 'single', suggestedName: names[0] } : { mode: 'multiple' };
  }
  return null;
}
