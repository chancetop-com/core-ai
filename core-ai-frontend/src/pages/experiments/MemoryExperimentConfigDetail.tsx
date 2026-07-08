import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api, type AgentMemoryExperimentConfig } from '../../api/client';

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

const ALL_LAYERS = ['knowledge', 'methods', 'trajectories'] as const;
const ALL_RANKING = ['SEMANTIC', 'BM25', 'RECENCY', 'IMPORTANCE', 'HYBRID', 'RANDOM'] as const;

const DEFAULT_CONFIG: AgentMemoryExperimentConfig = {
  id: '',
  agent_id: '',
  enabled: true,
  injection_probability: 1.0,
  enabled_layers: ['knowledge', 'methods', 'trajectories'],
  top_k: 5,
  ranking_strategy: 'SEMANTIC',
};

export default function MemoryExperimentConfigDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [config, setConfig] = useState<AgentMemoryExperimentConfig | null>(null);
  const [draft, setDraft] = useState<AgentMemoryExperimentConfig>(DEFAULT_CONFIG);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!id) return;
    loadConfig();
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadConfig = async () => {
    setLoading(true);
    try {
      const c = await api.experiments.getConfig(id!);
      if (c) {
        setConfig(c);
        setDraft({ ...c });
      } else {
        setDraft({ ...DEFAULT_CONFIG, agent_id: id! });
        setConfig(null);
      }
    } catch (e: any) {
      if (e.message?.includes('404')) {
        setDraft({ ...DEFAULT_CONFIG, agent_id: id! });
        setConfig(null);
      } else {
        setError(e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  const toggleLayer = (layer: string) => {
    setDraft(prev => {
      const layers = prev.enabled_layers.includes(layer)
        ? prev.enabled_layers.filter(l => l !== layer)
        : [...prev.enabled_layers, layer];
      return { ...prev, enabled_layers: layers };
    });
  };

  const hasChanged = config
    ? JSON.stringify(draft) !== JSON.stringify(config)
    : true;


  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    try {
      const saved = await api.experiments.saveConfig(id, draft);
      setConfig(saved);
      setDraft({ ...saved });
      setError('');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    if (config) setDraft({ ...config });
    else setDraft({ ...DEFAULT_CONFIG, agent_id: id! });
  };

  if (loading) return <div className="p-6 text-gray-400 text-sm">Loading...</div>;

  return (
    <div className="p-6 max-w-3xl mx-auto">
      <button
        className="text-blue-400 hover:text-blue-300 text-sm mb-4 inline-block"
        onClick={() => navigate('/experiments/memory')}
      >
        ← Back to Agent Memory
      </button>

      <h1 className="text-2xl font-bold text-white mb-1">Agent Memory</h1>
      <p className="text-sm text-gray-400 mb-6">Agent: <span className="text-white font-mono">{id}</span></p>

      {error && (
        <div className="mb-4 p-3 bg-red-900/30 border border-red-800 rounded text-red-300 text-sm">{error}</div>
      )}

      <div className="space-y-6">
        {/* Enabled */}
        <div className="flex items-center justify-between bg-gray-800/50 rounded p-4">
          <div>
            <div className="text-sm text-white">Enabled</div>
            <div className="text-xs text-gray-400 mt-0.5">Turn experiment on/off for this agent</div>
          </div>
          <label className="relative inline-flex items-center cursor-pointer">
            <input
              type="checkbox"
              className="sr-only peer"
              checked={draft.enabled}
              onChange={e => setDraft(prev => ({ ...prev, enabled: e.target.checked }))}
            />
            <div className="w-9 h-5 bg-gray-600 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-blue-600"></div>
          </label>
        </div>

        {/* Injection Probability */}
        <div className="bg-gray-800/50 rounded p-4">
          <div className="flex items-center justify-between mb-2">
            <div className="text-sm text-white">Injection Probability</div>
            <span className="text-xs text-blue-400">{Math.round(draft.injection_probability * 100)}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={draft.injection_probability}
            onChange={e => setDraft(prev => ({ ...prev, injection_probability: Number(e.target.value) }))}
            className="w-full h-2 bg-gray-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
          />
          <div className="flex justify-between text-xs text-gray-500 mt-1">
            <span>0% (never)</span>
            <span>100% (always)</span>
          </div>
        </div>

        {/* Layers */}
        <div className="bg-gray-800/50 rounded p-4">
          <div className="text-sm text-white mb-3">Memory Layers</div>
          <div className="flex gap-2">
            {ALL_LAYERS.map(layer => (
              <button
                key={layer}
                className={`px-3 py-1.5 rounded text-xs border transition ${
                  draft.enabled_layers.includes(layer)
                    ? 'bg-blue-600/20 border-blue-600 text-blue-300'
                    : 'bg-gray-700 border-gray-600 text-gray-400 hover:border-gray-500'
                }`}
                onClick={() => toggleLayer(layer)}
              >
                {LAYER_LABELS[layer]}
              </button>
            ))}
          </div>
        </div>

        {/* Top-K */}
        <div className="bg-gray-800/50 rounded p-4">
          <div className="text-sm text-white mb-2">Top-K</div>
          <select
            className="bg-gray-700 border border-gray-600 rounded px-3 py-1.5 text-sm text-white"
            value={draft.top_k}
            onChange={e => setDraft(prev => ({ ...prev, top_k: Number(e.target.value) }))}
          >
            {[1, 3, 5, 10, 20].map(k => <option key={k} value={k}>{k}</option>)}
          </select>
          <div className="text-xs text-gray-500 mt-1">Max memories to inject per run</div>
        </div>

        {/* Ranking Strategy */}
        <div className="bg-gray-800/50 rounded p-4">
          <div className="text-sm text-white mb-2">Ranking Strategy</div>
          <select
            className="bg-gray-700 border border-gray-600 rounded px-3 py-1.5 text-sm text-white w-full max-w-xs"
            value={draft.ranking_strategy}
            onChange={e => setDraft(prev => ({ ...prev, ranking_strategy: e.target.value }))}
          >
            {ALL_RANKING.map(s => <option key={s} value={s}>{RANKING_LABELS[s]}</option>)}
          </select>
          <div className="text-xs text-gray-500 mt-1">How memories are sorted before top-K selection</div>
        </div>

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <button
            className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white text-sm rounded disabled:opacity-50"
            disabled={!hasChanged || saving}
            onClick={handleSave}
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
          {hasChanged && (
            <button
              className="px-4 py-2 bg-gray-700 hover:bg-gray-600 text-gray-300 text-sm rounded"
              onClick={handleReset}
            >
              Reset
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
