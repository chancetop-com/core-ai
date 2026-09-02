import { FileText, Paperclip, Video } from 'lucide-react';
import type { MentionableAttachment } from './attachmentMentions';
import { attachmentBadgeLabel } from './attachmentMentions';

interface AttachmentMentionMenuProps {
  candidates: MentionableAttachment[];
  activeIndex: number;
  ordinals: ReadonlyMap<string, number>;
  onSelect: (attachment: MentionableAttachment) => void;
}

function AttachmentIcon({ attachment }: { attachment: MentionableAttachment }) {
  if (attachment.contentType.startsWith('image/')) {
    return <img src={attachment.url} alt="" className="h-6 w-6 shrink-0 rounded object-cover" style={{ background: 'var(--color-bg-tertiary)' }} />;
  }
  if (attachment.contentType.startsWith('video/')) return <Video size={14} className="shrink-0" />;
  if (attachment.contentType === 'application/pdf') return <FileText size={14} className="shrink-0" />;
  return <Paperclip size={14} className="shrink-0" />;
}

export default function AttachmentMentionMenu({ candidates, activeIndex, ordinals, onSelect }: AttachmentMentionMenuProps) {
  return (
    <div
      role="listbox"
      aria-label="Attachments"
      className="absolute left-0 right-0 bottom-full mb-1.5 z-20 max-h-52 overflow-y-auto rounded-xl border p-1 shadow-lg"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      {candidates.map((attachment, index) => (
        <button
          key={attachment.id}
          type="button"
          role="option"
          aria-selected={index === activeIndex}
          className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs cursor-pointer"
          style={{
            background: index === activeIndex ? 'var(--color-primary)' + '18' : 'transparent',
            color: 'var(--color-text)',
          }}
          // Keep focus in the textarea so its blur handler does not close the menu before the click lands
          onMouseDown={event => event.preventDefault()}
          onClick={() => onSelect(attachment)}>
          <AttachmentIcon attachment={attachment} />
          <span className="truncate">{attachmentBadgeLabel(attachment, ordinals)}</span>
        </button>
      ))}
    </div>
  );
}
