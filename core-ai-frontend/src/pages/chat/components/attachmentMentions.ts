export interface MentionableAttachment {
  id: string;
  name: string;
  url: string;
  contentType: string;
  uploading: boolean;
}

export interface MentionTrigger {
  start: number;
  end: number;
  query: string;
}

/**
 * Ordinals for ready images only, in list order. They must line up with the "[Image N: name]"
 * labels the agent runtime puts in front of each image, which count the attachments actually sent.
 */
export function imageOrdinals(attachments: readonly MentionableAttachment[]): Map<string, number> {
  const ordinals = new Map<string, number>();
  let next = 1;
  for (const attachment of attachments) {
    if (attachment.uploading || !attachment.contentType.startsWith('image/')) continue;
    ordinals.set(attachment.id, next);
    next += 1;
  }
  return ordinals;
}

export function attachmentBadgeLabel(attachment: MentionableAttachment, ordinals: ReadonlyMap<string, number>): string {
  const ordinal = ordinals.get(attachment.id);
  return ordinal ? `Image ${ordinal} · ${attachment.name}` : attachment.name;
}

export function pastedImageFileName(index: number, mimeType: string): string {
  const extension = mimeType.split('/')[1] || 'png';
  return `pasted-${index}.${extension}`;
}

/** An "@" token that starts the text or follows whitespace and runs unbroken up to the caret. */
export function findMentionTrigger(text: string, caret: number): MentionTrigger | null {
  const before = text.slice(0, caret);
  const start = before.lastIndexOf('@');
  if (start < 0) return null;
  if (start > 0 && !/\s/.test(before[start - 1])) return null;
  const query = before.slice(start + 1);
  if (/\s/.test(query)) return null;
  return { start, end: caret, query };
}

export function mentionCandidates<T extends MentionableAttachment>(
  attachments: readonly T[],
  query: string,
  ordinals: ReadonlyMap<string, number>,
): T[] {
  const needle = query.toLowerCase();
  return attachments.filter(attachment => !attachment.uploading
    && attachmentBadgeLabel(attachment, ordinals).toLowerCase().includes(needle));
}

/** Replace the "@query" token with the plain file name; the model matches the name from its own labels. */
export function applyMention(text: string, trigger: MentionTrigger, name: string): { text: string; caret: number } {
  const inserted = `${name} `;
  return {
    text: text.slice(0, trigger.start) + inserted + text.slice(trigger.end),
    caret: trigger.start + inserted.length,
  };
}
