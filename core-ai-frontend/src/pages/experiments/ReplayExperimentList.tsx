import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FlaskConical, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api, type ReplayExperimentListItem } from '../../api/client';
import { usePermission } from '../../api/permissions';
import { formatRelativeTime } from '../traces/traceViewModel';

const PAGE_SIZE = 20;

/** Shared list for both origins: SPAN = Replay Debug, BLANK = Playground. */
export default function ReplayExperimentList({ origin = 'SPAN' }: { origin?: 'SPAN' | 'BLANK' }) {
  const navigate = useNavigate();
  const isPlayground = origin === 'BLANK';
  const canReplay = usePermission('experiment.replay');
  const [experiments, setExperiments] = useState<ReplayExperimentListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async (nextOffset: number) => {
    setLoading(true);
    try {
      const res = await api.replay.list(undefined, nextOffset, PAGE_SIZE, origin);
      setExperiments(res.experiments);
      setTotal(res.total);
    } catch (e) {
      console.error('load replay experiments failed', e);
    } finally {
      setLoading(false);
    }
  }, [origin]);

  const createPlayground = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const experiment = await api.replay.create();
      navigate(`/experiments/replay/${experiment.id}`);
    } catch (e) {
      console.error('create playground experiment failed', e);
      setCreating(false);
    }
  };

  const removeExperiment = async (experiment: ReplayExperimentListItem) => {
    if (!window.confirm(`Delete this replay experiment${experiment.span_name ? ` (${experiment.span_name})` : ''} and all its runs?`)) return;
    try {
      await api.replay.delete(experiment.id);
      // if the last item on the page is removed, step back one page
      const nextOffset = experiments.length === 1 && offset > 0 ? offset - PAGE_SIZE : offset;
      setOffset(nextOffset);
      await load(nextOffset);
    } catch (e) {
      console.error('delete replay experiment failed', e);
    }
  };

  useEffect(() => {
    load(offset);
  }, [load, offset]);

  return (
    <div className="p-4 max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-lg font-semibold flex items-center gap-2">
            <FlaskConical size={18} style={{ color: 'var(--color-primary)' }} /> {isPlayground ? 'Playground' : 'Replay Debug'}
          </h1>
          <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
            {isPlayground
              ? 'Blank experiments written from scratch — no trace needed. Edit the request and run samples to compare responses.'
              : 'Experiments created from trace LLM spans — edit the request and rerun it to compare responses.'}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
            onClick={() => load(offset)}>
            <RefreshCw size={12} /> Refresh
          </button>
          {isPlayground && (
            <button
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium disabled:opacity-50"
              style={{ background: 'var(--color-primary)', color: '#fff' }}
              disabled={creating || !canReplay}
              title={canReplay ? '' : 'Missing experiment.replay permission'}
              onClick={createPlayground}>
              <Plus size={12} /> New Playground
            </button>
          )}
        </div>
      </div>

      <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs" style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-secondary)' }}>
              {isPlayground ? (
                <th className="px-3 py-2 font-medium">Experiment</th>
              ) : (
                <>
                  <th className="px-3 py-2 font-medium">Span</th>
                  <th className="px-3 py-2 font-medium">Agent</th>
                  <th className="px-3 py-2 font-medium">Model</th>
                </>
              )}
              <th className="px-3 py-2 font-medium">Runs</th>
              <th className="px-3 py-2 font-medium">Created</th>
              <th className="px-3 py-2 font-medium w-10"></th>
            </tr>
          </thead>
          <tbody>
            {experiments.map(experiment => (
              <tr
                key={experiment.id}
                className="cursor-pointer border-t"
                style={{ borderColor: 'var(--color-border)' }}
                onClick={() => navigate(`/experiments/replay/${experiment.id}`)}>
                {isPlayground ? (
                  <td className="px-3 py-2.5 font-medium">
                    <span className="text-[10px] px-1.5 py-0.5 rounded font-medium mr-2" style={{ background: 'rgba(124,58,237,0.12)', color: '#7c3aed' }}>
                      Playground
                    </span>
                    <span className="font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>{experiment.id.slice(0, 8)}</span>
                  </td>
                ) : (
                  <>
                    <td className="px-3 py-2.5 font-medium">{experiment.span_name || '-'}</td>
                    <td className="px-3 py-2.5">{experiment.agent_name || experiment.agent_id || '-'}</td>
                    <td className="px-3 py-2.5 font-mono text-xs">{experiment.original_model || '-'}</td>
                  </>
                )}
                <td className="px-3 py-2.5">{experiment.run_count ?? 0}</td>
                <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {formatRelativeTime(experiment.created_at)}
                </td>
                <td className="px-3 py-2.5">
                  <button
                    className="p-1.5 rounded-md hover:opacity-80"
                    style={{ color: 'var(--color-text-secondary)' }}
                    title="Delete experiment"
                    onClick={event => {
                      event.stopPropagation();
                      removeExperiment(experiment);
                    }}>
                    <Trash2 size={13} />
                  </button>
                </td>
              </tr>
            ))}
            {!loading && experiments.length === 0 && (
              <tr>
                <td colSpan={isPlayground ? 4 : 6} className="px-3 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                  {isPlayground
                    ? 'No playground experiments yet. Click "New Playground" to start one from scratch.'
                    : 'No replay experiments yet. Open a trace → select an LLM span → click Replay.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
        <span>{total} experiments</span>
        <div className="flex gap-2">
          <button
            className="px-2.5 py-1 rounded-md disabled:opacity-40"
            style={{ background: 'var(--color-bg-tertiary)' }}
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
            Prev
          </button>
          <button
            className="px-2.5 py-1 rounded-md disabled:opacity-40"
            style={{ background: 'var(--color-bg-tertiary)' }}
            disabled={offset + PAGE_SIZE >= total}
            onClick={() => setOffset(offset + PAGE_SIZE)}>
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
