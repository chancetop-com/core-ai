import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Brain, Calendar, GitBranch, Layers, Tag, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentMemoryView } from '../../api/client';

export default function AgentMemory() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [memories, setMemories] = useState<AgentMemoryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [deleting, setDeleting] = useState<string | null>(null); // memory id being deleted, or 'ALL' for delete-all

  useEffect(() => {
    if (!id) return;
    api.agents.memories(id)
      .then(res => setMemories(res.memories || []))
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load'))
      .finally(() => setLoading(false));
  }, [id]);

  const handleDelete = async (memoryId: string) => {
    if (!id || !window.confirm('Delete this memory? This cannot be undone.')) return;
    setDeleting(memoryId);
    try {
      await api.agents.deleteMemory(id, memoryId);
      setMemories(prev => prev.filter(m => m.id !== memoryId));
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete');
    } finally {
      setDeleting(null);
    }
  };

  const handleDeleteAll = async () => {
    if (!id || !window.confirm('Delete ALL memories for this agent? This cannot be undone.')) return;
    setDeleting('ALL');
    try {
      await api.agents.deleteAllMemories(id);
      setMemories([]);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete');
    } finally {
      setDeleting(null);
    }
  };

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
      case 'TRAJECTORY': return { bg: '#f3f4f6', fg: '#4b5563' };
      default: return { bg: '#f3f4f6', fg: '#6b7280' };
    }
  };

  const layerInfo = (layer: string) => {
    switch (layer) {
      case 'knowledge': return { label: 'Layer 1 - Knowledge', color: { bg: '#ede9fe', fg: '#7c3aed' }, desc: 'Auto-injected into system prompt. SOP-safe facts & gotchas.' };
      case 'methods': return { label: 'Layer 2 - Methods', color: { bg: '#dbeafe', fg: '#1d4ed8' }, desc: 'On-demand via search_memory tool. Patterns, tool tips, efficiencies.' };
      case 'trajectories': return { label: 'Layer 3 - Trajectories', color: { bg: '#d1fae5', fg: '#059669' }, desc: 'On-demand via search_memory tool. Raw session summaries, append-only.' };
      default: return { label: layer || 'Legacy', color: { bg: '#f3f4f6', fg: '#6b7280' }, desc: 'Pre-V2 memory (no layer assigned)' };
    }
  };

  // Group memories by layer for organized display
  const grouped = memories.reduce((acc, m) => {
    const layer = m.layer || 'legacy';
    if (!acc[layer]) acc[layer] = [];
    acc[layer].push(m);
    return acc;
  }, {} as Record<string, AgentMemoryView[]>);

  const layerOrder = ['knowledge', 'methods', 'trajectories', 'legacy'];

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
            Agent Memory V2
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Three-tier architecture: Knowledge (auto-inject) · Methods (on-demand) · Trajectories (session summaries)
          </p>
        </div>
        {memories.length > 0 && (
          <button
            onClick={handleDeleteAll}
            disabled={deleting === 'ALL'}
            className="ml-auto px-3 py-1.5 rounded-lg border text-sm font-medium inline-flex items-center gap-1.5 cursor-pointer transition-colors"
            style={{
              borderColor: 'var(--color-error)',
              color: 'var(--color-error)',
              background: 'transparent',
              opacity: deleting === 'ALL' ? 0.5 : 1,
            }}
          >
            <Trash2 size={14} />
            {deleting === 'ALL' ? 'Deleting...' : 'Delete All'}
          </button>
        )}
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
        <div className="space-y-6">
          {layerOrder.map(layerKey => {
            const layerMemories = grouped[layerKey];
            if (!layerMemories || layerMemories.length === 0) return null;
            const info = layerInfo(layerKey);
            return (
              <div key={layerKey}>
                <div className="flex items-center gap-2 mb-3">
                  <Layers size={16} style={{ color: info.color.fg }} />
                  <span className="text-sm font-semibold px-2 py-0.5 rounded" style={{ background: info.color.bg, color: info.color.fg }}>
                    {info.label}
                  </span>
                  <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {info.desc} · {layerMemories.length} entries
                  </span>
                </div>
                <div className="grid gap-3">
                  {layerMemories.map((m, i) => {
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
                          <button
                            onClick={() => handleDelete(m.id)}
                            disabled={deleting === m.id}
                            className="p-1.5 rounded-lg cursor-pointer transition-colors shrink-0"
                            style={{
                              color: 'var(--color-text-secondary)',
                              opacity: deleting === m.id ? 0.3 : 1,
                            }}
                            title="Delete this memory"
                          >
                            <Trash2 size={14} />
                          </button>
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
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
