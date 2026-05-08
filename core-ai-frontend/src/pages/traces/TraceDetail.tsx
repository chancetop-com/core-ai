import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Span, Trace } from '../../api/client';
import TraceInspector from './TraceInspector';

interface TraceDetailState {
  id: string;
  trace: Trace | null;
  spans: Span[];
}

export default function TraceDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [state, setState] = useState<TraceDetailState>({ id: '', trace: null, spans: [] });

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.all([api.traces.get(id), api.traces.spans(id)])
      .then(([nextTrace, nextSpans]) => {
        if (cancelled) return;
        setState({ id, trace: nextTrace, spans: nextSpans || [] });
      })
      .catch(error => {
        console.warn('load trace failed', error);
        if (!cancelled) {
          setState({ id, trace: null, spans: [] });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [id]);

  const loading = !!id && state.id !== id;
  const trace = loading ? null : state.trace;

  if (loading) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  }

  if (!trace) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Trace not found</div>;
  }

  return (
    <TraceInspector
      trace={trace}
      spans={state.spans}
      mode="page"
      onBack={() => navigate('/traces')}
    />
  );
}
