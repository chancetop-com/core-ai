import { useEffect, useState } from 'react';
import { Activity, Zap, Clock, AlertCircle } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { api } from '../../api/client';
import type { Trace } from '../../api/client';

const COLORS = ['#6366f1', '#22c55e', '#ef4444', '#f59e0b'];

export default function Dashboard() {
  const [traces, setTraces] = useState<Trace[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.traces.list(0, 100).then(setTraces).finally(() => setLoading(false));
  }, []);

  const totalTraces = traces.length;
  const totalTokens = traces.reduce((sum, t) => sum + (t.total_tokens || 0), 0);
  const avgDuration = totalTraces > 0 ? traces.reduce((sum, t) => sum + (t.duration_ms || 0), 0) / totalTraces : 0;
  const errorRate = totalTraces > 0 ? traces.filter(t => t.status === 'ERROR').length / totalTraces * 100 : 0;

  const statusData = ['COMPLETED', 'RUNNING', 'ERROR'].map(status => ({
    name: status,
    value: traces.filter(t => t.status === status).length,
  })).filter(d => d.value > 0);

  const tokensByDay = Object.entries(
    traces.reduce((acc, t) => {
      const day = t.created_at ? new Date(t.created_at).toLocaleDateString() : 'Unknown';
      acc[day] = (acc[day] || 0) + (t.total_tokens || 0);
      return acc;
    }, {} as Record<string, number>)
  ).map(([date, tokens]) => ({ date, tokens })).slice(-7);

  const statCards = [
    { label: 'Total Traces', value: totalTraces.toLocaleString(), icon: Activity, color: '#6366f1' },
    { label: 'Total Tokens', value: totalTokens.toLocaleString(), icon: Zap, color: '#f59e0b' },
    { label: 'Avg Duration', value: `${(avgDuration / 1000).toFixed(1)}s`, icon: Clock, color: '#06b6d4' },
    { label: 'Error Rate', value: `${errorRate.toFixed(1)}%`, icon: AlertCircle, color: '#ef4444' },
  ];

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Overview of agent performance and usage
        </p>
      </div>

      <div className="grid grid-cols-4 gap-4 mb-6">
        {statCards.map(({ label, value, icon: Icon, color }) => (
          <div key={label} className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between">
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
              <Icon size={18} style={{ color }} />
            </div>
            <div className="text-2xl font-semibold mt-2">{value}</div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="col-span-2 rounded-xl border p-4"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="font-medium text-sm mb-4">Token Usage (Last 7 Days)</h3>
          {tokensByDay.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={tokensByDay}>
                <XAxis dataKey="date" tick={{ fontSize: 12, fill: 'var(--color-text-secondary)' }} />
                <YAxis tick={{ fontSize: 12, fill: 'var(--color-text-secondary)' }} />
                <Tooltip contentStyle={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 8 }} />
                <Bar dataKey="tokens" fill="#6366f1" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex items-center justify-center h-64 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No data yet</div>
          )}
        </div>

        <div className="rounded-xl border p-4"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="font-medium text-sm mb-4">Status Distribution</h3>
          {statusData.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie data={statusData} cx="50%" cy="50%" innerRadius={60} outerRadius={90} dataKey="value" label={({ name, value }) => `${name}: ${value}`}>
                  {statusData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex items-center justify-center h-64 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No data yet</div>
          )}
        </div>
      </div>
    </div>
  );
}
