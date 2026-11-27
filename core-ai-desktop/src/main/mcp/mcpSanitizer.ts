// Redacts known MCP secret values, bearer/basic tokens, ANSI escape codes, and URL
// userinfo/query segments from Desktop logs, engine:log forwarding, and display summaries.

const MIN_SECRET_LENGTH = 4;
// eslint-disable-next-line no-control-regex
const ANSI_PATTERN = /\x1b\[[0-9;]*[A-Za-z]/g;
const URL_PATTERN = /https?:\/\/[^\s"'<>)\]]+/g;
const AUTH_SCHEME_PATTERN = /\b(Bearer|Basic)\s+[^\s"',;]+/gi;
const USERINFO_FALLBACK = /(https?:\/\/)[^/@\s]+@/g;

export function sanitizeUrlForDisplay(url: string): string {
  if (!url) return '';
  const trimmed = url.trim();
  try {
    const parsed = new URL(trimmed);
    const port = parsed.port ? `:${parsed.port}` : '';
    return `${parsed.protocol}//${parsed.hostname}${port}${parsed.pathname}`;
  } catch {
    return trimmed
      .replace(USERINFO_FALLBACK, '$1')
      .replace(/[?#].*$/, '');
  }
}

export function secretHint(value: string): string {
  if (!value || value.length <= MIN_SECRET_LENGTH) return '••••';
  return `••••${value.slice(-4)}`;
}

export class McpLogRedactor {
  private readonly secrets = new Set<string>();

  register(values: Iterable<string | null | undefined>): void {
    for (const value of values) {
      if (typeof value === 'string' && value.length >= MIN_SECRET_LENGTH) {
        this.secrets.add(value);
      }
    }
  }

  registerSecretMap(secretsByServer: Record<string, Record<string, string>>): void {
    for (const entries of Object.values(secretsByServer)) {
      if (!entries || typeof entries !== 'object') continue;
      this.register(Object.values(entries));
    }
  }

  clear(): void {
    this.secrets.clear();
  }

  redact(line: string): string {
    if (!line) return '';
    let clean = line.replace(ANSI_PATTERN, '');
    clean = clean.replace(URL_PATTERN, (match) => sanitizeUrlForDisplay(match));
    clean = clean.replace(AUTH_SCHEME_PATTERN, '$1 ***');
    if (this.secrets.size > 0) {
      const ordered = [...this.secrets].sort((a, b) => b.length - a.length);
      for (const secret of ordered) {
        if (clean.includes(secret)) {
          clean = clean.split(secret).join('***');
        }
      }
    }
    return clean;
  }
}
