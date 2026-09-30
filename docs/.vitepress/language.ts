import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function slug(text: string) {
  return text.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[\u0000-\u001f]/g, '')
    .replace(/[\s~`!@#$%^&*()\-_+=[\]{}|\\;:"'“”‘’<>,.?/]+/g, '-')
    .replace(/-{2,}/g, '-').replace(/^-+|-+$/g, '').replace(/^(\d)/, '_$1').toLowerCase();
}

export function alternateFor(relativePath: string, docsRoot: string) {
  const english = relativePath.startsWith('en/');
  const candidate = relativePath === 'api/index.md' ? 'en/api.md' : relativePath === 'index.md' ? 'en/index.md'
    : relativePath.replace(/^(cn|en)\//, english ? 'cn/' : 'en/');
  const translated = candidate !== relativePath && existsSync(resolve(docsRoot, candidate));
  const target = translated ? candidate : english ? 'cn/index.md' : 'en/index.md';
  const source = readFileSync(resolve(docsRoot, target), 'utf8');
  const ids: string[] = [];
  const occurrences = new Map<string, number>();
  let fenced = false;
  for (const line of source.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) { fenced = !fenced; continue; }
    if (fenced) continue;
    const heading = /^#{1,6}\s+(.+)$/.exec(line);
    if (heading) {
      const explicit = /\{#([^}]+)\}\s*$/.exec(heading[1]);
      const id = explicit?.[1] || slug(heading[1].replace(/\[([^\]]+)\]\([^)]*\)/g, '$1'));
      const count = occurrences.get(id) || 0;
      ids.push(count ? `${id}-${count}` : id);
      occurrences.set(id, count + 1);
    }
    for (const match of line.matchAll(/\bid="([^"]+)"/g)) ids.push(match[1]);
  }
  return { href: '/' + target.replace(/index\.md$/, '').replace(/\.md$/, ''), translated, ids };
}
