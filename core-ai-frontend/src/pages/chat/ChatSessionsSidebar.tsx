import { useCallback, useEffect, useState } from 'react';
import { MessageSquare, Plus, Loader2, Trash2 } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type { ChatSessionSummary } from '../../api/session';

interface Props {
  currentSessionId: string | null;
  refreshKey: number;
  onOpen: (sessionId: string) => void;
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

  const handleDelete = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    if (!confirm('Delete this conversation? (messages will remain in audit log)')) return;
    try {
      await sessionApi.deleteChatSession(id);
      setSessions(prev => prev.filter(s => s.id !== id));
      onDeleted?.(id);
    } catch (err) {
      console.warn('failed to delete chat session', err);
    }
  };

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
            <div key={s.id} onClick={() => onOpen(s.id)}
              className="group w-full text-left px-3 py-2 flex items-start gap-2 cursor-pointer border-l-2 transition-colors"
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
                <div className="text-[10px] mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                  {formatTime(s.last_message_at)}
                  {s.message_count ? ` · ${s.message_count} msg` : ''}
                </div>
              </div>
              <button onClick={e => handleDelete(e, s.id)}
                className="opacity-0 group-hover:opacity-100 mt-0.5 p-1 rounded cursor-pointer transition-opacity"
                style={{ color: 'var(--color-text-secondary)' }}
                title="Delete conversation">
                <Trash2 size={14} />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
