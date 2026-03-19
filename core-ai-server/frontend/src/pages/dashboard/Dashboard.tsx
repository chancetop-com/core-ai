import { useEffect, useState } from 'react';
import { Activity, Zap, Clock, AlertCircle } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
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
  const totalInputTokens = traces.reduce((sum, t) => sum + (t.input_tokens || 0), 0);
  const totalOutputTokens = traces.reduce((sum, t) => sum + (t.output_tokens || 0), 0);
  const totalTokens = totalInputTokens + totalOutputTokens;
  const avgDuration = totalTraces > 0 ? traces.reduce((sum, t) => sum + (t.duration_ms || 0), 0) / totalTraces : 0;
  const errorRate = totalTraces > 0 ? traces.filter(t => t.status === 'ERROR').length / totalTraces * 100 : 0;

  const statusData = ['COMPLETED', 'RUNNING', 'ERROR'].map(status => ({
    name: status,
    value: traces.filter(t => t.status === status).length,
  })).filter(d => d.value > 0);

  const tokensByDay = Object.entries(
    traces.reduce((acc, t) => {
      const day = (t.started_at || t.created_at) ? new Date(t.started_at || t.created_at).toLocaleDateString() : 'Unknown';
      if (!acc[day]) acc[day] = { input: 0, output: 0 };
      acc[day].input += (t.input_tokens || 0);
      acc[day].output += (t.output_tokens || 0);
      return acc;
    }, {} as Record<string, { input: number; output: number }>)
  ).map(([date, tokens]) => ({ date, input: tokens.input, output: tokens.output })).slice(-7);

  const statCards = [
    { label: 'Total Traces', value: totalTraces.toLocaleString(), icon: Activity, color: '#6366f1' },
    { label: 'Total Tokens', value: `${totalTokens.toLocaleString()} (${totalInputTokens.toLocaleString()} in / ${totalOutputTokens.toLocaleString()} out)`, icon: Zap, color: '#f59e0b' },
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
                <Legend />
                <Bar dataKey="input" fill="#6366f1" radius={[0, 0, 0, 0]} name="Input Tokens" stackId="tokens" />
                <Bar dataKey="output" fill="#f59e0b" radius={[4, 4, 0, 0]} name="Output Tokens" stackId="tokens" />
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
