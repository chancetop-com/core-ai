import { forwardRef, memo, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { ChangeEvent, KeyboardEvent, RefObject } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Database,
  Loader2,
  Maximize2,
  Mic,
  Minimize2,
  Paperclip,
  Send,
  Settings,
  Sparkles,
  Square,
  Users,
  Wrench,
  X,
} from 'lucide-react';

type ChatStatus = 'idle' | 'running';

export interface ComposerAttachment {
  url: string;
  type: 'PDF' | 'IMAGE';
}

export interface ChatComposerHandle {
  focus: () => void;
  reset: () => void;
  setDraft: (text: string) => void;
}

interface PendingAttachment {
  id: string;
  name: string;
  url: string;
  contentType: string;
  uploading: boolean;
}

interface ChatComposerProps {
  status: ChatStatus;
  selectedAgentId: string;
  messagesContainerRef: RefObject<HTMLDivElement | null>;
  loadedToolIds: Set<string>;
  loadedSkillIds: Set<string>;
  loadedSubAgentIds: Set<string>;
  preToolIds: Set<string>;
  preSkillIds: Set<string>;
  preSubAgentIds: Set<string>;
  loadedDatasetId: string | null;
  preDatasetId: string | null;
  showVoiceSidebar: boolean;
  getSkillChipName: (id: string) => string;
  getAgentChipName: (id: string) => string;
  getDatasetChipName: (id: string | null) => string;
  onOpenConfig: () => void;
  onToggleVoiceSidebar: () => void;
  onSend: (text: string, attachments: ComposerAttachment[]) => void | Promise<void>;
  onCancel: () => void;
  onToast: (message: string) => void;
}

const VALID_ATTACHMENT_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/svg+xml',
  'application/pdf',
]);

const MAX_ATTACHMENT_SIZE = 50 * 1024 * 1024;
const COLLAPSE_THRESHOLD = 8;

interface ComposerConfigChipsProps {
  totalChips: number;
  datasetChips: number;
  loadedToolIds: Set<string>;
  loadedSkillIds: Set<string>;
  loadedSubAgentIds: Set<string>;
  preToolIds: Set<string>;
  preSkillIds: Set<string>;
  preSubAgentIds: Set<string>;
  loadedDatasetId: string | null;
  preDatasetId: string | null;
  getSkillChipName: (id: string) => string;
  getAgentChipName: (id: string) => string;
  getDatasetChipName: (id: string | null) => string;
}

