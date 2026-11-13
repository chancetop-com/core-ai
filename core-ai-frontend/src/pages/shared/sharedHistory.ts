import type { HistoryMessage } from '../../api/session';
import { sharedFileUrl } from '../../api/sessionShares';
import type { SharedSessionArtifact } from '../../api/sessionShares';
import type { ChatAttachment, ChatMessage } from '../chat/types';
import { historyToChatMessages } from '../chat/utils';

const INLINE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif', 'image/bmp']);

export function sharedImageArtifacts(artifacts: SharedSessionArtifact[], token: string): Map<string, SharedSessionArtifact> {
  const images = new Map<string, SharedSessionArtifact>();
  for (const artifact of artifacts) {
    const url = sharedFileUrl(artifact.download_url, token);
    const contentType = artifact.content_type?.split(';')[0].trim().toLowerCase();
    if (url && contentType && INLINE_IMAGE_TYPES.has(contentType)) images.set(url, artifact);
  }
  return images;
}

export function sharedHistoryToChatMessages(messages: HistoryMessage[], artifacts: SharedSessionArtifact[], token: string): ChatMessage[] {
  const images = sharedImageArtifacts(artifacts, token);
  return messages.map(message => {
    const attachments: ChatAttachment[] = [];
    const seen = new Set<string>();
    let fence: { marker: string; length: number } | undefined;
    const lines = (message.content ?? '').split('\n').filter(line => {
      const marker = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
      if (marker) {
        if (!fence) fence = { marker: marker[0], length: marker.length };
        else if (marker[0] === fence.marker && marker.length >= fence.length) fence = undefined;
        return true;
      }
      if (fence || /^( {4}|\t)/.test(line)) return true;
      const url = sharedFileUrl(line.trim(), token);
      const artifact = url ? images.get(url) : undefined;
      if (!url || !artifact) return true;
      if (!seen.has(url)) {
        attachments.push({ type: 'IMAGE', url, file_name: artifact.file_name });
        seen.add(url);
      }
      return false;
    });
    const [result] = historyToChatMessages([{ ...message, content: lines.join('\n') }]);
    return attachments.length ? { ...result, attachments } : result;
  });
}
