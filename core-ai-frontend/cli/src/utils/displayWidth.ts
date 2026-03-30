const ESC_RE = /\x1b\[[0-9;]*[a-zA-Z]/g;

export function stripAnsi(s: string): string {
  return s.replace(ESC_RE, '');
}

export function displayWidth(s: string): number {
  const clean = stripAnsi(s);
  let w = 0;
  for (const ch of clean) {
    const code = ch.codePointAt(0)!;
    // CJK Unified Ideographs and common wide ranges
    if (
      (code >= 0x1100 && code <= 0x115f) ||
      (code >= 0x2e80 && code <= 0x303e) ||
      (code >= 0x3040 && code <= 0x9fff) ||
      (code >= 0xac00 && code <= 0xd7af) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xfe10 && code <= 0xfe6f) ||
      (code >= 0xff01 && code <= 0xff60) ||
      (code >= 0xffe0 && code <= 0xffe6) ||
      (code >= 0x20000 && code <= 0x2fffd) ||
      (code >= 0x30000 && code <= 0x3fffd)
    ) {
      w += 2;
    } else {
      w += 1;
    }
  }
  return w;
}

export function truncateToWidth(s: string, maxWidth: number): string {
  const clean = stripAnsi(s);
  let w = 0;
  let i = 0;
  for (const ch of clean) {
    const cw = displayWidth(ch);
    if (w + cw > maxWidth) break;
    w += cw;
    i += ch.length;
  }
  return i < clean.length ? clean.slice(0, i) + '...' : s;
}
