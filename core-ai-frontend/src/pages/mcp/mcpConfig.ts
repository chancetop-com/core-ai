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

export function validateMcpImportJson(rawJson: string): string | null {
  if (!rawJson.trim()) return 'Paste an MCP configuration first.';

  let root: unknown;
  try {
    root = JSON.parse(rawJson);
  } catch {
    return 'MCP configuration must be valid JSON.';
  }

  if (!isObject(root) || !isObject(root.mcpServers) || Object.keys(root.mcpServers).length === 0) {
    return "MCP configuration must contain a non-empty 'mcpServers' object.";
  }

  for (const [name, config] of Object.entries(root.mcpServers)) {
    if (!name.trim()) return 'MCP server name must not be blank.';
    if (!isObject(config)) return `MCP server '${name}' configuration must be an object.`;

    const hasCommand = hasNonBlankString(config, 'command');
    const hasUrl = hasNonBlankString(config, 'url');
    if (hasCommand && hasUrl) return `MCP server '${name}' must define only one of 'command' or 'url'.`;
    if (!hasCommand && !hasUrl) return `MCP server '${name}' must define either 'command' or 'url'.`;
    if (hasUrl && typeof config.transport === 'string' && config.transport.toLowerCase() === 'sandbox_hosted') {
      return `MCP server '${name}' cannot use 'sandbox_hosted' transport with a URL.`;
    }
  }

  return null;
}
