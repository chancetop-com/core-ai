/**
 * Minimal, defensive ZIP reader for plugin packages.
 *
 * Supports only what a plugin archive needs: stored (0) and deflate (8) entries, no encryption and
 * no ZIP64. Everything else is rejected instead of guessed. Path safety is enforced here as well as
 * in the store, because extraction is the first place an archive can escape its staging directory.
 */
import { inflateRawSync } from 'node:zlib';

export interface ZipEntry {
  name: string;
  data: Buffer;
}

export interface ZipLimits {
  maxFiles: number;
  maxFileBytes: number;
  maxTotalBytes: number;
}

export const DEFAULT_ZIP_LIMITS: ZipLimits = {
  maxFiles: 500,
  maxFileBytes: 10 * 1024 * 1024,
  maxTotalBytes: 32 * 1024 * 1024,
};

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const LOCAL_SIGNATURE = 0x04034b50;
const MAX_EOCD_SEARCH = 22 + 0xffff;

export class ZipError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ZipError';
  }
}

export function isSafeZipName(name: string): boolean {
  if (!name || name.length > 512) return false;
  if (name.includes('\\') || name.includes('\u0000')) return false;
  if (name.startsWith('/') || /^[a-zA-Z]:/.test(name)) return false;
  const segments = name.split('/');
  for (const segment of segments) {
    if (!segment || segment === '.' || segment === '..') return false;
    if (/[<>:"|?*]/.test(segment)) return false;
    // Windows device names are invalid with or without an extension.
    if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i.test(segment)) return false;
    if (/[. ]$/.test(segment)) return false;
  }
  return true;
}

export function readZipEntries(buffer: Buffer, limits: ZipLimits = DEFAULT_ZIP_LIMITS): ZipEntry[] {
  if (buffer.length < 22) throw new ZipError('archive is too small');
  const eocd = findEndOfCentralDirectory(buffer);
  if (eocd < 0) throw new ZipError('central directory not found');
  const entryCount = buffer.readUInt16LE(eocd + 10);
  const directorySize = buffer.readUInt32LE(eocd + 12);
  const directoryOffset = buffer.readUInt32LE(eocd + 16);
  if (entryCount === 0xffff || directorySize === 0xffffffff || directoryOffset === 0xffffffff) {
    throw new ZipError('ZIP64 archives are not supported');
  }
  if (entryCount > limits.maxFiles) throw new ZipError(`archive has too many entries (${entryCount})`);
  if (directoryOffset + directorySize > buffer.length) throw new ZipError('central directory is out of bounds');

  const entries: ZipEntry[] = [];
  let total = 0;
  let offset = directoryOffset;
  for (let index = 0; index < entryCount; index += 1) {
    if (offset + 46 > buffer.length || buffer.readUInt32LE(offset) !== CENTRAL_SIGNATURE) {
      throw new ZipError('corrupt central directory');
    }
    const flags = buffer.readUInt16LE(offset + 8);
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const uncompressedSize = buffer.readUInt32LE(offset + 24);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);
    offset += 46 + nameLength + extraLength + commentLength;

    if (flags & 0x1) throw new ZipError('encrypted entries are not supported');
    if (!name || name.endsWith('/')) continue;
    if (!isSafeZipName(name)) throw new ZipError(`unsafe entry path: ${name}`);
    if (method !== 0 && method !== 8) throw new ZipError(`unsupported compression method ${method} for ${name}`);
    if (uncompressedSize > limits.maxFileBytes) throw new ZipError(`entry is too large: ${name}`);
    if (compressedSize > limits.maxFileBytes) throw new ZipError(`compressed entry is too large: ${name}`);

    if (localOffset + 30 > buffer.length || buffer.readUInt32LE(localOffset) !== LOCAL_SIGNATURE) {
      throw new ZipError(`corrupt local header for ${name}`);
    }
    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    if (dataStart + compressedSize > buffer.length) throw new ZipError(`entry data is out of bounds: ${name}`);

    const compressed = buffer.subarray(dataStart, dataStart + compressedSize);
    let data: Buffer;
    if (method === 0) {
      data = Buffer.from(compressed);
    } else {
      try {
        data = inflateRawSync(compressed, { maxOutputLength: Math.min(limits.maxFileBytes, uncompressedSize || limits.maxFileBytes) });
      } catch (error) {
        throw new ZipError(`cannot inflate ${name}: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
    if (data.length > limits.maxFileBytes) throw new ZipError(`entry expands beyond the limit: ${name}`);
    total += data.length;
    if (total > limits.maxTotalBytes) throw new ZipError('archive expands beyond the total size limit');
    entries.push({ name, data });
  }

  if (entries.length === 0) throw new ZipError('archive contains no files');
  return entries;
}

function findEndOfCentralDirectory(buffer: Buffer): number {
  const start = Math.max(0, buffer.length - MAX_EOCD_SEARCH);
  for (let offset = buffer.length - 22; offset >= start; offset -= 1) {
    if (buffer.readUInt32LE(offset) === EOCD_SIGNATURE) return offset;
  }
  return -1;
}
