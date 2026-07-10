import { useEffect, useState, useCallback } from 'react';
import { Bell, BellOff, CheckCheck, Loader2 } from 'lucide-react';
import { api, type NotificationView, type UnreadCountResponse } from '../../api/client';

const PAGE_SIZE = 20;

type CategoryFilter = 'ALL' | 'AGENT' | 'SYSTEM';
type StatusFilter = 'ALL' | 'UNREAD' | 'READ';

export default function Notifications() {
  const [notifications, setNotifications] = useState<NotificationView[]>([]);
  const [total, setTotal] = useState(0);
  const [unreadCount, setUnreadCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>('ALL');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');

  const fetchUnreadCount = useCallback(() => {
    api.notifications.unreadCount()
      .then((res: UnreadCountResponse) => setUnreadCount(res.unread_count))
      .catch(() => { /* silent */ });
  }, []);

  const fetchList = useCallback((pageNum: number) => {
    setLoading(true);
    setError('');
    const cat = categoryFilter === 'ALL' ? undefined : categoryFilter;
    const st = statusFilter === 'ALL' ? undefined : statusFilter;
    api.notifications.list(cat, st, pageNum * PAGE_SIZE, PAGE_SIZE)
      .then(res => {
        setNotifications(res.notifications);
        setTotal(res.total);
      })
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load notifications'))
      .finally(() => setLoading(false));
  }, [categoryFilter, statusFilter]);

  useEffect(() => {
    fetchUnreadCount();
  }, [fetchUnreadCount]);

  useEffect(() => {
    setPage(0);
    fetchList(0);
  }, [fetchList]);

  const handleGoTo = (newPage: number) => {
    setPage(newPage);
    fetchList(newPage);
  };

  const handleMarkRead = async (id: string) => {
    try {
      await api.notifications.markRead(id);
      setNotifications(prev => prev.map(n =>
        n.id === id ? { ...n, status: 'READ' } : n
      ));
      fetchUnreadCount();
    } catch {
      // ignore
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await api.notifications.markAllRead();
      setNotifications(prev => prev.map(n => ({ ...n, status: 'READ' })));
      fetchUnreadCount();
    } catch {
      // ignore
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const formatTime = (iso: string) => {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffH = Math.floor(diffMin / 60);
    if (diffH < 24) return `${diffH}h ago`;
    const diffD = Math.floor(diffH / 24);
    if (diffD < 7) return `${diffD}d ago`;
    return d.toLocaleDateString();
  };

  const categoryLabel = (c: string) => c === 'AGENT' ? 'Agent' : c === 'SYSTEM' ? 'System' : c;

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold">Notifications</h2>
          {unreadCount > 0 && (
            <span className="inline-flex items-center justify-center min-w-[20px] h-5 px-1.5 text-xs font-medium rounded-full"
              style={{ background: 'var(--color-primary)', color: '#fff' }}>
              {unreadCount}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <select
            value={categoryFilter}
            onChange={e => setCategoryFilter(e.target.value as CategoryFilter)}
            className="px-2 py-1 text-sm rounded border"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}>
            <option value="ALL">All Categories</option>
            <option value="AGENT">Agent</option>
            <option value="SYSTEM">System</option>
          </select>
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value as StatusFilter)}
            className="px-2 py-1 text-sm rounded border"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}>
            <option value="ALL">All Status</option>
            <option value="UNREAD">Unread</option>
            <option value="READ">Read</option>
          </select>
          <button
            onClick={handleMarkAllRead}
            disabled={unreadCount === 0}
            className="flex items-center gap-1 px-3 py-1 text-sm rounded border transition-colors disabled:opacity-40"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}
            title="Mark all as read">
            <CheckCheck size={14} />
            Mark all read
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="flex items-center justify-center h-40">
            <Loader2 size={24} className="animate-spin" style={{ color: 'var(--color-text-secondary)' }} />
          </div>
        ) : error ? (
          <div className="flex flex-col items-center justify-center h-40 gap-3">
            <BellOff size={40} style={{ color: 'var(--color-text-secondary)' }} />
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{error}</p>
          </div>
        ) : notifications.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-40 gap-3">
            <Bell size={40} style={{ color: 'var(--color-text-secondary)' }} />
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No notifications</p>
          </div>
        ) : (
          <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
            {notifications.map(n => (
              <div
                key={n.id}
                className={`px-6 py-3 transition-colors ${n.status === 'UNREAD' ? '' : 'opacity-70'}`}
                style={n.status === 'UNREAD' ? { background: 'var(--color-bg-secondary)' } : {}}>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-0.5">
                      <span className="text-xs font-medium uppercase px-1.5 py-0.5 rounded"
                        style={
                          n.type === 'PAUSE' ? { background: 'rgba(245,158,11,0.15)', color: '#d97706' }
                            : n.type === 'TERMINATE' ? { background: 'rgba(239,68,68,0.15)', color: '#dc2626' }
                              : { background: 'rgba(59,130,246,0.15)', color: '#2563eb' }
                        }>
                        {n.type}
                      </span>
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        {categoryLabel(n.category)}
                      </span>
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        {formatTime(n.created_at)}
                      </span>
                    </div>
                    <p className="text-sm font-medium truncate">{n.title}</p>
                    {n.message && (
                      <p className="text-xs mt-0.5 line-clamp-2" style={{ color: 'var(--color-text-secondary)' }}>
                        {n.message}
                      </p>
                    )}
                    {n.agent_id && (
                      <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                        Agent: {n.agent_id}
                      </p>
                    )}
                  </div>
                  {n.status === 'UNREAD' && (
                    <button
                      onClick={() => handleMarkRead(n.id)}
                      className="shrink-0 px-2 py-1 text-xs rounded border transition-colors hover:opacity-80"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      Mark read
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 px-6 py-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <button
            disabled={page === 0}
            onClick={() => handleGoTo(page - 1)}
            className="px-3 py-1 text-sm rounded border disabled:opacity-40 transition-colors"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            Prev
          </button>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {page + 1} / {totalPages}
          </span>
          <button
            disabled={page >= totalPages - 1}
            onClick={() => handleGoTo(page + 1)}
            className="px-3 py-1 text-sm rounded border disabled:opacity-40 transition-colors"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            Next
          </button>
        </div>
      )}
    </div>
  );
}
