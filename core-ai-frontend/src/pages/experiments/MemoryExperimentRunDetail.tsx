import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api, type ExperimentRun } from '../../api/client';

const LAYER_LABELS: Record<string, string> = {
  knowledge: 'Knowledge',
  methods: 'Methods',
  trajectories: 'Trajectories',
};

const RANKING_LABELS: Record<string, string> = {
  SEMANTIC: 'Semantic',
  BM25: 'BM25',
  RECENCY: 'Recency',
  IMPORTANCE: 'Importance',
  HYBRID: 'Hybrid',
  RANDOM: 'Random',
};

export default function MemoryExperimentRunDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [run, setRun] = useState<ExperimentRun | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    api.experiments.getRun(id)
      .then(setRun)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="p-6 text-gray-400 text-sm">Loading...</div>;
  if (error) return <div className="p-6 text-red-400 text-sm">{error}</div>;
  if (!run) return <div className="p-6 text-gray-500 text-sm">Run not found.</div>;

  return (
    <div className="p-6 max-w-4xl mx-auto">
      <button
        className="text-blue-400 hover:text-blue-300 text-sm mb-4 inline-block"
        onClick={() => navigate('/experiments/memory')}
      >
        ← Back to Agent Memory
      </button>

      <h1 className="text-2xl font-bold text-white mb-2">Agent Memory</h1>
      <p className="text-sm text-gray-400 mb-6 font-mono">{run.id}</p>

      <div className="grid grid-cols-2 gap-4 mb-6">
        <KV label="Agent" value={run.agent_id} />
        <KV label="Session" value={run.session_id} mono />
        <KV label="Run" value={run.run_id} mono />
        <KV label="Time" value={run.created_at ? new Date(run.created_at).toLocaleString() : '-'} />
      </div>

      {/* Policy */}
      <Section title="Policy">
        <div className="grid grid-cols-2 gap-2">
          <KV label="Enabled" value={run.enabled ? 'Yes' : 'No'} />
          <KV label="Injection Probability" value={run.injection_probability != null ? `${Math.round(run.injection_probability * 100)}%` : '-'} />
          <KV label="Layers" value={run.enabled_layers?.map(l => LAYER_LABELS[l] ?? l).join(', ') || '-'} />
          <KV label="Ranking" value={RANKING_LABELS[run.ranking_strategy] ?? run.ranking_strategy} />
          <KV label="Top-K" value={String(run.top_k ?? '-')} />
        </div>
      </Section>

      {/* Injection Result */}
      <Section title="Injection Result">
        <div className="grid grid-cols-2 gap-2">
          <KV label="Decision" value={run.injection_decision ? 'Injected' : 'Skipped'} highlight={run.injection_decision} />
          <KV label="Memory Count" value={String(run.injected_memory_count ?? 0)} />
          <KV label="Prompt Tokens" value={String(run.prompt_tokens ?? 0)} />
        </div>
        {run.layer_breakdown && Object.keys(run.layer_breakdown).length > 0 && (
          <div className="mt-3">
            <div className="text-xs text-gray-400 mb-1">Layer Breakdown</div>
            <div className="flex gap-3">
              {Object.entries(run.layer_breakdown).map(([layer, count]) => (
                <span key={layer} className="text-xs bg-gray-800 rounded px-2 py-0.5">
                  {LAYER_LABELS[layer] ?? layer}: <span className="text-white">{count}</span>
                </span>
              ))}
            </div>
          </div>
        )}
        {run.injected_memory_ids && run.injected_memory_ids.length > 0 && (
          <div className="mt-3">
            <div className="text-xs text-gray-400 mb-1">Injected Memory IDs</div>
            <div className="flex flex-wrap gap-1">
              {run.injected_memory_ids.map(mid => (
                <code key={mid} className="text-xs bg-gray-800 rounded px-1 py-0.5 text-gray-300 font-mono">{mid.slice(-12)}</code>
              ))}
            </div>
          </div>
        )}
      </Section>

      {/* Outcome */}
      <Section title="Outcome">
        <div className="grid grid-cols-2 gap-2">
          <KV label="Outcome" value={run.outcome || '-'} />
          <KV label="User Rating" value={run.user_rating != null ? '⭐'.repeat(run.user_rating) : '-'} />
        </div>
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-6">
      <h2 className="text-sm font-semibold text-gray-300 mb-2 pb-1 border-b border-gray-800">{title}</h2>
      {children}
    </div>
  );
}

function KV({ label, value, mono, highlight }: { label: string; value: string; mono?: boolean; highlight?: boolean }) {
  return (
    <div>
      <div className="text-xs text-gray-500">{label}</div>
      <div className={`text-sm ${highlight ? 'text-green-400 font-medium' : 'text-white'} ${mono ? 'font-mono' : ''}`}>
        {value}
      </div>
    </div>
  );
}
