// Stages user-picked files into the engine's per-session attachment staging directory and
// classifies each file into the app-server part type (image / pdf / file). The staging root
// (`~/.core-ai/app-server/staging/<sessionId>`) mirrors EngineSessionRegistry.stagingDir();
// session/send reads attachment paths only inside the workspace or that directory.
import { copyFile, mkdir, readdir, rm, unlink, writeFile } from 'node:fs/promises';
import { existsSync, statSync } from 'node:fs';
import path from 'node:path';

export type AttachmentKind = 'image' | 'file' | 'pdf';

export interface StagedAttachment {
  /** Absolute path of the staged copy handed to the engine as part.path. */
  path: string;
  /** Original file name; the engine labels the attachment with it. */
  name: string;
  kind: AttachmentKind;
  size: number;
  /** Data-URL image preview for the composer tile and the sent-message bubble (main process). */
  preview?: string;
}

export interface SkippedAttachment {
  name: string;
  reason: string;
}

export interface StageResult {
  attachments: StagedAttachment[];
  skipped: SkippedAttachment[];
}

/** In-memory bytes handed over by the renderer (clipboard paste) instead of a source path. */
export interface StagedBufferInput {
  name: string;
  data: ArrayBuffer;
}

const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
const TEXT_EXTENSIONS = new Set([
  'md', 'markdown', 'txt', 'text', 'log', 'json', 'jsonc', 'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf',
  'properties', 'xml', 'csv', 'tsv', 'html', 'htm', 'css', 'scss', 'less', 'js', 'mjs', 'cjs', 'jsx',
  'ts', 'tsx', 'py', 'java', 'kt', 'kts', 'go', 'rs', 'c', 'h', 'cpp', 'cc', 'cxx', 'hpp', 'cs', 'rb',
  'php', 'pl', 'lua', 'r', 'dart', 'swift', 'sh', 'bash', 'zsh', 'ps1', 'bat', 'cmd', 'sql', 'gradle',
  'groovy', 'vue', 'svelte', 'tf',
]);
export const MAX_IMAGE_BYTES = 50 * 1024 * 1024;
export const MAX_PDF_BYTES = 16 * 1024 * 1024;
const MAX_TEXT_BYTES = 100 * 1024;

export function classifyAttachment(fileName: string): AttachmentKind | null {
  const extension = path.extname(fileName).slice(1).toLowerCase();
  if (IMAGE_EXTENSIONS.has(extension)) return 'image';
  if (extension === 'pdf') return 'pdf';
  if (TEXT_EXTENSIONS.has(extension)) return 'file';
  return null;
}

/** Mirrors EngineSessionRegistry.stagingDir (the engine's user.home is the desktop home). */
export function stagingDirOf(stagingRoot: string, sessionId: string): string {
  return path.join(stagingRoot, sessionId);
}

export async function stageAttachments(stagingRoot: string, sessionId: string, sourcePaths: string[]): Promise<StageResult> {
  const dir = stagingDirOf(stagingRoot, sessionId);
  const attachments: StagedAttachment[] = [];
  const skipped: SkippedAttachment[] = [];
  for (const source of sourcePaths) {
    const name = path.basename(String(source ?? ''));
    if (!name) {
      skipped.push({ name: String(source ?? ''), reason: 'not a file' });
      continue;
    }
    const kind = classifyAttachment(name);
    if (!kind) {
      skipped.push({ name, reason: 'unsupported file type' });
      continue;
    }
    let size: number;
    try {
      const info = statSync(source);
      if (!info.isFile()) {
        skipped.push({ name, reason: 'not a file' });
        continue;
      }
      size = info.size;
    } catch (error) {
      skipped.push({ name, reason: `could not be read (${messageOf(error)})` });
      continue;
    }
    if (size > maxBytesOf(kind)) {
      skipped.push({ name, reason: sizeReasonOf(kind) });
      continue;
    }
    try {
      await mkdir(dir, { recursive: true });
      const target = uniqueTarget(dir, name, size);
      if (!existsSync(target)) await copyFile(source, target);
      attachments.push({ path: target, name, kind, size });
    } catch (error) {
      skipped.push({ name, reason: `could not be staged (${messageOf(error)})` });
    }
  }
  return { attachments, skipped };
}

