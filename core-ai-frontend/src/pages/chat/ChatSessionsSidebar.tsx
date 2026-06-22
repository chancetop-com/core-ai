import { useCallback, useEffect, useRef, useState } from 'react';
import { MessageSquare, Plus, Loader2, MoreHorizontal, Pencil, Trash2 } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type { ChatSessionSummary } from '../../api/session';
import { formatMessageTimeFull } from './utils';

interface Props {
  currentSessionId: string | null;
  refreshKey: number;
  onOpen: (session: ChatSessionSummary) => void;
  onNewChat: () => void;
  onDeleted?: (sessionId: string) => void;
}

function formatTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const diffDays = Math.floor((now.getTime() - d.getTime()) / 86400000);
  if (diffDays < 7) return `${diffDays}d ago`;
  return d.toLocaleDateString();
}

export default function ChatSessionsSidebar({ currentSessionId, refreshKey, onOpen, onNewChat, onDeleted }: Props) {
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [renameId, setRenameId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [renameError, setRenameError] = useState('');
  const menuRef = useRef<HTMLDivElement | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await sessionApi.listChatSessions(0, 50);
      setSessions(res.sessions || []);
    } catch (e) {
      console.warn('failed to load chat sessions', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load, refreshKey]);

  // close the hover menu when clicking outside of it
  useEffect(() => {
    if (!menuId) return;
    const onDocClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuId(null);
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [menuId]);

  const handleDelete = async (id: string) => {
    setMenuId(null);
    if (!confirm('Delete this conversation? (messages will remain in audit log)')) return;
    try {
      await sessionApi.deleteChatSession(id);
      setSessions(prev => prev.filter(s => s.id !== id));
      onDeleted?.(id);
    } catch (err) {
      console.warn('failed to delete chat session', err);
    }
  };

  const openRename = (s: ChatSessionSummary) => {
    setMenuId(null);
    setRenameId(s.id);
    setRenameValue(s.title || '');
    setRenameError('');
  };

  const closeRename = () => {
    if (saving) return;
    setRenameId(null);
    setRenameValue('');
    setRenameError('');
  };

  const submitRename = async () => {
    const id = renameId;
    const title = renameValue.trim().replace(/\s+/g, ' ');
    if (!id || !title) return;
    setSaving(true);
    setRenameError('');
    try {
      await sessionApi.renameChatSession(id, title);
      setSessions(prev => prev.map(s => (s.id === id ? { ...s, title } : s)));
      setRenameId(null);
      setRenameValue('');
    } catch (err) {
      console.warn('failed to rename chat session', err);
      setRenameError('Failed to rename. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col border-r h-full w-60 shrink-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="px-3 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <button onClick={onNewChat}
          className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm cursor-pointer"
          style={{ background: 'var(--color-primary)', color: 'white' }}>
          <Plus size={14} /> New Chat
        </button>
      </div>
      <div className="flex-1 overflow-auto">
        {loading && sessions.length === 0 && (
          <div className="flex items-center justify-center py-6" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 size={16} className="animate-spin" />
          </div>
        )}
        {!loading && sessions.length === 0 && (
          <div className="px-4 py-6 text-xs text-center" style={{ color: 'var(--color-text-secondary)' }}>
            No conversations yet
          </div>
        )}
        {sessions.map(s => {
          const active = s.id === currentSessionId;
          return (
            <div key={s.id} onClick={() => onOpen(s)}
              className="group relative w-full text-left px-3 py-2 flex items-start gap-2 cursor-pointer border-l-2 transition-colors"
              style={{
                borderColor: active ? 'var(--color-primary)' : 'transparent',
                background: active ? 'var(--color-bg-tertiary)' : 'transparent',
                color: 'var(--color-text)',
              }}>
              <MessageSquare size={14} className="mt-0.5 shrink-0" style={{ color: 'var(--color-text-secondary)' }} />
              <div className="flex-1 min-w-0">
                <div className="text-sm truncate" title={s.title || s.id}>
                  {s.title || '(untitled)'}
                </div>
                <div className="text-[10px] mt-0.5" style={{ color: 'var(--color-text-secondary)' }}
                  title={formatMessageTimeFull(s.last_message_at || s.created_at)}>
                  {formatTime(s.last_message_at || s.created_at)}
                  {s.message_count ? ` · ${s.message_count} msg` : ''}
                </div>
              </div>
              <button onClick={e => { e.stopPropagation(); setMenuId(menuId === s.id ? null : s.id); }}
                className="opacity-0 group-hover:opacity-100 mt-0.5 p-1 rounded cursor-pointer transition-opacity"
                style={{ color: 'var(--color-text-secondary)' }}
                title="More actions">
                <MoreHorizontal size={14} />
              </button>
              {menuId === s.id && (
                <div ref={menuRef} onClick={e => e.stopPropagation()}
                  className="absolute right-2 top-8 z-10 py-1 rounded-md border shadow-md text-sm"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
                  <button onClick={() => openRename(s)}
                    className="w-full flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:opacity-80"
                    style={{ color: 'var(--color-text)' }}>
                    <Pencil size={13} /> Rename
                  </button>
                  <button onClick={() => handleDelete(s.id)}
                    className="w-full flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:opacity-80"
                    style={{ color: 'var(--color-danger, #e5484d)' }}>
                    <Trash2 size={13} /> Delete
                  </button>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {renameId && (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ background: 'rgba(0,0,0,0.4)' }}
          onClick={closeRename}>
          <div onClick={e => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="Rename conversation"
            className="w-80 p-4 rounded-lg border shadow-lg"
            style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
            <div className="text-sm font-medium mb-2" style={{ color: 'var(--color-text)' }}>Rename conversation</div>
            <input autoFocus value={renameValue} maxLength={100}
              onChange={e => setRenameValue(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter') submitRename();
                if (e.key === 'Escape') closeRename();
              }}
              className="w-full px-2 py-1.5 rounded border text-sm outline-none"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            {renameError && (
              <div className="text-xs mt-1" style={{ color: 'var(--color-danger, #e5484d)' }}>{renameError}</div>
            )}
            <div className="flex justify-end gap-2 mt-3">
              <button onClick={closeRename} disabled={saving}
                className="px-3 py-1.5 rounded text-sm cursor-pointer"
                style={{ color: 'var(--color-text-secondary)' }}>
                Cancel
              </button>
              <button onClick={submitRename} disabled={saving || !renameValue.trim()}
                className="px-3 py-1.5 rounded text-sm cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)', color: 'white' }}>
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
