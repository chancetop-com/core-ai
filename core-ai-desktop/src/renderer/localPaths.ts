// Pure helpers for markdown that points at files inside the workspace. Generated-image markdown reaches
// the renderer as an absolute path (`C:\ws\out\x.png`) or as the workspace-relative form the
// submit_artifacts tool reports back (`out/x.png`); both map to the workspace-relative key that
// coreai-artifact:// serves. Anything else — http(s), data:, another drive, a `..` escape — is rejected.

/** marked percent-encodes a Windows path's backslashes (`C:\…` becomes `C:%5C…`); decode before matching. */
export function decodePathRef(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

export function workspaceRelativePath(workspace: string | null, value: string | null): string | null {
  if (!workspace || !value) return null;
  const root = slashes(workspace).replace(/\/+$/, '');
  if (!root) return null;
  const ref = stripFileScheme(slashes(value.trim()));
  if (!ref) return null;
  let relative: string;
  if (ref.toLowerCase().startsWith(`${root.toLowerCase()}/`)) {
    relative = ref.slice(root.length + 1);
  } else if (isWorkspaceRelative(ref)) {
    relative = ref;
  } else {
    return null;
  }
  return normalizeRelative(relative);
}

const SCHEME = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

/**
 * A bare relative path (`out/x.png`, `./out/x.png`). A URL scheme disqualifies it, which also rejects
 * another drive's absolute path; `/` rejects POSIX absolutes, UNC shares and root-relative `\out\x`;
 * `#`/`?` are in-page refs, not files.
 */
function isWorkspaceRelative(ref: string): boolean {
  return !SCHEME.test(ref) && !ref.startsWith('/') && !ref.startsWith('#') && !ref.startsWith('?');
}

/** `file:///D:/ws/x.png` is a local path too; `/D:/…` loses the slash the URL form adds before the drive. */
function stripFileScheme(ref: string): string {
  const match = /^file:(\/\/)?(.*)$/i.exec(ref);
  if (!match) return ref;
  return /^\/[a-zA-Z]:\//.test(match[2]) ? match[2].slice(1) : match[2];
}

function normalizeRelative(relative: string): string | null {
  const segments: string[] = [];
  for (const segment of relative.split('/')) {
    if (!segment || segment === '.') continue;
    if (segment === '..') return null;
    segments.push(segment);
  }
  return segments.length ? segments.join('/') : null;
}

function slashes(value: string): string {
  return value.replace(/\\/g, '/');
}
