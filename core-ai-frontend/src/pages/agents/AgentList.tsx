import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Bot } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function AgentList() {
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.agents.list().then(res => setAgents(res.agents || [])).finally(() => setLoading(false));
  }, []);

  const handleCreate = async () => {
    const created = await api.agents.create({
      name: 'New Agent',
      type: 'AGENT',
    });
    if (created?.id) navigate(`/agents/${created.id}`);
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const now = Date.now();
    const diff = now - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Agents</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create and manage your AI agents
          </p>
        </div>
        <button onClick={handleCreate}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={16} /> New Agent
        </button>
      </div>

      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : agents.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No agents yet. Click "New Agent" to create one.
          </div>
        ) : agents.filter(a => !a.system_default).map(a => (
          <div key={a.id} onClick={() => navigate(`/agents/${a.id}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'var(--color-bg-secondary)')}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Bot size={18} style={{ color: 'var(--color-primary)' }} />
                <span className="font-medium">{a.name}</span>
                <StatusBadge status={a.status} />
                <span className="px-2 py-0.5 rounded text-xs"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {a.type}
                </span>
              </div>
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {formatTime(a.updated_at)}
              </span>
            </div>
            {a.description && (
              <p className="text-sm mt-1 ml-8" style={{ color: 'var(--color-text-secondary)' }}>{a.description}</p>
            )}
            <div className="flex items-center gap-4 mt-2 ml-8 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {a.model && <span>Model: {a.model}</span>}
              {a.max_turns && <span>Max turns: {a.max_turns}</span>}
              {a.system_default && <span className="px-2 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>System Default</span>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
