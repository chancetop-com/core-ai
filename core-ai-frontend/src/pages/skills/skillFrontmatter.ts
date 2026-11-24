export interface FrontmatterKey {
  key: string;
  value: string;
  raw: string;
}

function blockContent(lines: string[]): string[] {
  const nonEmpty = lines.filter(line => line.trim() !== '');
  const indent = nonEmpty.length > 0 ? Math.min(...nonEmpty.map(line => line.length - line.trimStart().length)) : 0;
  const content = lines.map(line => line.slice(Math.min(indent, line.length)));
  while (content.length > 0 && content[content.length - 1].trim() === '') content.pop();
  return content;
}

function foldLines(lines: string[]): string {
  let result = '';
  for (const line of blockContent(lines)) {
    if (line.trim() === '') {
      result += '\n';
    } else {
      result += (result === '' || result.endsWith('\n') ? '' : ' ') + line;
    }
  }
  return result;
}

function unquote(value: string): string {
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) return value.slice(1, -1);
  if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) return value.slice(1, -1);
  return value;
}

/**
 * Minimal frontmatter parser for the fields the skill editor edits. It understands YAML block
 * scalars (`key: >`, `key: |` with chomping), plain multi-line scalars and block sequences, and
 * keeps the verbatim source of every key so untouched values round-trip byte-identically on save.
 */
export function parseFrontmatter(content: string): { keys: FrontmatterKey[]; body: string } {
  const match = content.match(/^---[^\S\n]*\r?\n([\s\S]*?)\r?\n---[^\S\n]*\r?\n?([\s\S]*)$/);
  if (!match) return { keys: [], body: content };
  const lines = match[1].split('\n').map(line => (line.endsWith('\r') ? line.slice(0, -1) : line));
  const keys: FrontmatterKey[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const keyMatch = /^([A-Za-z0-9_.-]+):(.*)$/.exec(line);
    if (!keyMatch) {
      i++;
      continue;
    }
    const key = keyMatch[1];
    const inline = keyMatch[2].trim();
    const blockStyle = /^([>|])[-+]?\d*$/.exec(inline);
    const continuation: string[] = [];
    let j = i + 1;
    while (j < lines.length && (lines[j].trim() === '' || /^\s/.test(lines[j]))) {
      continuation.push(lines[j]);
      j++;
    }
    const raw = [line, ...continuation].join('\n');
    let value: string;
    if (blockStyle) {
      value = blockStyle[1] === '>' ? foldLines(continuation) : blockContent(continuation).join('\n');
    } else if (continuation.some(entry => entry.trim() !== '')) {
      const first = continuation.find(entry => entry.trim() !== '') ?? '';
      if (/^\s*-\s/.test(first)) {
        value = continuation.filter(entry => entry.trim() !== '')
          .map(entry => unquote(entry.trim().replace(/^-\s+/, ''))).join(' ');
      } else {
        value = foldLines([`  ${inline}`, ...continuation]);
      }
    } else {
      value = unquote(inline);
    }
    keys.push({ key, value, raw });
    i = j;
  }
  return { keys, body: match[2] };
}

function isPlainSafe(value: string): boolean {
  if (value !== value.trim() || value.endsWith(':')) return false;
  if (/^[-?:,[\]{}#&*!|>'"%@`]/.test(value)) return false;
  return !/:\s|\s#/.test(value);
}

function renderScalar(key: string, value: string): string {
  if (value.includes('\n')) {
    const block = value.split('\n').map(line => (line ? `  ${line}` : '')).join('\n');
    return `${key}: |-\n${block}`;
  }
  if (isPlainSafe(value)) return `${key}: ${value}`;
  return `${key}: "${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

const EDITABLE_KEYS = ['name', 'description', 'allowed-tools', 'version'];

/** The server publishes metadata.version; a top-level version is only a fallback for skills without metadata. */
function metadataVersion(raw: string): string | null {
  const line = raw.split('\n').find(entry => /^\s+version\s*:/.test(entry));
  if (!line) return null;
  return unquote(line.replace(/^\s+version\s*:\s*/, '').trim());
}

function renderVersion(value: string): string {
  // Always quote: YAML would turn 1.10 into the float 1.1
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function withMetadataVersion(raw: string, version: string): string {
  if (raw.includes('{')) {
    return raw.replace(/(\bversion\s*:\s*)("[^"]*"|'[^']*'|[^,}]+)/, (_match, prefix: string) =>
      `${prefix}${renderVersion(version)}`);
  }
  const lines = raw.split('\n');
  const index = lines.findIndex(entry => /^\s+version\s*:/.test(entry));
  if (index >= 0) {
    const indent = lines[index].match(/^\s*/)?.[0] ?? '  ';
    lines[index] = `${indent}version: ${renderVersion(version)}`;
    return lines.join('\n');
  }
  const indent = lines.slice(1).find(entry => /^\s+\S/.test(entry))?.match(/^\s*/)?.[0] ?? '  ';
  lines.splice(1, 0, `${indent}version: ${renderVersion(version)}`);
  return lines.join('\n');
}

/**
 * Rebuilds SKILL.md with edited fields applied. Values that still match the loaded frontmatter
 * reuse their original source text (block scalars stay block scalars), other keys are preserved
 * verbatim, and only changed values are re-rendered — so saving never silently drops what the
 * editor cannot show.
 */
export function buildSkillMdContent(
  original: FrontmatterKey[],
  fields: { name: string; description: string; allowedTools: string[]; version: string },
  body: string,
): string {
  const metadata = original.find(item => item.key === 'metadata');
  const current: Record<string, string> = {
    name: fields.name,
    description: fields.description,
    'allowed-tools': fields.allowedTools.join(' '),
    version: fields.version,
  };
  const lines: string[] = [];
  const emit = (key: string) => {
    const value = current[key];
    if (!value) return;
    const existing = original.find(item => item.key === key);
    lines.push(existing && existing.value === value ? existing.raw : renderScalar(key, value));
  };
  const seen = new Set<string>();
  for (const item of original) {
    seen.add(item.key);
    if (item.key === 'metadata' && fields.version && metadataVersion(item.raw) !== fields.version) {
      lines.push(withMetadataVersion(item.raw, fields.version));
    } else if (item.key in current) emit(item.key);
    else lines.push(item.raw);
  }
  for (const key of EDITABLE_KEYS) {
    if (seen.has(key)) continue;
    if (key === 'version' && metadata) continue;
    emit(key);
  }
  return '---\n' + lines.join('\n') + '\n---\n' + body;
}