const ComposerConfigChips = memo(function ComposerConfigChips({
  totalChips,
  datasetChips,
  loadedToolIds,
  loadedSkillIds,
  loadedSubAgentIds,
  preToolIds,
  preSkillIds,
  preSubAgentIds,
  loadedDatasetId,
  preDatasetId,
  getSkillChipName,
  getAgentChipName,
  getDatasetChipName,
}: ComposerConfigChipsProps) {
  const [chipsExpanded, setChipsExpanded] = useState(false);
  const collapsible = totalChips > COLLAPSE_THRESHOLD;
  const collapsed = collapsible && !chipsExpanded;
  const effectiveDatasetId = preDatasetId || loadedDatasetId;
  const datasetPending = Boolean(preDatasetId);

  if (totalChips === 0) return null;

  return (
    <div className="mb-2">
      {collapsible && (
        <button
          onClick={() => setChipsExpanded(value => !value)}
          className="inline-flex items-center gap-1 text-xs mb-1.5 cursor-pointer hover:opacity-80"
          style={{ color: 'var(--color-text-secondary)' }}>
          {collapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
          <span>
            {loadedToolIds.size + preToolIds.size > 0 && `${loadedToolIds.size + preToolIds.size} tools`}
            {loadedSkillIds.size + preSkillIds.size > 0 && `${loadedToolIds.size + preToolIds.size > 0 ? ', ' : ''}${loadedSkillIds.size + preSkillIds.size} skills`}
            {loadedSubAgentIds.size + preSubAgentIds.size > 0 && `${(loadedToolIds.size + preToolIds.size > 0 || loadedSkillIds.size + preSkillIds.size > 0) ? ', ' : ''}${loadedSubAgentIds.size + preSubAgentIds.size} agents`}
            {datasetChips > 0 && `${(loadedToolIds.size + preToolIds.size > 0 || loadedSkillIds.size + preSkillIds.size > 0 || loadedSubAgentIds.size + preSubAgentIds.size > 0) ? ', ' : ''}${getDatasetChipName(effectiveDatasetId)}`}
            {' loaded'}
          </span>
        </button>
      )}
      {!collapsed && (
        <div className="flex flex-wrap gap-1.5 min-h-[24px]">
          {Array.from(loadedToolIds).map(name => (
            <span key={`t-${name}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: 'var(--color-primary)' + '18',
                color: 'var(--color-primary)',
                border: '1px solid var(--color-primary)' + '30',
              }}>
              <Wrench size={10} />
              {name}
            </span>
          ))}
          {Array.from(preToolIds).map(name => (
            <span key={`pt-${name}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: 'var(--color-primary)' + '08',
                color: 'var(--color-text-muted)',
                border: '1px dashed var(--color-border)',
              }}>
              <Wrench size={10} />
              {name}
              <span className="ml-0.5 opacity-60">(pending)</span>
            </span>
          ))}
          {Array.from(loadedSkillIds).map(id => (
            <span key={`s-${id}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: 'var(--color-warning)' + '18',
                color: 'var(--color-warning)',
                border: '1px solid var(--color-warning)' + '30',
              }}>
              <Sparkles size={10} />
              {getSkillChipName(id)}
            </span>
          ))}
          {Array.from(preSkillIds).map(id => (
            <span key={`ps-${id}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: 'var(--color-warning)' + '08',
                color: 'var(--color-text-muted)',
                border: '1px dashed var(--color-border)',
              }}>
              <Sparkles size={10} />
              {getSkillChipName(id)}
              <span className="ml-0.5 opacity-60">(pending)</span>
            </span>
          ))}
          {Array.from(loadedSubAgentIds).map(id => (
            <span key={`lsa-${id}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: '#8b5cf6' + '18',
                color: '#8b5cf6',
                border: '1px solid #8b5cf6' + '30',
              }}>
              <Users size={10} />
              {getAgentChipName(id)}
            </span>
          ))}
          {Array.from(preSubAgentIds).map(id => (
            <span key={`psa-${id}`}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: '#8b5cf6' + '08',
                color: 'var(--color-text-muted)',
                border: '1px dashed var(--color-border)',
              }}>
              <Users size={10} />
              {getAgentChipName(id)}
              <span className="ml-0.5 opacity-60">(pending)</span>
            </span>
          ))}
          {effectiveDatasetId && (
            <span key="ds-effective"
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
              style={{
                background: datasetPending ? '#3b82f6' + '08' : '#3b82f6' + '18',
                color: datasetPending ? 'var(--color-text-muted)' : '#3b82f6',
                border: datasetPending ? '1px dashed var(--color-border)' : '1px solid #3b82f6' + '30',
              }}>
              <Database size={10} />
              {getDatasetChipName(effectiveDatasetId)}
              {datasetPending && <span className="ml-0.5 opacity-60">(pending)</span>}
            </span>
          )}
        </div>
      )}
    </div>
  );
});

const ChatComposer = memo(forwardRef<ChatComposerHandle, ChatComposerProps>(function ChatComposer({
  status,
  selectedAgentId,
  messagesContainerRef,
  loadedToolIds,
  loadedSkillIds,
  loadedSubAgentIds,
  preToolIds,
  preSkillIds,
  preSubAgentIds,
  loadedDatasetId,
  preDatasetId,
  showVoiceSidebar,
  getSkillChipName,
  getAgentChipName,
  getDatasetChipName,
  onOpenConfig,
  onToggleVoiceSidebar,
  onSend,
  onCancel,
  onToast,
}, ref) {
  const [input, setInput] = useState('');
  const [isInputExpanded, setIsInputExpanded] = useState(false);
  const [needsExpand, setNeedsExpand] = useState(false);
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useImperativeHandle(ref, () => ({
    focus: () => inputRef.current?.focus(),
    reset: () => {
      setInput('');
      setIsInputExpanded(false);
      setNeedsExpand(false);
      setPendingAttachments([]);
    },
    setDraft: (text: string) => {
      setInput(text);
      setIsInputExpanded(false);
      requestAnimationFrame(() => inputRef.current?.focus());
    },
  }), []);

  useEffect(() => {
    const textarea = inputRef.current;
    if (!textarea) return;

    if (isInputExpanded) {
      textarea.style.height = 'auto';
      const maxHeight = (messagesContainerRef.current?.clientHeight ?? 600) / 3;
      const newHeight = Math.min(textarea.scrollHeight, maxHeight);
      textarea.style.height = `${newHeight}px`;
      textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
    } else {
      textarea.style.height = '';
      textarea.style.overflowY = 'hidden';
      const lineHeight = parseFloat(getComputedStyle(textarea).lineHeight) || 20;
      setNeedsExpand(textarea.scrollHeight > lineHeight * 1.5);
    }
  }, [input, isInputExpanded, messagesContainerRef]);

  const handleFileSelect = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(async (event: ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files;
    if (!files || files.length === 0) return;

    for (const file of Array.from(files)) {
      if (!VALID_ATTACHMENT_TYPES.has(file.type)) {
        onToast(`Unsupported file type: ${file.type}. Only images and PDFs are allowed.`);
        continue;
      }
      if (file.size > MAX_ATTACHMENT_SIZE) {
        onToast(`File too large: ${file.name}. Maximum size is 50MB.`);
        continue;
      }

      const id = crypto.randomUUID();
      setPendingAttachments(prev => [...prev, {
        id,
        name: file.name,
        url: '',
        contentType: file.type,
        uploading: true,
      }]);

      try {
        const credentialResponse = await fetch(`/api/blob/upload-credential?content_type=${encodeURIComponent(file.type)}`, {
          headers: { Authorization: `Bearer ${localStorage.getItem('apiKey')}` },
        });
        if (!credentialResponse.ok) throw new Error(`Credential request failed: ${credentialResponse.status}`);
        const credential = await credentialResponse.json();

        const uploadResponse = await fetch(credential.upload_url, {
          method: 'PUT',
          headers: {
            'x-ms-blob-type': 'BlockBlob',
            'x-ms-blob-content-type': file.type,
          },
          body: file,
        });
        if (!uploadResponse.ok) throw new Error(`Upload failed: ${uploadResponse.status}`);

        setPendingAttachments(prev => prev.map(attachment =>
          attachment.id === id
            ? { ...attachment, url: credential.blob_url, contentType: file.type, uploading: false }
            : attachment
        ));
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        onToast(`Upload failed for ${file.name}: ${message}`);
        setPendingAttachments(prev => prev.filter(attachment => attachment.id !== id));
      }
    }

    if (fileInputRef.current) fileInputRef.current.value = '';
  }, [onToast]);

  const removeAttachment = useCallback((id: string) => {
    setPendingAttachments(prev => prev.filter(attachment => attachment.id !== id));
  }, []);

  const handleSend = useCallback(async () => {
    const text = input.trim();
    const readyAttachments = pendingAttachments.filter(attachment => !attachment.uploading);
    const hasAttachments = readyAttachments.length > 0;
    if ((!text && !hasAttachments) || status !== 'idle' || !selectedAgentId) return;

    setInput('');
    setIsInputExpanded(false);
    setPendingAttachments([]);

    await onSend(text, readyAttachments.map(attachment => ({
      url: attachment.url,
      type: attachment.contentType === 'application/pdf' ? 'PDF' : 'IMAGE',
    })));
  }, [input, onSend, pendingAttachments, selectedAgentId, status]);

  const handleKeyDown = useCallback((event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void handleSend();
    }
  }, [handleSend]);

  const datasetChips = loadedDatasetId || preDatasetId ? 1 : 0;
  const totalChips = loadedToolIds.size
    + loadedSkillIds.size
    + loadedSubAgentIds.size
    + preToolIds.size
    + preSkillIds.size
    + preSubAgentIds.size
    + datasetChips;
  const hasConfig = totalChips > 0;

  return (
    <div className="border-t p-4" style={{ borderColor: 'var(--color-border)' }}>
      <div className="max-w-4xl mx-auto">
        <ComposerConfigChips
          totalChips={totalChips}
          datasetChips={datasetChips}
          loadedToolIds={loadedToolIds}
          loadedSkillIds={loadedSkillIds}
          loadedSubAgentIds={loadedSubAgentIds}
          preToolIds={preToolIds}
          preSkillIds={preSkillIds}
          preSubAgentIds={preSubAgentIds}
          loadedDatasetId={loadedDatasetId}
          preDatasetId={preDatasetId}
          getSkillChipName={getSkillChipName}
          getAgentChipName={getAgentChipName}
          getDatasetChipName={getDatasetChipName}
        />

        {pendingAttachments.length > 0 && (
          <div className="flex gap-2 flex-wrap mb-2">
            {pendingAttachments.map(attachment => (
              <span key={attachment.id}
                className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium"
                style={{
                  background: attachment.uploading ? 'var(--color-bg-tertiary)' : 'var(--color-primary)' + '12',
                  border: attachment.uploading ? '1px dashed var(--color-border)' : '1px solid var(--color-primary)' + '20',
                  color: attachment.uploading ? 'var(--color-text-muted)' : 'var(--color-primary)',
                }}>
                {attachment.uploading ? (
                  <Loader2 size={12} className="animate-spin" />
                ) : (
                  <Paperclip size={12} />
                )}
                <span className="max-w-[120px] truncate">{attachment.name}</span>
                {!attachment.uploading && (
                  <button
                    onClick={() => removeAttachment(attachment.id)}
                    className="ml-0.5 hover:opacity-70"
                    style={{ color: 'var(--color-text-muted)' }}>
                    <X size={12} />
                  </button>
                )}
              </span>
            ))}
          </div>
        )}

        <div className="flex gap-2 items-end">
          <button
            onClick={onOpenConfig}
            disabled={!selectedAgentId || status === 'running'}
            className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0 relative"
            style={{
              background: hasConfig ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
              border: '1px solid var(--color-border)',
              color: hasConfig ? 'var(--color-primary)' : 'var(--color-text-secondary)',
            }}
            title="Configure tools, skills, subagents, and dataset">
            <Settings size={18} />
            {totalChips > 0 && (
              <span className="absolute -top-1 -right-1 text-[9px] px-1 py-0.5 rounded-full font-medium"
                style={{ background: 'var(--color-primary)', color: 'white', minWidth: '16px', textAlign: 'center' }}>
                {totalChips}
              </span>
            )}
          </button>

          <button
            onClick={handleFileSelect}
            disabled={!selectedAgentId || status === 'running'}
            className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
            style={{
              background: pendingAttachments.length > 0 ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
              border: '1px solid var(--color-border)',
              color: pendingAttachments.length > 0 ? 'var(--color-primary)' : 'var(--color-text-secondary)',
            }}
            title="Upload image or PDF">
            <Paperclip size={18} />
          </button>

          <span className="relative flex-1 self-stretch flex items-end">
            <textarea
              ref={inputRef}
              value={input}
              onChange={event => setInput(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={selectedAgentId ? 'Send a message...' : 'Select an agent first'}
              rows={isInputExpanded ? undefined : 1}
              className={`rounded-xl border px-4 py-3 text-sm resize-none focus:outline-none w-full ${isInputExpanded ? 'absolute bottom-0 left-0 right-0 z-10' : ''}`}
              style={{
                background: 'var(--color-bg-secondary)',
                borderColor: 'var(--color-border)',
                color: 'var(--color-text)',
                ...(isInputExpanded ? { minHeight: '80px' } : {}),
              }}
              disabled={status !== 'idle' || !selectedAgentId}
            />
          </span>

          {needsExpand && (
            <button
              onClick={() => setIsInputExpanded(value => !value)}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: 'var(--color-bg-tertiary)',
                border: '1px solid var(--color-border)',
                color: 'var(--color-text-secondary)',
              }}
              title={isInputExpanded ? 'Collapse input' : 'Expand input'}>
              {isInputExpanded ? <Minimize2 size={18} /> : <Maximize2 size={18} />}
            </button>
          )}

          <button
            onClick={onToggleVoiceSidebar}
            disabled={!selectedAgentId || status === 'running'}
            className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
            style={{
              background: showVoiceSidebar ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
              border: '1px solid ' + (showVoiceSidebar ? 'var(--color-primary)' : 'var(--color-border)'),
              color: showVoiceSidebar ? 'var(--color-primary)' : 'var(--color-text-secondary)',
            }}
            title={showVoiceSidebar ? 'Close voice sidebar' : 'Open voice input'}>
            <Mic size={18} />
          </button>

          {status === 'idle' ? (
            <button
              onClick={() => void handleSend()}
              disabled={(!input.trim() && pendingAttachments.every(attachment => attachment.uploading)) || !selectedAgentId}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-40"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              <Send size={18} />
            </button>
          ) : (
            <button
              onClick={onCancel}
              className="p-3 rounded-xl cursor-pointer transition-colors"
              style={{ background: 'var(--color-error)', color: 'white' }}>
              <Square size={18} />
            </button>
          )}
        </div>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/gif,image/webp,image/svg+xml,application/pdf"
        multiple
        onChange={handleFileChange}
        className="hidden"
      />
    </div>
  );
}));

export default ChatComposer;
