import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Brain, Calendar, GitBranch, Tag } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentMemoryView } from '../../api/client';

export default function AgentMemory() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [memories, setMemories] = useState<AgentMemoryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id) return;
    api.agents.memories(id)
      .then(res => setMemories(res.memories || []))
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load'))
      .finally(() => setLoading(false));
  }, [id]);

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    return d.toLocaleString();
  };

  const typeColor = (type: string) => {
    switch (type) {
      case 'WORKFLOW_PATTERN': return { bg: '#dbeafe', fg: '#1d4ed8' };
      case 'GOTCHA': return { bg: '#fee2e2', fg: '#dc2626' };
      case 'TOOL_USAGE': return { bg: '#fef3c7', fg: '#d97706' };
      case 'EFFICIENCY': return { bg: '#d1fae5', fg: '#059669' };
      case 'DOMAIN_KNOWLEDGE': return { bg: '#ede9fe', fg: '#7c3aed' };
      default: return { bg: '#f3f4f6', fg: '#6b7280' };
    }
  };

  return (
    <div className="p-6">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate(`/agents/${id}`)}
          className="p-2 rounded-lg border cursor-pointer"
          style={{ borderColor: 'var(--color-border)' }}>
          <ArrowLeft size={16} />
        </button>
        <div>
          <h1 className="text-2xl font-semibold flex items-center gap-2">
            <Brain size={24} style={{ color: 'var(--color-primary)' }} />
            Agent Memory
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Patterns and experiences learned from previous runs
          </p>
        </div>
      </div>

      {loading ? (
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : error ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
          {error}
        </div>
      ) : memories.length === 0 ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          <Brain size={48} className="mx-auto mb-3 opacity-30" />
          <p className="text-lg font-medium mb-1">No memories yet</p>
          <p className="text-sm">
            Memories are automatically extracted from completed agent runs.
            Once your agent has been used enough, patterns will appear here.
          </p>
        </div>
      ) : (
        <div className="grid gap-4">
          {memories.map((m, i) => {
            const tc = typeColor(m.type);
            return (
              <div key={m.id || i}
                className="rounded-xl border p-4"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1 flex-wrap">
                      {m.type && (
                        <span className="px-2 py-0.5 rounded text-xs font-medium inline-flex items-center gap-1"
                          style={{ background: tc.bg, color: tc.fg }}>
                          <Tag size={10} /> {m.type}
                        </span>
                      )}
                    </div>
                    <p className="text-sm whitespace-pre-wrap" style={{ lineHeight: '1.6' }}>
                      {m.content}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-4 mt-3 text-xs flex-wrap"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  {m.source_trace_ids && m.source_trace_ids.length > 0 && (
                    <span className="inline-flex items-center gap-1">
                      <GitBranch size={12} />
                      {m.source_trace_ids.length} source trace{m.source_trace_ids.length !== 1 ? 's' : ''}
                    </span>
                  )}
                  <span className="inline-flex items-center gap-1">
                    <Calendar size={12} />
                    Updated {formatTime(m.updated_at)}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
