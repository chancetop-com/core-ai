import { useCallback, useContext, useEffect, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Trash2, History, Download, FileUp, Copy, ChevronLeft, ChevronRight, Search, Star, Workflow as WorkflowIcon } from 'lucide-react';
import { api, type WorkflowView } from '../../api/client';
import { AuthContext } from '../../api/auth';
import { newGraph } from './graph';

const PAGE_SIZE = 10;

interface WorkflowListProps {
  initialTab?: 'my' | 'shared';
}

function workflowPageKey(keyword: string, offset: number) {
  return `${keyword}\u0000${offset}`;
}

export default function WorkflowList({ initialTab = 'my' }: WorkflowListProps) {
  const [myWorkflows, setMyWorkflows] = useState<WorkflowView[]>([]);
  const [sharedWorkflows, setSharedWorkflows] = useState<WorkflowView[]>([]);
  const [myTotal, setMyTotal] = useState(0);
  const [sharedTotal, setSharedTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [myLoading, setMyLoading] = useState(false);
  const [sharedLoading, setSharedLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [cloningId, setCloningId] = useState('');
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState<'my' | 'shared'>(initialTab);
  const [keyword, setKeyword] = useState('');
  const [myOffset, setMyOffset] = useState(0);
  const [sharedOffset, setSharedOffset] = useState(0);
  const navigate = useNavigate();
  const { user } = useContext(AuthContext);
  const isAdmin = user?.role === 'admin';
  const [importing, setImporting] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const myReqIdRef = useRef(0);
  const sharedReqIdRef = useRef(0);
  const myPageKeyRef = useRef<string | null>(null);
  const myLoadingKeyRef = useRef<string | null>(null);
  const sharedPageKeyRef = useRef<string | null>(null);
  const sharedLoadingKeyRef = useRef<string | null>(null);

  const loadMy = useCallback(async (kw: string, offset: number) => {
    const key = workflowPageKey(kw, offset);
    if (myPageKeyRef.current === key || myLoadingKeyRef.current === key) return;
    const myReq = ++myReqIdRef.current;
    myLoadingKeyRef.current = key;
    setMyLoading(true);
    setError('');
    try {
      const res = await api.workflows.list(true, kw, offset, PAGE_SIZE);
      if (myReq !== myReqIdRef.current) return;
      setMyWorkflows(res.workflows || []);
      setMyTotal(res.total || 0);
      myPageKeyRef.current = key;
    } catch (e) {
      if (myReq === myReqIdRef.current) setError((e as Error).message);
    } finally {
      if (myLoadingKeyRef.current === key) myLoadingKeyRef.current = null;
      if (myReq === myReqIdRef.current) setMyLoading(false);
    }
  }, []);

  const loadShared = useCallback(async (kw: string, offset: number) => {
    const key = workflowPageKey(kw, offset);
    if (sharedPageKeyRef.current === key || sharedLoadingKeyRef.current === key) return;
    const myReq = ++sharedReqIdRef.current;
    sharedLoadingKeyRef.current = key;
    setSharedLoading(true);
    setError('');
    try {
      const res = await api.workflows.explore(kw, offset, PAGE_SIZE);
      if (myReq !== sharedReqIdRef.current) return;
      setSharedWorkflows(res.workflows || []);
      setSharedTotal(res.total || 0);
      sharedPageKeyRef.current = key;
    } catch (e) {
      if (myReq === sharedReqIdRef.current) setError((e as Error).message);
    } finally {
      if (sharedLoadingKeyRef.current === key) sharedLoadingKeyRef.current = null;
      if (myReq === sharedReqIdRef.current) setSharedLoading(false);
    }
  }, []);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api.workflows.list(true, '', 0, PAGE_SIZE),
      api.workflows.explore('', 0, PAGE_SIZE),
    ])
      .then(([mine, shared]) => {
        setMyWorkflows(mine.workflows || []);
        setMyTotal(mine.total || 0);
        setSharedWorkflows(shared.workflows || []);
        setSharedTotal(shared.total || 0);
        myPageKeyRef.current = workflowPageKey('', 0);
        sharedPageKeyRef.current = workflowPageKey('', 0);
      })
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (loading) return;
    const offset = activeTab === 'my' ? myOffset : sharedOffset;
    const key = workflowPageKey(keyword, offset);
    const loadedKey = activeTab === 'my' ? myPageKeyRef.current : sharedPageKeyRef.current;
    const loadingKey = activeTab === 'my' ? myLoadingKeyRef.current : sharedLoadingKeyRef.current;
    if (loadedKey === key || loadingKey === key) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      if (activeTab === 'my') {
        void loadMy(keyword, offset);
      } else {
        void loadShared(keyword, offset);
      }
    }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [activeTab, keyword, myOffset, sharedOffset, loading, loadMy, loadShared]);

  const create = async () => {
    setCreating(true);
    setError('');
    try {
      const wf = await api.workflows.create({ name: 'Untitled workflow', mode: 'WORKFLOW', graph: JSON.stringify(newGraph()) });
      navigate(`/workflows/${wf.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const openRuns = (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    navigate(`/workflows/${wf.id}/runs`);
  };

  const del = async (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    const archived = wf.published_version || wf.status === 'PUBLIC';
    const action = archived ? 'Archive' : 'Delete';
    const detail = archived
      ? 'It will leave public/shared lists and existing pinned sub-workflow references keep using their saved version.'
      : 'This cannot be undone.';
    if (!window.confirm(`${action} "${wf.name}"? ${detail}`)) return;
    try {
      await api.workflows.delete(wf.id);
      if (activeTab === 'my') {
        setMyTotal((total) => Math.max(0, total - 1));
        if (myWorkflows.length === 1 && myOffset > 0) {
          setMyOffset(Math.max(0, myOffset - PAGE_SIZE));
        } else {
          setMyWorkflows((ws) => ws.filter((w) => w.id !== wf.id));
        }
      } else {
        setSharedTotal((total) => Math.max(0, total - 1));
        if (sharedWorkflows.length === 1 && sharedOffset > 0) {
          setSharedOffset(Math.max(0, sharedOffset - PAGE_SIZE));
        } else {
          setSharedWorkflows((ws) => ws.filter((w) => w.id !== wf.id));
        }
      }
    } catch (err) {
      setError((err as Error).message);
    }
  };

  const exportWorkflow = async (e: ReactMouseEvent, wf: WorkflowView) => {
    e.stopPropagation();
    try {
      const envelope = await api.workflows.export(wf.id);
      const json = JSON.stringify(envelope, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${wf.name.replace(/\s+/g, '-').toLowerCase()}.workflow.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError((err as Error).message);
    }
  };

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

  const importWorkflow = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImporting(true);
    setError('');
    try {
      const text = await file.text();
      const res = await api.workflows.import(text);
      // Hand any unresolved references to the editor, which surfaces them inline where the user lands.
      navigate(`/workflows/${res.workflow.id}`, { state: { importNotice: res.unresolved_references || [] } });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setImporting(false);
      e.target.value = '';
    }
  };

  const handleTabChange = (tab: 'my' | 'shared') => {
    setActiveTab(tab);
    setKeyword('');
    setMyOffset(0);
    setSharedOffset(0);
  };

  const currentWorkflows = activeTab === 'my' ? myWorkflows : sharedWorkflows;
  const currentLoading = loading || (activeTab === 'my' ? myLoading : sharedLoading);
  const currentTotal = activeTab === 'my' ? myTotal : sharedTotal;
  const currentOffset = activeTab === 'my' ? myOffset : sharedOffset;
  const nextDisabled = currentOffset + PAGE_SIZE >= currentTotal;
  const prevDisabled = currentOffset === 0;
  const setCurrentOffset = activeTab === 'my' ? setMyOffset : setSharedOffset;

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Workflows</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create and manage your workflows, or browse shared workflows from others
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {activeTab === 'my' && (
            <>
              <label style={{ ...btnSecondary, opacity: importing ? 0.5 : 1 }}>
                <FileUp size={16} /> {importing ? 'Importing...' : 'Import'}
                <input type="file" accept=".json" style={{ display: 'none' }} onChange={importWorkflow} disabled={importing} />
              </label>
              <button onClick={create} disabled={creating} style={btnPrimary}>
                <Plus size={16} /> New workflow
              </button>
            </>
          )}
        </div>
      </div>

      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--color-bg-secondary)' }}>
          <button
            onClick={() => handleTabChange('my')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'my' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'my' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <WorkflowIcon size={14} />
            My Workflows
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {myTotal}
            </span>
          </button>
          <button
            onClick={() => handleTabChange('shared')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'shared' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'shared' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Star size={14} />
            Shared Workflows
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {sharedTotal}
            </span>
          </button>
        </div>
        <div className="relative w-64">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            type="text"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setMyOffset(0);
              setSharedOffset(0);
            }}
            placeholder="Search by name..."
            className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
          />
        </div>
      </div>

      {error && <div style={{ color: '#dc2626', marginBottom: 12 }}>{error}</div>}

      {currentLoading ? (
        <div style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : currentWorkflows.length === 0 ? (
        <div style={emptyState}>
          {activeTab === 'my'
            ? 'No workflows yet. Create one to get started, or import from a JSON file.'
            : keyword ? 'No shared workflows match your search.' : 'No shared workflows available yet.'}
        </div>
      ) : (
        <div style={grid}>
          {currentWorkflows.map((wf) => (
            <div key={wf.id} onClick={() => navigate(`/workflows/${wf.id}`)} style={card} title={activeTab === 'shared' ? 'Open read-only to run' : undefined}>
              {activeTab === 'my' ? (
                <>
                  <button onClick={(e) => exportWorkflow(e, wf)} style={exportBtn} title="Export workflow"><Download size={14} /></button>
                  <button onClick={(e) => openRuns(e, wf)} style={histBtn} title="Run history"><History size={14} /></button>
                  <button onClick={(e) => del(e, wf)} style={delBtn} title={wf.published_version ? 'Archive workflow' : 'Delete workflow'}><Trash2 size={14} /></button>
                </>
              ) : (
                <>
                  {isAdmin && <button onClick={(e) => del(e, wf)} style={adminDelBtn} title="Admin archive public workflow"><Trash2 size={14} /></button>}
                  <button onClick={(e) => cloneWorkflow(e, wf)} style={cloneBtn} disabled={cloningId === wf.id} title="Clone to my workflows">
                    <Copy size={14} /> {cloningId === wf.id ? 'Cloning...' : 'Clone'}
                  </button>
                </>
              )}
              <div style={{ fontWeight: 600, color: 'var(--color-text)', paddingRight: activeTab === 'my' ? 92 : 84 }}>{wf.name}</div>
              <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginTop: 4 }}>
                {wf.mode}
                {wf.status ? ` · ${statusLabel(wf)}` : ''}
                {activeTab === 'shared' && wf.user_name ? ` · by ${wf.user_name}` : ''}
              </div>
            </div>
          ))}
        </div>
      )}

      {currentTotal > 0 && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Showing {currentOffset + 1}-{Math.min(currentOffset + PAGE_SIZE, currentTotal)} of {currentTotal}
          </span>
          <div className="flex gap-2">
            <button onClick={() => setCurrentOffset(Math.max(0, currentOffset - PAGE_SIZE))} disabled={prevDisabled}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setCurrentOffset(currentOffset + PAGE_SIZE)} disabled={nextDisabled}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function statusLabel(wf: WorkflowView): string {
  if (wf.status === 'PUBLIC') return `Public${wf.published_version ? ` v${wf.published_version}` : ''}`;
  if (wf.status === 'ARCHIVED' || wf.status === 'DISABLED') return wf.status;
  return wf.published_version ? `Private · last public v${wf.published_version}` : 'Private';
}

const btnPrimary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px',
  background: 'var(--color-primary)', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer', fontWeight: 500,
};
const btnSecondary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px',
  background: 'transparent', color: 'var(--color-text)', border: '1px solid var(--color-border)',
  borderRadius: 8, cursor: 'pointer', fontWeight: 500,
};
const grid: CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12,
};
const emptyState: CSSProperties = {
  textAlign: 'center', padding: '48px 16px', border: '1px solid var(--color-border)', borderRadius: 8,
  background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)',
};
const card: CSSProperties = {
  position: 'relative', border: '1px solid var(--color-border)', borderRadius: 8, padding: 16, cursor: 'pointer', background: 'var(--color-bg-secondary)',
};
const delBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 10, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const histBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 38, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const exportBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 66, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const cloneBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 10, display: 'flex', alignItems: 'center', gap: 4,
  padding: '4px 8px', border: '1px solid var(--color-border)', borderRadius: 6,
  background: 'transparent', color: 'var(--color-text-secondary)', cursor: 'pointer', fontSize: 12,
};
const adminDelBtn: CSSProperties = {
  position: 'absolute', top: 10, right: 84, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 26, height: 26, border: 'none', borderRadius: 6, background: 'transparent', color: '#dc2626', cursor: 'pointer',
};
