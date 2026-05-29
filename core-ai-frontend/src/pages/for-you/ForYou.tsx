import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import {
  Circle,
  FileText,
  Files,
  Globe,
  Image as ImageIcon,
  MessageCircle,
  Plus,
  RefreshCw,
  Trash2,
  Zap,
  Calendar,
} from 'lucide-react';
import { api } from '../../api/client';
import type { ForYouDashboard, ForYouFile, ForYouReport, ForYouTodo, ForYouTokenUsage } from '../../api/client';
import { useAuth } from '../../api/auth';
import ArtifactDrawer from '../chat/components/ArtifactDrawer';
import type { ArtifactSpec } from '../chat/components/artifactTypes';

type RangeMode = 'yesterday' | '7d' | '30d' | 'custom';

export default function ForYou() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [dashboard, setDashboard] = useState<ForYouDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeArtifact, setActiveArtifact] = useState<ArtifactSpec | null>(null);
  const [tokenUsage, setTokenUsage] = useState<ForYouTokenUsage | null>(null);
  const [tokenRange, setTokenRange] = useState<RangeMode>('7d');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');

  const [newTodoTitle, setNewTodoTitle] = useState('');
  const [addingTodo, setAddingTodo] = useState(false);

  const loadDashboard = () => {
    setLoading(true);
    setError(null);
    api.forYou.dashboard()
      .then(setDashboard)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { loadDashboard(); }, []);

  const loadTokenUsage = () => {
    if (tokenRange === 'custom' && customFrom && customTo) {
      api.forYou.tokenUsage(undefined, customFrom, customTo).then(setTokenUsage).catch(() => {});
    } else {
      const range = tokenRange === 'custom' ? '7d' : tokenRange;
      api.forYou.tokenUsage(range).then(setTokenUsage).catch(() => {});
    }
  };

  useEffect(() => { loadTokenUsage(); }, [tokenRange, customFrom, customTo]);

  const addTodo = async () => {
    if (!newTodoTitle.trim()) return;
    setAddingTodo(true);
    try {
      await api.forYou.createTodo({ title: newTodoTitle.trim(), priority: 'MEDIUM' });
      setNewTodoTitle('');
      loadDashboard();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add todo');
    } finally { setAddingTodo(false); }
  };

  const toggleTodo = async (todo: ForYouTodo) => {
    try {
      await api.forYou.updateTodo(todo.id, { completed: !todo.completed });
      loadDashboard();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update');
    }
  };

  const deleteTodo = async (id: string) => {
    try { await api.forYou.deleteTodo(id); loadDashboard(); }
    catch (err) { setError(err instanceof Error ? err.message : 'Failed to delete'); }
  };

  const formatTime = (iso: string | null | undefined) => {
    if (!iso) return '';
    const d = new Date(iso);
    const now = Date.now();
    const diffMins = Math.floor((now - d.getTime()) / 60000);
    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    const diffDays = Math.floor(diffHours / 24);
    if (diffDays < 7) return `${diffDays}d ago`;
    return d.toLocaleDateString();
  };

  const priorityBadge = (p: string | null | undefined) => {
    const colors: Record<string, { bg: string; color: string }> = {
      HIGH: { bg: '#fee2e2', color: '#dc2626' },
      MEDIUM: { bg: '#fef3c7', color: '#d97706' },
      LOW: { bg: '#dcfce7', color: '#16a34a' },
    };
    const c = colors[p || ''] || { bg: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' };
    return { background: c.bg, color: c.color };
  };

  const sessions = dashboard?.recent_sessions ?? [];
  const reports = dashboard?.recent_reports ?? [];
  const files = dashboard?.recent_files ?? [];
  const todos = dashboard?.active_todos ?? [];

  const fileSpecs: ArtifactSpec[] = files.map(f => ({
    kind: 'file',
    title: f.file_name,
    fileId: f.id,
    fileName: f.file_name,
    contentType: f.content_type,
    size: f.size,
  }));

  const reportSpecs: ArtifactSpec[] = reports.map(r => ({
    kind: 'markdown',
    title: r.title,
    content: r.content || '',
    language: 'markdown',
  }));

  const artifactSpecs = [...fileSpecs, ...reportSpecs].sort((a, b) => {
    const ta = a.kind === 'file' ? (files.find(x => x.id === a.fileId)?.created_at ?? '') : (reports.find(x => x.title === a.title)?.updated_at ?? reports.find(x => x.title === a.title)?.created_at ?? '');
    const tb = b.kind === 'file' ? (files.find(x => x.id === b.fileId)?.created_at ?? '') : (reports.find(x => x.title === b.title)?.updated_at ?? reports.find(x => x.title === b.title)?.created_at ?? '');
    return new Date(tb).getTime() - new Date(ta).getTime();
  });

  const visibleArtifacts = artifactSpecs.slice(0, 6);

  return (
    <div className="flex h-full">
      {/* Main content area */}
      <div className="flex-1 min-w-0 overflow-auto">
        <div className="p-6 max-w-5xl mx-auto">
          {/* Header */}
      <div className="mb-8">
        <h1 className="text-2xl font-semibold">
          For You{user ? `, ${user.name || user.userId}` : ''}
        </h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Your work, files, and tasks — all in one place
        </p>
      </div>

      {/* ── Token Usage (always on top) ── */}
      {!loading && (
        <TokenUsageSection
          tokenUsage={tokenUsage}
          tokenRange={tokenRange}
          onRangeChange={setTokenRange}
          customFrom={customFrom}
          customTo={customTo}
          onCustomFromChange={setCustomFrom}
          onCustomToChange={setCustomTo}
        />
      )}

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center justify-between gap-3"
          style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }}>
          <span>{error}</span>
          <button onClick={loadDashboard}
            className="inline-flex items-center gap-1.5 px-2 py-1 rounded-md border cursor-pointer"
            style={{ borderColor: '#fecaca', background: '#fff' }}>
            <RefreshCw size={14} /> Retry
          </button>
        </div>
      )}

      {loading && (
        <div className="grid gap-3 mb-6 grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="rounded-lg border p-3 animate-pulse"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <div className="flex items-start gap-2.5">
                <div className="w-7 h-7 rounded shrink-0" style={{ background: 'var(--color-bg-tertiary)' }} />
                <div className="flex-1 min-w-0">
                  <div className="h-3 w-24 rounded mb-2" style={{ background: 'var(--color-bg-tertiary)' }} />
                  <div className="h-2.5 w-16 rounded mb-2" style={{ background: 'var(--color-bg-tertiary)' }} />
                  <div className="h-2.5 w-12 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {dashboard && !loading && (
        <>
          {/* ── Section 1: Recent (card grid) ── */}
          {visibleArtifacts.length > 0 && (
            <section className="mb-6">
              <h2 className="text-base font-semibold mb-3">Recent</h2>
              <div className="grid grid-cols-3 gap-3">
                {visibleArtifacts.map(spec => (
                  <ArtifactGridCard
                    key={spec.kind === 'file' ? `file-${spec.fileId}` : `report-${spec.title}`}
                    spec={spec}
                    file={spec.kind === 'file' ? (files.find(f => f.id === spec.fileId) ?? null) : null}
                    report={spec.kind === 'markdown' ? (reports.find(r => r.title === spec.title) ?? null) : null}
                    onClick={() => setActiveArtifact(spec)}
                  />
                ))}
              </div>
            </section>
          )}

          {/* ── Section 2: Recent Chats ── */}
          {sessions.length > 0 && (
            <section className="mb-6">
              <h2 className="text-base font-semibold mb-3">Continue where you left off</h2>
              <div className="rounded-lg border overflow-hidden"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                {sessions.map(s => (
                  <div key={s.id}
                    onClick={() => navigate(`/chat?sessionId=${encodeURIComponent(s.id)}`)}
                    className="px-4 py-3 border-b last:border-b-0 flex items-center gap-3 cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <MessageCircle size={16} style={{ color: 'var(--color-text-secondary)' }} />
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-medium truncate">{s.title || 'Untitled chat'}</div>
                      <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                        {s.message_count ?? 0} messages · {formatTime(s.last_message_at || s.created_at)}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* ── Section 3: Tasks ── */}
          {todos.length > 0 && (
            <section className="mb-6">
              <h2 className="text-base font-semibold mb-3">
                Tasks
                <span className="ml-2 text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>
                  {dashboard.active_todo_count} active · {dashboard.todo_count - dashboard.active_todo_count} done
                </span>
              </h2>
              <div className="rounded-lg border overflow-hidden"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <div className="px-4 py-3 border-b flex items-center gap-2"
                  style={{ borderColor: 'var(--color-border)' }}>
                  <input
                    type="text" placeholder="Add a new task..."
                    value={newTodoTitle} onChange={e => setNewTodoTitle(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addTodo()}
                    className="flex-1 px-3 py-2 rounded-lg border text-sm"
                    style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
                  />
                  <button onClick={addTodo} disabled={addingTodo || !newTodoTitle.trim()}
                    className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer shrink-0"
                    style={{
                      background: newTodoTitle.trim() ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                      color: newTodoTitle.trim() ? '#fff' : 'var(--color-text-secondary)',
                    }}>
                    <Plus size={16} />
                  </button>
                </div>
                <div>
                  {todos.map(todo => (
                    <div key={todo.id}
                      className="px-4 py-2.5 border-b last:border-b-0 flex items-center gap-3 group"
                      style={{ borderColor: 'var(--color-border)' }}>
                      <button onClick={() => toggleTodo(todo)}
                        className="shrink-0 cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                        <Circle size={18} />
                      </button>
                      <div className="min-w-0 flex-1">
                        <div className="text-sm truncate">{todo.title}</div>
                        <div className="flex items-center gap-2 mt-0.5">
                          {todo.priority && (
                            <span className="px-1.5 py-0.5 rounded text-xs font-medium" style={priorityBadge(todo.priority)}>{todo.priority}</span>
                          )}
                          {todo.due_date && (
                            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Due {formatTime(todo.due_date)}</span>
                          )}
                          {todo.description && (
                            <span className="text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>{todo.description}</span>
                          )}
                        </div>
                      </div>
                      <button onClick={() => deleteTodo(todo.id)}
                        className="w-6 h-6 rounded flex items-center justify-center cursor-pointer shrink-0 opacity-0 group-hover:opacity-100"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        <Trash2 size={13} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            </section>
          )}
        </>
      )}
        </div>
      </div>
      {activeArtifact && (
        <ArtifactDrawer artifact={activeArtifact} onClose={() => setActiveArtifact(null)} />
      )}
    </div>
  );
}

/* ── Artifact grid card ── */

function ArtifactGridCard({ spec, file, report, onClick }: {
  spec: ArtifactSpec;
  file: ForYouFile | null;
  report: ForYouReport | null;
  onClick: () => void;
}) {
  const isFile = spec.kind === 'file';

  const icon = (() => {
    const color = 'var(--color-text-secondary)';
    if (isFile) {
      const ct = file?.content_type ?? '';
      if (/html/i.test(ct)) return <Globe size={15} style={{ color: '#16a34a' }} />;
      if (/svg|image/i.test(ct)) return <ImageIcon size={15} style={{ color: '#16a34a' }} />;
      return <Files size={15} style={{ color }} />;
    }
    return <FileText size={15} style={{ color: '#6366f1' }} />;
  })();

  const typeLabel = (() => {
    if (isFile) {
      const ct = (file?.content_type ?? '').split('/').pop() ?? 'file';
      return ct;
    }
    return report?.type ?? 'report';
  })();

  const time = isFile ? file?.created_at : (report?.updated_at || report?.created_at);
  const displayTime = formatTimeStatic(time);

  return (
    <button onClick={onClick}
      className="rounded-lg border p-3 text-left cursor-pointer transition-colors hover:bg-[var(--color-bg-secondary)] min-w-0"
      style={{ borderColor: 'var(--color-border)' }}>
      <div className="flex items-start gap-2.5">
        <div className="w-7 h-7 rounded flex items-center justify-center shrink-0 mt-0.5"
          style={{ background: 'var(--color-bg-tertiary)' }}>
          {icon}
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium truncate" style={{ color: 'var(--color-text)' }}>
            {spec.title}
          </div>
          <div className="flex items-center gap-2 mt-1.5">
            <span className="text-[11px] uppercase tracking-wide font-semibold px-1.5 py-0.5 rounded shrink-0"
              style={{
                background: isFile ? '#16a34a' + '18' : '#6366f1' + '18',
                color: isFile ? '#16a34a' : '#6366f1',
              }}>
              {typeLabel}
            </span>
            <span className="text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>
              {displayTime}
            </span>
          </div>
        </div>
      </div>
    </button>
  );
}

function formatTimeStatic(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  const now = Date.now();
  const diffMins = Math.floor((now - d.getTime()) / 60000);
  if (diffMins < 1) return 'just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 7) return `${diffDays}d ago`;
  return d.toLocaleDateString();
}

/* ── Token Usage Section ── */

const RANGES: { key: RangeMode; label: string }[] = [
  { key: 'yesterday', label: 'Yest' },
  { key: '7d', label: '7d' },
  { key: '30d', label: '1m' },
  { key: 'custom', label: 'Custom' },
];

function formatLargeNum(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function formatCost(usd: number): string {
  if (usd < 0.01) return '< $0.01';
  return `$${usd.toFixed(2)}`;
}

function todayStr(): string {
  return new Date().toISOString().slice(0, 10);
}

function TokenUsageSection({ tokenUsage, tokenRange, onRangeChange,
  customFrom, customTo, onCustomFromChange, onCustomToChange,
}: {
  tokenUsage: ForYouTokenUsage | null;
  tokenRange: RangeMode;
  onRangeChange: (r: RangeMode) => void;
  customFrom: string;
  customTo: string;
  onCustomFromChange: (v: string) => void;
  onCustomToChange: (v: string) => void;
}) {
  if (!tokenUsage || tokenUsage.total_tokens === 0) {
    return (
      <section className="mb-6">
        <div className="flex items-center justify-between mb-3 gap-3">
          <h2 className="text-base font-semibold flex items-center gap-2">
            <Zap size={16} style={{ color: '#f59e0b' }} />
            Token & Cost
          </h2>
          <RangeSwitcher value={tokenRange} onChange={onRangeChange}
            customFrom={customFrom} customTo={customTo}
            onCustomFromChange={onCustomFromChange} onCustomToChange={onCustomToChange} />
        </div>
        <div className="rounded-lg border p-6 text-center text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          No token usage data for this period.
        </div>
      </section>
    );
  }

  // Build chart data: two lines — total tokens + cost
  const chartData = tokenUsage.daily.map(d => ({
    date: tokenRange === 'yesterday' ? d.date : d.date.slice(5),
    tokens: d.input_tokens + d.output_tokens,
    cost: Math.round(d.cost_usd * 10000) / 10000,
    // detail for tooltip
    input: d.input_tokens,
    output: d.output_tokens,
  }));

  return (
    <section className="mb-6">
      {/* Header row */}
      <div className="flex items-center justify-between mb-3 gap-3">
        <h2 className="text-base font-semibold flex items-center gap-2">
          <Zap size={16} style={{ color: '#f59e0b' }} />
          Token & Cost
        </h2>
        <RangeSwitcher value={tokenRange} onChange={onRangeChange}
          customFrom={customFrom} customTo={customTo}
          onCustomFromChange={onCustomFromChange} onCustomToChange={onCustomToChange} />
      </div>

      {/* Stat cards row */}
      <div className="grid grid-cols-4 gap-3 mb-4">
        <StatCard label="Tokens" value={formatLargeNum(tokenUsage.total_tokens)} color="#6366f1" />
        <StatCard label="Input" value={formatLargeNum(tokenUsage.total_input_tokens)} color="#3b82f6" />
        <StatCard label="Output" value={formatLargeNum(tokenUsage.total_output_tokens)} color="#8b5cf6" />
        <StatCard label="Cost" value={formatCost(tokenUsage.total_cost_usd)} color="#f59e0b" />
      </div>

      {/* Chart */}
      {chartData.length > 0 && (
        <div className="rounded-lg border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: 'var(--color-text-secondary)' }} />
              <YAxis
                yAxisId="tokens"
                orientation="left"
                tick={{ fontSize: 11, fill: '#6366f1' }}
                tickFormatter={formatLargeNum}
                domain={[0, 'auto']}
                width={50}
              />
              <YAxis
                yAxisId="cost"
                orientation="right"
                tick={{ fontSize: 11, fill: '#f59e0b' }}
                tickFormatter={(v: number) => `$${v.toFixed(2)}`}
                domain={[0, 'auto']}
                width={50}
              />
              <Tooltip
                contentStyle={{
                  background: 'var(--color-bg-secondary)',
                  border: '1px solid var(--color-border)',
                  borderRadius: 8,
                  fontSize: 12,
                }}
                formatter={(_value: any, name: any) => {
                  const v = Number(_value);
                  if (name === 'tokens') return [formatLargeNum(v), 'Tokens'];
                  if (name === 'cost') return [`$${v.toFixed(4)}`, 'Cost'];
                  return [String(v), name];
                }}
              />
              <Legend />
              <Line
                yAxisId="tokens"
                type="monotone"
                dataKey="tokens"
                name="Tokens"
                stroke="#6366f1"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, fill: '#6366f1' }}
              />
              <Line
                yAxisId="cost"
                type="monotone"
                dataKey="cost"
                name="Cost"
                stroke="#f59e0b"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, fill: '#f59e0b' }}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}

function StatCard({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="rounded-lg border px-4 py-2.5" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="text-xs mb-0.5" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-lg font-bold" style={{ color }}>{value}</div>
    </div>
  );
}

function RangeSwitcher({ value, onChange, customFrom, customTo, onCustomFromChange, onCustomToChange }: {
  value: RangeMode;
  onChange: (r: RangeMode) => void;
  customFrom: string;
  customTo: string;
  onCustomFromChange: (v: string) => void;
  onCustomToChange: (v: string) => void;
}) {
  return (
    <div className="flex items-center gap-2">
      <div className="flex rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        {RANGES.map(r => (
          <button key={r.key}
            onClick={() => onChange(r.key)}
            className="px-3 py-1.5 text-xs font-medium cursor-pointer transition-colors"
            style={{
              background: value === r.key ? 'var(--color-primary)' : 'transparent',
              color: value === r.key ? '#fff' : 'var(--color-text-secondary)',
            }}>
            {r.label}
          </button>
        ))}
      </div>
      {value === 'custom' && (
        <div className="flex items-center gap-1.5">
          <Calendar size={14} style={{ color: 'var(--color-text-secondary)' }} />
          <input type="date" value={customFrom} onChange={e => onCustomFromChange(e.target.value)}
            max={customTo || todayStr()}
            className="px-2 py-1.5 rounded border text-xs w-32"
            style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>to</span>
          <input type="date" value={customTo} onChange={e => onCustomToChange(e.target.value)}
            min={customFrom} max={todayStr()}
            className="px-2 py-1.5 rounded border text-xs w-32"
            style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
        </div>
      )}
    </div>
  );
}
