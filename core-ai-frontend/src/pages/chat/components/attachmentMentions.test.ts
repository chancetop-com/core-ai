import { describe, expect, it } from 'vitest';
import {
  applyMention,
  attachmentBadgeLabel,
  findMentionTrigger,
  imageOrdinals,
  mentionCandidates,
  pastedImageFileName,
} from './attachmentMentions';

const image = (id: string, name: string, uploading = false) => ({ id, name, url: `https://blob/${name}`, contentType: 'image/png', uploading });
const pdf = (id: string, name: string) => ({ id, name, url: `https://blob/${name}`, contentType: 'application/pdf', uploading: false });

describe('imageOrdinals', () => {
  it('numbers only ready images, in list order, skipping files and uploads', () => {
    const ordinals = imageOrdinals([pdf('p', 'report.pdf'), image('a', 'menu.png'), image('u', 'slow.png', true), image('b', 'receipt.jpg')]);

    expect(ordinals.get('a')).toBe(1);
    expect(ordinals.get('b')).toBe(2);
    expect(ordinals.has('u')).toBe(false);
    expect(ordinals.has('p')).toBe(false);
  });
});

describe('attachmentBadgeLabel', () => {
  it('prefixes ready images with their ordinal and leaves everything else as the file name', () => {
    const attachments = [image('a', 'menu.png'), image('u', 'slow.png', true), pdf('p', 'report.pdf')];
    const ordinals = imageOrdinals(attachments);

    expect(attachmentBadgeLabel(attachments[0], ordinals)).toBe('Image 1 · menu.png');
    expect(attachmentBadgeLabel(attachments[1], ordinals)).toBe('slow.png');
    expect(attachmentBadgeLabel(attachments[2], ordinals)).toBe('report.pdf');
  });
});

describe('pastedImageFileName', () => {
  it('builds a short sequential name from the mime type', () => {
    expect(pastedImageFileName(1, 'image/png')).toBe('pasted-1.png');
    expect(pastedImageFileName(2, 'image/jpeg')).toBe('pasted-2.jpeg');
    expect(pastedImageFileName(3, '')).toBe('pasted-3.png');
  });
});

describe('findMentionTrigger', () => {
  it('detects an @ token that starts at the beginning or after whitespace', () => {
    expect(findMentionTrigger('@me', 3)).toEqual({ start: 0, end: 3, query: 'me' });
    expect(findMentionTrigger('see @', 5)).toEqual({ start: 4, end: 5, query: '' });
    expect(findMentionTrigger('see @menu tail', 9)).toEqual({ start: 4, end: 9, query: 'menu' });
  });

  it('ignores @ inside a word, after whitespace in the query, or behind the caret', () => {
    expect(findMentionTrigger('mail a@b', 8)).toBeNull();
    expect(findMentionTrigger('hi @x y', 7)).toBeNull();
    expect(findMentionTrigger('@x', 0)).toBeNull();
  });
});

describe('mentionCandidates', () => {
  it('matches ready attachments by name or label, case-insensitively', () => {
    const attachments = [image('a', 'Menu.png'), image('b', 'receipt.jpg'), image('u', 'menu-2.png', true), pdf('p', 'report.pdf')];
    const ordinals = imageOrdinals(attachments);

    expect(mentionCandidates(attachments, 'men', ordinals).map(a => a.id)).toEqual(['a']);
    expect(mentionCandidates(attachments, 'image', ordinals).map(a => a.id)).toEqual(['a', 'b']);
    expect(mentionCandidates(attachments, '', ordinals).map(a => a.id)).toEqual(['a', 'b', 'p']);
  });
});

describe('applyMention', () => {
  it('replaces the @query with the plain file name and a trailing space', () => {
    const result = applyMention('make @me a table', { start: 5, end: 8, query: 'me' }, 'menu.png');

    expect(result.text).toBe('make menu.png  a table');
    expect(result.caret).toBe('make menu.png '.length);
  });
});
