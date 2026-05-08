import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Span, Trace } from '../../api/client';
import TraceInspector from './TraceInspector';

interface Props {
  traceId: string;
  onClose: () => void;
}

interface PanelState {
  traceId: string;
  trace: Trace | null;
  spans: Span[];
}

export default function TraceDetailPanel({ traceId, onClose }: Props) {
  const [state, setState] = useState<PanelState>({ traceId: '', trace: null, spans: [] });

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.traces.get(traceId), api.traces.spans(traceId)])
      .then(([nextTrace, nextSpans]) => {
        if (cancelled) return;
        setState({ traceId, trace: nextTrace, spans: nextSpans || [] });
      })
      .catch(error => {
        console.warn('load trace failed', error);
        if (!cancelled) {
          setState({ traceId, trace: null, spans: [] });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [traceId]);

  const loading = state.traceId !== traceId;
  const trace = loading ? null : state.trace;

  return (
    <div className="flex flex-col border-l h-full w-[560px] shrink-0 min-h-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      {loading && (
        <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
          Loading...
        </div>
      )}
      {!loading && !trace && (
        <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
          Trace not found
        </div>
      )}
      {!loading && trace && <TraceInspector trace={trace} spans={state.spans} mode="panel" onClose={onClose} />}
    </div>
  );
}
