import { theme } from '../utils/theme.js';

interface Span { start: number; end: number; color: (s: string) => string; }

const KEYWORDS: Record<string, Set<string>> = {
  java: new Set(['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','var','yield','record','sealed','permits','when']),
  javascript: new Set(['async','await','break','case','catch','class','const','continue','debugger','default','delete','do','else','export','extends','finally','for','function','if','import','in','instanceof','let','new','of','return','super','switch','this','throw','try','typeof','var','void','while','with','yield','from','as','type','interface','enum','implements','declare','abstract','readonly','static','private','protected','public','override']),
  python: new Set(['False','None','True','and','as','assert','async','await','break','class','continue','def','del','elif','else','except','finally','for','from','global','if','import','in','is','lambda','nonlocal','not','or','pass','raise','return','try','while','with','yield']),
  go: new Set(['break','case','chan','const','continue','default','defer','else','fallthrough','for','func','go','goto','if','import','interface','map','package','range','return','select','struct','switch','type','var']),
  rust: new Set(['as','async','await','break','const','continue','crate','dyn','else','enum','extern','false','fn','for','if','impl','in','let','loop','match','mod','move','mut','pub','ref','return','self','Self','static','struct','super','trait','true','type','unsafe','use','where','while']),
  shell: new Set(['if','then','else','elif','fi','for','while','do','done','case','esac','in','function','return','local','export','readonly','declare','typeset','unset','shift','source','alias','eval','exec','set','trap','exit','break','continue']),
  sql: new Set(['SELECT','FROM','WHERE','AND','OR','NOT','INSERT','INTO','VALUES','UPDATE','SET','DELETE','CREATE','TABLE','ALTER','DROP','INDEX','JOIN','LEFT','RIGHT','INNER','OUTER','ON','AS','ORDER','BY','GROUP','HAVING','LIMIT','OFFSET','UNION','ALL','DISTINCT','COUNT','SUM','AVG','MIN','MAX','LIKE','IN','BETWEEN','IS','NULL','EXISTS','CASE','WHEN','THEN','ELSE','END']),
};

const ALIASES: Record<string, string> = {
  js: 'javascript', ts: 'javascript', jsx: 'javascript', tsx: 'javascript',
  sh: 'shell', bash: 'shell', zsh: 'shell',
  kt: 'java', kotlin: 'java', scala: 'java', groovy: 'java', cs: 'java',
  cpp: 'c', cc: 'c', h: 'c', hpp: 'c',
  rs: 'rust', yml: 'yaml', py: 'python',
};

function resolveLanguage(lang: string): string {
  const l = lang.toLowerCase();
  return ALIASES[l] || l;
}

function tokenizeSpans(code: string, lang: string): Span[] {
  const spans: Span[] = [];
  const resolved = resolveLanguage(lang);

  if (resolved === 'diff') {
    let pos = 0;
    for (const line of code.split('\n')) {
      if (line.startsWith('+')) spans.push({ start: pos, end: pos + line.length, color: theme.synDiffAdd });
      else if (line.startsWith('-')) spans.push({ start: pos, end: pos + line.length, color: theme.synDiffDel });
      else if (line.startsWith('@@')) spans.push({ start: pos, end: pos + line.length, color: theme.synAnnotation });
      pos += line.length + 1;
    }
    return spans;
  }

  // strings
  const strRe = /(["'`])(?:(?!\1|\\).|\\.)*?\1/g;
  let m: RegExpExecArray | null;
  while ((m = strRe.exec(code)) !== null) {
    spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synString });
  }

  // single-line comments
  const commentRe = resolved === 'python' ? /#[^\n]*/g : /\/\/[^\n]*/g;
  while ((m = commentRe.exec(code)) !== null) {
    spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synComment });
  }

  // numbers
  const numRe = /\b\d+(\.\d+)?\b/g;
  while ((m = numRe.exec(code)) !== null) {
    spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synNumber });
  }

  // annotations
  if (['java', 'python', 'javascript'].includes(resolved)) {
    const annoRe = /@\w+/g;
    while ((m = annoRe.exec(code)) !== null) {
      spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synAnnotation });
    }
  }

  // keywords
  const kw = KEYWORDS[resolved];
  if (kw) {
    const wordRe = /\b[a-zA-Z_]\w*\b/g;
    while ((m = wordRe.exec(code)) !== null) {
      if (kw.has(m[0])) {
        spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synKeyword });
      } else if (/^[A-Z][a-zA-Z0-9]*$/.test(m[0]) && m[0].length > 1) {
        spans.push({ start: m.index, end: m.index + m[0].length, color: theme.synType });
      }
    }
  }

  // remove overlapping spans: earlier spans win
  spans.sort((a, b) => a.start - b.start);
  const result: Span[] = [];
  let lastEnd = 0;
  for (const s of spans) {
    if (s.start >= lastEnd) {
      result.push(s);
      lastEnd = s.end;
    }
  }
  return result;
}

export function highlightCode(code: string, lang: string): string {
  if (!lang) return theme.mdCodeBlock(code);

  const spans = tokenizeSpans(code, lang);
  if (spans.length === 0) return theme.mdCodeBlock(code);

  let result = '';
  let pos = 0;
  for (const span of spans) {
    if (span.start > pos) result += theme.mdCodeBlock(code.slice(pos, span.start));
    result += span.color(code.slice(span.start, span.end));
    pos = span.end;
  }
  if (pos < code.length) result += theme.mdCodeBlock(code.slice(pos));
  return result;
}
