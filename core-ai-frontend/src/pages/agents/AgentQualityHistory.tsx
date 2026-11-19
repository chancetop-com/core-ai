import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import { qualityApi, type AgentQualityRun } from '../../api/quality';
import Pagination from '../../components/Pagination';
import { useListState } from '../../hooks/useListState';

export default function AgentQualityHistory() {
  const { id } = useParams<{ id: string }>();
  const agentId = id || '';
  const navigate = useNavigate();
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [runs, setRuns] = useState<AgentQualityRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offset, setOffset] = useListState('agent-quality-history.offset', 0);
  const [limit, setLimit] = useListState('agent-quality-history.limit', 20);

  const load = async () => {
    if (!agentId) return;
    setLoading(true);
    setError('');
    try {
      const [agentRes, runsRes] = await Promise.all([
        api.agents.get(agentId),
        qualityApi.runs(agentId),
      ]);
      setAgent(agentRes);
      setRuns(runsRes.runs || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load quality history');
      setRuns([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [agentId]);

  useEffect(() => {
    if (runs.length === 0) {
      if (offset !== 0) setOffset(0);
    } else if (offset >= runs.length) {
      setOffset(Math.floor((runs.length - 1) / limit) * limit);
    }
  }, [runs.length, offset, limit, setOffset]);

  const pageRuns = runs.slice(offset, offset + limit);

  return (
    <div className="p-6">
      <button onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}`)}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Agent
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div>
          <h1 className="text-xl font-semibold">Policy history</h1>
          <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {agent?.name || agentId}
            {' · '}
            {runs.length} quality {runs.length === 1 ? 'check' : 'checks'} recorded for this agent
          </div>
        </div>
        <button onClick={load} disabled={loading} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      <div className="rounded-lg border overflow-x-auto" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
              <th className="px-3 py-3 font-medium">Time</th>
              <th className="px-3 py-3 font-medium">Mode</th>
              <th className="px-3 py-3 font-medium">Policy rev</th>
              <th className="px-3 py-3 font-medium">Definition rev</th>
              <th className="px-3 py-3 font-medium">Gate result</th>
              <th className="px-3 py-3 font-medium">Status</th>
              <th className="px-3 py-3 font-medium">Warnings</th>
              <th className="px-3 py-3 font-medium">Block fails</th>
              <th className="px-3 py-3 font-medium">Incomplete</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={9} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : pageRuns.length === 0 ? (
              <tr><td colSpan={9} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>No quality checks recorded for this agent yet.</td></tr>
            ) : pageRuns.map(run => (
              <tr key={run.run_id} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-3 text-xs whitespace-nowrap">{run.created_at ? new Date(run.created_at).toLocaleString() : '-'}</td>
                <td className="px-3 py-3 text-xs">{run.mode || '-'}</td>
                <td className="px-3 py-3 text-xs">{run.policy_revision ?? '-'}</td>
                <td className="px-3 py-3 text-xs">{run.definition_revision ?? '-'}</td>
                <td className="px-3 py-3 text-xs">
                  <span className="px-1.5 py-0.5 rounded" style={gateStyle(run.gate_result)}>{run.gate_result || '-'}</span>
                  {run.exempted && (
                    <span className="ml-1 px-1.5 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      exempt
                    </span>
                  )}
                </td>
                <td className="px-3 py-3 text-xs">{run.run_status || '-'}</td>
                <td className="px-3 py-3 text-xs">{run.warn_count ?? 0}</td>
                <td className="px-3 py-3 text-xs">{run.block_fail_count ?? 0}</td>
                <td className="px-3 py-3 text-xs">{run.incomplete_count ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {runs.length > 0 && (
        <Pagination
          offset={offset}
          limit={limit}
          total={runs.length}
          shown={pageRuns.length}
          onOffsetChange={setOffset}
          onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
        />
      )}
    </div>
  );
}

function gateStyle(result?: string) {
  if (result === 'PASSED') return { background: 'rgba(34,197,94,0.12)', color: 'var(--color-success)' };
  if (result === 'REJECTED') return { background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' };
  return { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' };
}
