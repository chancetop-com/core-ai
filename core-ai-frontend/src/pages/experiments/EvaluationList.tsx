import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, FlaskConical, Plus, Search, Trash2 } from 'lucide-react';
import { api, type AgentDefinition } from '../../api/client';
import { evaluationApi, type Evaluation } from '../../api/evaluation';

const PAGE_SIZE = 20;

function formatTime(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

export default function EvaluationList() {
  const navigate = useNavigate();
  const [evaluations, setEvaluations] = useState<Evaluation[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [agentFilter, setAgentFilter] = useState('');

  const [showCreate, setShowCreate] = useState(false);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [name, setName] = useState('');
  const [agentId, setAgentId] = useState('');
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await evaluationApi.list(agentFilter || undefined, offset, PAGE_SIZE);
      setEvaluations(response.evaluations);
      setTotal(response.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [agentFilter, offset]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const timer = setTimeout(() => {
      setAgentFilter(search);
      setOffset(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  const openCreate = async () => {
    setShowCreate(true);
    if (agents.length === 0) {
      try {
        const response = await api.agents.listSummaries();
        setAgents(response.agents);
      } catch {
        // keep the picker empty
      }
    }
  };

  const create = async () => {
    if (!name.trim() || !agentId) return;
    setCreating(true);
    try {
      const evaluation = await evaluationApi.create({ name: name.trim(), agent_id: agentId, description });
      setShowCreate(false);
      setName('');
      setAgentId('');
      setDescription('');
      navigate(`/experiments/evaluations/${evaluation.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setCreating(false);
    }
  };

  const archive = async (evaluation: Evaluation) => {
    if (!confirm(`Archive evaluation "${evaluation.name}"?`)) return;
    try {
      await evaluationApi.archive(evaluation.id);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Evaluations</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Repeatable offline evaluation of one agent version, with a frozen dataset, graders and baseline.
          </p>
        </div>
        <button onClick={openCreate}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={16} /> New Evaluation
        </button>
      </div>

      <div className="flex items-center justify-between mb-4">
        <div className="relative w-72">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--color-text-secondary)' }} />
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Filter by agent ID..."
            className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm"
          style={{ borderColor: 'rgba(239,68,68,0.4)', color: '#ef4444', background: 'rgba(239,68,68,0.06)' }}>
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : evaluations.length === 0 ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          No evaluations yet. Create one to freeze a dataset and start a baseline run.
        </div>
      ) : (
        <div className="grid gap-3">
          {evaluations.map(evaluation => (
            <div key={evaluation.id}
              onClick={() => navigate(`/experiments/evaluations/${evaluation.id}`)}
              className="rounded-xl border p-4 cursor-pointer transition-colors"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
              onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
              onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3 min-w-0">
                  <FlaskConical size={16} style={{ color: 'var(--color-primary)' }} />
                  <span className="font-medium truncate">{evaluation.name}</span>
                  <span className="px-2 py-0.5 rounded text-xs font-mono"
                    style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
                    {evaluation.agent_id}
                  </span>
                  {evaluation.baseline_run_id ? (
                    <span className="px-2 py-0.5 rounded text-xs"
                      style={{ background: 'rgba(34,197,94,0.1)', color: '#22c55e' }}>
                      Baseline set
                    </span>
                  ) : (
                    <span className="px-2 py-0.5 rounded text-xs"
                      style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
                      No baseline
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  <span>Updated {formatTime(evaluation.updated_at)}</span>
                  <button onClick={e => { e.stopPropagation(); archive(evaluation); }}
                    className="p-1.5 rounded cursor-pointer" title="Archive">
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
              {evaluation.description && (
                <div className="mt-2 text-sm" style={{ color: 'var(--color-text-secondary)' }}>{evaluation.description}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {total > 0 && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Showing {offset + 1}–{Math.min(offset + PAGE_SIZE, total)} of {total}
          </span>
          <div className="flex gap-2">
            <button onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))} disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setOffset(offset + PAGE_SIZE)} disabled={offset + PAGE_SIZE >= total}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ background: 'rgba(0,0,0,0.5)' }} onClick={() => setShowCreate(false)}>
          <div className="rounded-2xl shadow-2xl w-[520px] p-6" style={{ background: 'var(--color-bg)' }}
            onClick={e => e.stopPropagation()}>
            <h2 className="text-xl font-semibold mb-2">New Evaluation</h2>
            <p className="text-sm mb-5" style={{ color: 'var(--color-text-secondary)' }}>
              Pick the agent to evaluate. Its executable configuration is frozen when a run starts.
            </p>
            <label className="block text-sm font-medium mb-1">Name</label>
            <input value={name} onChange={e => setName(e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none mb-4"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            <label className="block text-sm font-medium mb-1">Agent</label>
            <select value={agentId} onChange={e => setAgentId(e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none mb-4 cursor-pointer"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
              <option value="">Select an agent...</option>
              {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name} ({agent.id})</option>)}
            </select>
            <label className="block text-sm font-medium mb-1">Description</label>
            <textarea value={description} onChange={e => setDescription(e.target.value)} rows={3}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none mb-5 resize-y"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
            <div className="flex justify-end gap-2">
              <button onClick={() => setShowCreate(false)}
                className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                Cancel
              </button>
              <button onClick={create} disabled={creating || !name.trim() || !agentId}
                className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                {creating ? 'Creating...' : 'Create'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
