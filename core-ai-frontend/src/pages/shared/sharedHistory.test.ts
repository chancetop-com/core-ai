import { describe, expect, it } from 'vitest';
import { sharedHistoryToChatMessages } from './sharedHistory';

const imageUrl = '/api/public/session-shares/token/files/image/content';
const image = { file_id: 'image', file_name: 'photo.png', content_type: 'image/png', download_url: imageUrl };

describe('shared history image attachments', () => {
  it('keeps image-only messages visible and preserves their sequence', () => {
    const [message] = sharedHistoryToChatMessages([{ role: 'user', seq: 1, content: imageUrl }], [image], 'token');
    expect(message.seq).toBe(1);
    expect(message.segments).toEqual([]);
    expect(message.attachments).toEqual([{ type: 'IMAGE', url: imageUrl, file_name: 'photo.png' }]);
  });

  it('does not convert unlisted, other-share, unknown-type or active-content files into images', () => {
    for (const artifacts of [[], [{ ...image, content_type: 'text/html' }], [{ ...image, content_type: 'image/svg+xml' }], [{ ...image, content_type: undefined }]]) {
      const [message] = sharedHistoryToChatMessages([{ role: 'user', content: imageUrl }], artifacts, 'token');
      expect(message.attachments).toBeUndefined();
      expect(message.segments).toEqual([{ type: 'text', content: imageUrl }]);
    }
    const [other] = sharedHistoryToChatMessages([{ role: 'user', content: imageUrl }], [image], 'other-token');
    expect(other.attachments).toBeUndefined();
  });

  it('preserves quoted code and existing Markdown images instead of adding duplicate thumbnails', () => {
    const content = `\`\`\`text\n${imageUrl}\n\`\`\`\n\n![photo](${imageUrl})\n\n    ${imageUrl}`;
    const [message] = sharedHistoryToChatMessages([{ role: 'agent', content }], [image], 'token');
    expect(message.attachments).toBeUndefined();
    expect(message.segments).toEqual([{ type: 'text', content }]);
  });
});
