import { useEffect, useState } from 'react';
import { adminApi, type BackgroundTask } from '../../api/client';
import { RefreshCw, ChevronDown, ChevronUp, Plus } from 'lucide-react';

const STATUS_STYLES: Record<string, { bg: string; text: string; dot: string }> = {
  SUCCESS: { bg: 'rgba(34,197,94,0.1)', text: '#22c55e', dot: '#22c55e' },
  FAILED: { bg: 'rgba(239,68,68,0.1)', text: '#ef4444', dot: '#ef4444' },
  RUNNING: { bg: 'rgba(59,130,246,0.1)', text: '#3b82f6', dot: '#3b82f6' },
  PENDING: { bg: 'rgba(251,191,36,0.1)', text: '#fbbf24', dot: '#fbbf24' },
};

const TASK_TYPES = ['TRACE_DAILY_MAINTENANCE'];

export default function Tasks() {
  const [tasks, setTasks] = useState<BackgroundTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedTask, setExpandedTask] = useState<string | null>(null);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [triggerType, setTriggerType] = useState(TASK_TYPES[0]);
  const [triggerDate, setTriggerDate] = useState('');
  const [triggering, setTriggering] = useState(false);

  const fetchTasks = async () => {
    try {
      setError(null);
      setLoading(true);
      const res = await adminApi.listTasks(undefined, 50);
      setTasks(res.tasks);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load tasks');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchTasks(); }, []);

  const handleRetry = async (taskId: string) => {
    try {
      setRetrying(taskId);
      await adminApi.retryTask(taskId);
      await fetchTasks();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Retry failed');
    } finally {
      setRetrying(null);
    }
  };

  const handleTrigger = async () => {
    if (!triggerDate) return;
    try {
      setTriggering(true);
      setError(null);
      await adminApi.triggerTask(triggerType, triggerDate);
      setShowModal(false);
      await fetchTasks();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Trigger failed');
    } finally {
      setTriggering(false);
    }
  };

  const formatTime = (ts?: string) => {
    if (!ts) return '—';
    return new Date(ts).toLocaleString();
  };

  const statusBadge = (status: string | null) => {
    if (!status) return <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>—</span>;
    const style = STATUS_STYLES[status] ?? { bg: 'transparent', text: 'var(--color-text-secondary)', dot: 'var(--color-text-secondary)' };
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs font-medium"
        style={{ background: style.bg, color: style.text }}>
        <span className="w-1.5 h-1.5 rounded-full" style={{ background: style.dot }} />
        {status}
      </span>
    );
  };

  if (loading) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-semibold mb-6">System Tasks</h1>
        <div className="flex items-center justify-center py-20" style={{ color: 'var(--color-text-secondary)' }}>
          Loading tasks...
        </div>
      </div>
    );
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">System Tasks</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Background task execution history
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowModal(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
            style={{ background: 'var(--color-primary)', color: '#fff' }}>
            <Plus size={14} />
            New Task
          </button>
          <button
            onClick={fetchTasks}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm transition-colors"
            style={{ color: 'var(--color-text-secondary)', border: '1px solid var(--color-border)' }}>
            <RefreshCw size={14} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-lg text-sm" style={{ background: 'rgba(239,68,68,0.1)', color: '#ef4444' }}>
          {error}
        </div>
      )}

      <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr style={{ borderBottom: '1px solid var(--color-border)' }}>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Task</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Type</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Status</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Pod</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Started</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Duration</th>
              <th className="text-left px-4 py-3 font-medium" style={{ color: 'var(--color-text-secondary)' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {tasks.length === 0 ? (
              <tr>
                <td colSpan={7} className="text-center py-16" style={{ color: 'var(--color-text-secondary)' }}>
                  No tasks found
                </td>
              </tr>
            ) : (
              tasks.map((task) => (
                <>
                  <tr key={task.id}
                    className="cursor-pointer transition-colors hover:bg-opacity-50"
                    style={{ borderBottom: '1px solid var(--color-border)' }}
                    onClick={() => setExpandedTask(expandedTask === task.id ? null : task.id)}>
                    <td className="px-4 py-3 font-mono text-xs">{task.id}</td>
                    <td className="px-4 py-3">
                      <span className="px-1.5 py-0.5 rounded text-xs font-mono"
                        style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                        {task.type}
                      </span>
                    </td>
                    <td className="px-4 py-3">{statusBadge(task.status)}</td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {task.claimed_by ?? '—'}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatTime(task.started_at)}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {task.started_at && task.completed_at
                        ? `${Math.round((new Date(task.completed_at).getTime() - new Date(task.started_at).getTime()) / 1000)}s`
                        : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {task.status === 'FAILED' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); handleRetry(task.id); }}
                            disabled={retrying === task.id}
                            className="px-2 py-1 rounded text-xs font-medium transition-colors"
                            style={{ background: 'rgba(239,68,68,0.1)', color: '#ef4444' }}>
                            {retrying === task.id ? 'Retrying...' : 'Retry'}
                          </button>
                        )}
                        {expandedTask === task.id ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                      </div>
                    </td>
                  </tr>
                  {expandedTask === task.id && (
                    <tr key={`${task.id}-detail`} style={{ borderBottom: '1px solid var(--color-border)' }}>
                      <td colSpan={7} className="px-4 py-3">
                        <div className="rounded-lg p-3 text-xs font-mono" style={{ background: 'var(--color-bg-hover, rgba(0,0,0,0.03))' }}>
                          {task.status_text && (
                            <div className="mb-2">
                              <span style={{ color: 'var(--color-text-secondary)' }}>Result: </span>
                              <span>{task.status_text}</span>
                            </div>
                          )}
                          {task.logs && task.logs.length > 0 && (
                            <div>
                              <div className="mb-1" style={{ color: 'var(--color-text-secondary)' }}>Logs:</div>
                              {task.logs.map((line, i) => (
                                <div key={i} className="ml-2" style={{ color: 'var(--color-text-secondary)' }}>
                                  {line}
                                </div>
                              ))}
                            </div>
                          )}
                          {task.retry_count != null && task.retry_count > 0 && (
                            <div className="mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                              Retries: {task.retry_count}
                            </div>
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* New Task Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}
          onClick={() => setShowModal(false)}>
          <div className="rounded-xl p-6 w-full max-w-md shadow-2xl border" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
            onClick={(e) => e.stopPropagation()}>
            <h2 className="text-lg font-semibold mb-4">Trigger Background Task</h2>
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Task Type</label>
                <select
                  value={triggerType}
                  onChange={(e) => setTriggerType(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg text-sm border"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                  {TASK_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Date</label>
                <input
                  type="date"
                  value={triggerDate}
                  onChange={(e) => setTriggerDate(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg text-sm border"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
              </div>
            </div>
            <div className="flex justify-end gap-2 mt-6">
              <button
                onClick={() => setShowModal(false)}
                className="px-4 py-2 rounded-lg text-sm transition-colors"
                style={{ color: 'var(--color-text-secondary)', border: '1px solid var(--color-border)' }}>
                Cancel
              </button>
              <button
                onClick={handleTrigger}
                disabled={triggering || !triggerDate}
                className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                style={{ background: triggerDate ? 'var(--color-primary)' : 'var(--color-border)', color: triggerDate ? '#fff' : 'var(--color-text-secondary)' }}>
                {triggering ? 'Running...' : 'Run Task'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