/** Stages pasted bytes under the same name/type/size/reuse rules as picked files. */
export async function stageBuffers(stagingRoot: string, sessionId: string, inputs: StagedBufferInput[]): Promise<StageResult> {
  const dir = stagingDirOf(stagingRoot, sessionId);
  const attachments: StagedAttachment[] = [];
  const skipped: SkippedAttachment[] = [];
  for (const input of inputs) {
    const requested = String(input?.name ?? '').trim();
    const name = path.basename(requested.replace(/\\/g, '/')).replace(/[\u0000-\u001f]/g, '');
    if (!name || name === '.' || name === '..') {
      skipped.push({ name: requested, reason: 'not a file' });
      continue;
    }
    const kind = classifyAttachment(name);
    if (!kind) {
      skipped.push({ name, reason: 'unsupported file type' });
      continue;
    }
    const data = input?.data;
    if (!(data instanceof ArrayBuffer)) {
      skipped.push({ name, reason: 'could not be staged (missing data)' });
      continue;
    }
    if (data.byteLength > maxBytesOf(kind)) {
      skipped.push({ name, reason: sizeReasonOf(kind) });
      continue;
    }
    try {
      await mkdir(dir, { recursive: true });
      const target = uniqueTarget(dir, name, data.byteLength);
      if (!existsSync(target)) await writeFile(target, Buffer.from(data));
      attachments.push({ path: target, name, kind, size: data.byteLength });
    } catch (error) {
      skipped.push({ name, reason: `could not be staged (${messageOf(error)})` });
    }
  }
  return { attachments, skipped };
}

export async function removeStaged(stagingRoot: string, stagedPath: string): Promise<boolean> {
  const root = path.resolve(stagingRoot);
  const target = path.resolve(String(stagedPath ?? ''));
  if (!target.startsWith(root + path.sep)) return false;
  try {
    await unlink(target);
    return true;
  } catch {
    return false;
  }
}

/** Drops staging directories untouched for maxAgeMs; called once at app start. */
export async function sweepStaging(stagingRoot: string, maxAgeMs: number): Promise<number> {
  let removed = 0;
  try {
    const entries = await readdir(stagingRoot, { withFileTypes: true });
    const cutoff = Date.now() - maxAgeMs;
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const dir = path.join(stagingRoot, entry.name);
      try {
        if (statSync(dir).mtimeMs < cutoff) {
          await rm(dir, { recursive: true, force: true });
          removed += 1;
        }
      } catch {
        // locked or already gone — leave it for the next sweep
      }
    }
  } catch {
    // staging root does not exist yet
  }
  return removed;
}

function maxBytesOf(kind: AttachmentKind): number {
  if (kind === 'file') return MAX_TEXT_BYTES;
  return kind === 'pdf' ? MAX_PDF_BYTES : MAX_IMAGE_BYTES;
}

function sizeReasonOf(kind: AttachmentKind): string {
  if (kind === 'file') return 'larger than 100 KB — mention its path in the message instead';
  return kind === 'pdf' ? 'larger than 16 MB' : 'larger than 50 MB';
}

/** Reuses an identical-size copy from an earlier pick; otherwise appends -2, -3… to the name. */
function uniqueTarget(dir: string, name: string, size: number): string {
  const direct = path.join(dir, name);
  if (!existsSync(direct) || sizeOf(direct) === size) return direct;
  const parsed = path.parse(name);
  for (let counter = 2; counter < 1000; counter += 1) {
    const candidate = path.join(dir, `${parsed.name}-${counter}${parsed.ext}`);
    if (!existsSync(candidate) || sizeOf(candidate) === size) return candidate;
  }
  return direct;
}

function sizeOf(file: string): number {
  try {
    return statSync(file).size;
  } catch {
    return -1;
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
