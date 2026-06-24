import { useCallback, useEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Search, Copy, Compass } from 'lucide-react';
import { api, type WorkflowView } from '../../api/client';

const PAGE_SIZE = 24;

export default function WorkflowExplore() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [workflows, setWorkflows] = useState<WorkflowView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [cloningId, setCloningId] = useState('');
  const [error, setError] = useState('');
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reqIdRef = useRef(0);

  // replace=true resets the grid (new search); replace=false appends (load more)
  const loadPage = useCallback(async (kw: string, offset: number, replace: boolean) => {
    const myReq = ++reqIdRef.current;
    setLoading(true);
    setError('');
    try {
      const res = await api.workflows.explore(kw, offset, PAGE_SIZE);
      if (myReq !== reqIdRef.current) return;   // a newer request superseded this one
      setTotal(res.total || 0);
      setWorkflows((prev) => (replace ? (res.workflows || []) : [...prev, ...(res.workflows || [])]));
    } catch (e) {
      if (myReq === reqIdRef.current) setError((e as Error).message);
    } finally {
      if (myReq === reqIdRef.current) setLoading(false);
    }
  }, []);

  // debounce the keyword; every change reloads from offset 0
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => { void loadPage(keyword, 0, true); }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [keyword, loadPage]);

  const cloneWorkflow = async (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    setCloningId(wf.id);
    setError('');
    try {
      const res = await api.workflows.clone(wf.id);
      navigate(`/workflows/${res.workflow.id}`, { state: { cloneWarnings: res.warnings || [] } });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setCloningId('');
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
        <button onClick={() => navigate('/workflows')} style={iconBtn} title="Back"><ArrowLeft size={16} /></button>
        <h1 style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 20, fontWeight: 600, margin: 0, color: 'var(--color-text)' }}>
          <Compass size={20} /> Explore workflows
        </h1>
      </div>
      <div style={{ position: 'relative', marginBottom: 16, maxWidth: 420 }}>
        <Search size={15} style={{ position: 'absolute', left: 10, top: 10, color: 'var(--color-text-secondary)' }} />
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="Search published workflows by name…"
          style={searchInput}
        />
      </div>
      {error && <div style={{ color: '#dc2626', marginBottom: 12 }}>{error}</div>}
      {workflows.length === 0 && !loading ? (
        <div style={{ color: 'var(--color-text-secondary)' }}>
          {keyword ? 'No workflows match your search.' : 'No published workflows yet.'}
        </div>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
            {workflows.map((wf) => (
              <div key={wf.id} onClick={() => navigate(`/workflows/${wf.id}`)} style={card} title="Open read-only to run">
                <button onClick={(e) => cloneWorkflow(e, wf)} style={cloneBtn} disabled={cloningId === wf.id} title="Clone to my workflows">
                  <Copy size={14} /> {cloningId === wf.id ? 'Cloning…' : 'Clone'}
                </button>
                <div style={{ fontWeight: 600, color: 'var(--color-text)', paddingRight: 92 }}>{wf.name}</div>
                <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 4 }}>
                  {wf.mode}
                  {wf.published_version ? ` · v${wf.published_version}` : ''}
                  {wf.user_name ? ` · by ${wf.user_name}` : ''}
                </div>
              </div>
            ))}
          </div>
          {workflows.length < total && (
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: 16 }}>
              <button onClick={() => void loadPage(keyword, workflows.length, false)} disabled={loading} style={loadMoreBtn}>
                {loading ? 'Loading…' : `Load more (${workflows.length}/${total})`}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text)', cursor: 'pointer',
};
const searchInput: CSSProperties = {
  width: '100%', padding: '8px 12px 8px 32px', borderRadius: 8,
  border: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)', outline: 'none',
};
const card: CSSProperties = {
  position: 'relative', border: '1px solid var(--color-border)', borderRadius: 10, padding: 16, cursor: 'pointer', background: 'var(--color-bg-secondary)',
};
const cloneBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 10, display: 'flex', alignItems: 'center', gap: 4,
  padding: '4px 8px', border: '1px solid var(--color-border)', borderRadius: 6,
  background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer', fontSize: 12,
};
const loadMoreBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px',
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'transparent',
  color: 'var(--color-text)', cursor: 'pointer', fontWeight: 500,
};
