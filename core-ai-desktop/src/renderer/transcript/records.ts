// Session transcript wire types. Field names are the engine's record keys verbatim: common fields
// are snake_case, event payloads keep whatever the source event used (callId, input_tokens, ...).

export interface TranscriptRecord {
  v?: number;
  seq: number;
  ts?: number;
  turn_id?: string | null;
  kind: string;
  synthetic?: boolean;
  [key: string]: unknown;
}

export interface RecordAttachment {
  filename?: string | null;
  mediaType?: string | null;
  size?: number;
  blob_ref?: string;
  url?: string;
}

export interface TurnInput {
  text?: string;
  text_ref?: string;
  text_preview?: string;
  attachments?: RecordAttachment[];
  client_turn_id?: string | null;
}

export interface PartialTurn {
  turnId: string;
  side: 'head' | 'tail';
}

export interface TranscriptPage {
  records: TranscriptRecord[];
  total_turns: number;
  max_seq: number;
  next_before_seq?: number | null;
  partialTurns?: PartialTurn[];
  oversized?: boolean;
  source: 'transcript' | 'projection' | 'mixed';
}

export function recordText(record: TranscriptRecord, field: string): string | undefined {
  const inline = record[field];
  if (typeof inline === 'string') return inline;
  const preview = record[`${field}_preview`];
  if (typeof preview === 'string') return preview;
  return undefined;
}

export function recordRef(record: TranscriptRecord, field: string): string | undefined {
  const ref = record[`${field}_ref`];
  return typeof ref === 'string' ? ref : undefined;
}

export function recordNumber(record: TranscriptRecord, field: string): number | undefined {
  const value = record[field];
  return typeof value === 'number' ? value : undefined;
}

export function recordBool(record: TranscriptRecord, field: string): boolean | undefined {
  const value = record[field];
  return typeof value === 'boolean' ? value : undefined;
}
