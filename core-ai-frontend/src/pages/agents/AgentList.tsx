import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Bot, Download, FileUp, Check, ChevronLeft, ChevronRight, Search, Star, Sparkles, Pencil, Brain } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import { useAuth } from '../../api/auth';
import StatusBadge from '../../components/StatusBadge';

const EXPORT_FIELDS = ['name', 'description', 'type', 'system_prompt', 'model', 'temperature',
  'max_turns', 'timeout_seconds', 'tool_ids', 'input_template', 'variables', 'response_schema'] as const;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT_BY = 'updated_at';

function toExportData(a: AgentDefinition): Record<string, unknown> {
  const raw = a as unknown as Record<string, unknown>;
  const data: Record<string, unknown> = {};
  for (const key of EXPORT_FIELDS) {
    if (raw[key] != null) data[key] = raw[key];
  }
  return data;
}

type AgentTab = 'my' | 'other';

function pageFor(offset: number, limit: number) {
  return Math.floor(offset / limit) + 1;
}

function agentPageKey(tab: AgentTab, query: string, offset: number, limit: number, sortBy: string) {
  return `${tab}\u0000${query}\u0000${offset}\u0000${limit}\u0000${sortBy}`;
}

export default function AgentList() {
  const [myAgents, setMyAgents] = useState<AgentDefinition[]>([]);
  const [otherAgents, setOtherAgents] = useState<AgentDefinition[]>([]);
  const [myTotal, setMyTotal] = useState(0);
  const [otherTotal, setOtherTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pageLoading, setPageLoading] = useState(false);
  const [importing, setImporting] = useState(false);
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [activeTab, setActiveTab] = useState<'my' | 'other'>('my');
  const [myOffset, setMyOffset] = useState(0);
  const [otherOffset, setOtherOffset] = useState(0);
  const [limit, setLimit] = useState(DEFAULT_LIMIT);
  const [query, setQuery] = useState('');
  const [sortBy, setSortBy] = useState<'updated_at' | 'created_at'>(DEFAULT_SORT_BY);
  const navigate = useNavigate();
  const { user } = useAuth();
  const isAdmin = user?.role === 'admin';
  const fileRef = useRef<HTMLInputElement>(null);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reqIdRef = useRef<Record<AgentTab, number>>({ my: 0, other: 0 });
  const loadedKeysRef = useRef<Record<AgentTab, string | null>>({ my: null, other: null });
  const loadingKeysRef = useRef<Record<AgentTab, string | null>>({ my: null, other: null });

  const loadAgents = useCallback(async (tab: AgentTab, offset: number, search: string) => {
    const trimmed = search.trim();
    const key = agentPageKey(tab, trimmed, offset, limit, sortBy);
    if (loadedKeysRef.current[tab] === key || loadingKeysRef.current[tab] === key) return;
    const requestId = ++reqIdRef.current[tab];
    loadingKeysRef.current[tab] = key;
    setPageLoading(true);
    try {
      const res = await api.agents.list(tab === 'my', trimmed || undefined, limit, pageFor(offset, limit), sortBy, tab === 'my' && !isAdmin ? false : undefined);
      if (requestId !== reqIdRef.current[tab]) return;
      if (tab === 'my') {
        setMyAgents(res.agents || []);
        setMyTotal(res.total || 0);
      } else {
        setOtherAgents(res.agents || []);
        setOtherTotal(res.total || 0);
      }
      loadedKeysRef.current[tab] = key;
    } catch (err) {
      console.error('Failed to load agents:', err);
    } finally {
      if (loadingKeysRef.current[tab] === key) loadingKeysRef.current[tab] = null;
      if (requestId === reqIdRef.current[tab]) setPageLoading(false);
    }
  }, [limit, sortBy, isAdmin]);

  const loadMyAgents = () => {
    loadedKeysRef.current.my = null;
    void loadAgents('my', myOffset, query);
  };

  useEffect(() => {
    setLoading(true);
    const myKey = agentPageKey('my', '', 0, DEFAULT_LIMIT, DEFAULT_SORT_BY);
    const otherKey = agentPageKey('other', '', 0, DEFAULT_LIMIT, DEFAULT_SORT_BY);
    Promise.all([
      api.agents.list(true, undefined, DEFAULT_LIMIT, 1, DEFAULT_SORT_BY, isAdmin ? undefined : false),
      api.agents.list(false, undefined, DEFAULT_LIMIT, 1, DEFAULT_SORT_BY),
    ]).then(([myRes, otherRes]) => {
      setMyAgents(myRes.agents || []);
      setMyTotal(myRes.total || 0);
      setOtherAgents(otherRes.agents || []);
      setOtherTotal(otherRes.total || 0);
      loadedKeysRef.current.my = myKey;
      loadedKeysRef.current.other = otherKey;
    }).finally(() => setLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (loading) return;
    const offset = activeTab === 'my' ? myOffset : otherOffset;
    const trimmed = query.trim();
    const key = agentPageKey(activeTab, trimmed, offset, limit, sortBy);
    if (loadedKeysRef.current[activeTab] === key || loadingKeysRef.current[activeTab] === key) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => { void loadAgents(activeTab, offset, query); }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
  }, [activeTab, myOffset, otherOffset, query, limit, sortBy, loading, loadAgents]);

  useEffect(() => {
    setSelected(new Set());
  }, [activeTab, myOffset, otherOffset, query, limit, sortBy]);

  const currentAgents = activeTab === 'my' ? myAgents : otherAgents;
  const currentOffset = activeTab === 'my' ? myOffset : otherOffset;
  const setCurrentOffset = activeTab === 'my' ? setMyOffset : setOtherOffset;
  const currentTotal = activeTab === 'my' ? myTotal : otherTotal;
  const currentLoading = loading || pageLoading;
  const pagedAgents = currentAgents;

  const handleCreate = () => {
    setShowCreateDialog(true);
  };

  const toggleSelect = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === currentAgents.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(currentAgents.map(a => a.id)));
    }
  };

  const handleExport = () => {
    const toExport = currentAgents.filter(a => selected.has(a.id));
    if (toExport.length === 0) return;
    const exportData = toExport.map(toExportData);
    const json = JSON.stringify(exportData.length === 1 ? exportData[0] : exportData, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const fileName = toExport.length === 1
      ? `${toExport[0].name.replace(/\s+/g, '-').toLowerCase()}.agent.json`
      : `agents-export-${new Date().toISOString().slice(0, 10)}.json`;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
    setSelectMode(false);
    setSelected(new Set());
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImporting(true);
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      const items = Array.isArray(data) ? data : [data];
      const existingNames = new Set([...myAgents, ...otherAgents].map(a => a.name));
      let created = 0;
      for (const item of items) {
        if (!item.name) continue;
        let name = item.name;
        let suffix = 1;
        while (existingNames.has(name)) {
          name = `${item.name} (${suffix++})`;
        }
        existingNames.add(name);
        try {
          await api.agents.create({ ...item, name });
          created++;
        } catch (err) {
          console.error(`Failed to import agent "${name}":`, err);
        }
      }
      alert(`Imported ${created} of ${items.length} agent(s)`);
      loadMyAgents();
    } catch {
      alert('Invalid JSON file');
    } finally {
      setImporting(false);
      e.target.value = '';
    }
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const now = Date.now();
    const diff = now - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  const handleTabChange = (tab: 'my' | 'other') => {
    setActiveTab(tab);
    setSelectMode(false);
    setSelected(new Set());
    setQuery('');
    setMyOffset(0);
    setOtherOffset(0);
  };

  return (
    <>
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Agents</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create and manage your AI agents, or browse shared agents from others
          </p>
        </div>
        <div className="flex items-center gap-2">
          {activeTab === 'my' && selectMode ? (
            <>
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                {selected.size} selected
              </span>
              <button onClick={toggleSelectAll}
                className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                {selected.size === currentAgents.length ? 'Deselect All' : 'Select All'}
              </button>
              <button onClick={handleExport} disabled={selected.size === 0}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                <Download size={14} /> Export ({selected.size})
              </button>
              <button onClick={() => { setSelectMode(false); setSelected(new Set()); }}
                className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
            </>
          ) : (
            <>
              {activeTab === 'my' && myTotal > 0 && (
                <button onClick={() => setSelectMode(true)}
                  className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>
                  <Download size={14} /> Export
                </button>
              )}
              {activeTab === 'my' && (
                <label className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', opacity: importing ? 0.5 : 1 }}>
                  <FileUp size={14} /> {importing ? 'Importing...' : 'Import'}
                  <input ref={fileRef} type="file" accept=".json" className="hidden" onChange={handleImportFile} disabled={importing} />
                </label>
              )}
              {activeTab === 'my' && (
                <button onClick={handleCreate}
                  className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                  style={{ background: 'var(--color-primary)' }}>
                  <Plus size={16} /> New Agent
                </button>
              )}
            </>
          )}
        </div>
      </div>

      <div className="mb-4 flex items-center justify-between">
        <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--color-bg-secondary)' }}>
          <button
            onClick={() => handleTabChange('my')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'my' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'my' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Bot size={14} />
            My Agents
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {myTotal}
            </span>
          </button>
          <button
            onClick={() => handleTabChange('other')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'other' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'other' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Star size={14} />
            Shared Agents
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg)', color: 'var(--color-text-secondary)' }}>
              {otherTotal}
            </span>
          </button>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative w-64">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
              style={{ color: 'var(--color-text-secondary)' }} />
            <input type="text" value={query}
              onChange={e => { setQuery(e.target.value); setMyOffset(0); setOtherOffset(0); }}
              placeholder="Search by name or description..."
              className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
          </div>
          <select value={sortBy}
            onChange={e => { setSortBy(e.target.value as 'updated_at' | 'created_at'); setMyOffset(0); setOtherOffset(0); }}
            className="px-3 py-2 rounded-lg border text-sm outline-none cursor-pointer"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
            <option value="updated_at">Sort: Updated ↓</option>
            <option value="created_at">Sort: Created ↓</option>
          </select>
        </div>
      </div>

      <div className="grid gap-4">
        {currentLoading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : pagedAgents.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {activeTab === 'my'
              ? 'No agents yet. Click "New Agent" to create one, or import from a JSON file.'
              : 'No shared agents available yet.'}
          </div>
        ) : pagedAgents.map(a => (
          <div key={a.id}
            onClick={() => activeTab === 'my' && selectMode ? toggleSelect(a.id, { stopPropagation: () => {} } as React.MouseEvent) : navigate(`/agents/${a.id}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{
              background: selected.has(a.id) ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)',
              borderColor: selected.has(a.id) ? 'var(--color-primary)' : 'var(--color-border)',
            }}
            onMouseEnter={e => { if (!selected.has(a.id)) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
            onMouseLeave={e => { if (!selected.has(a.id)) e.currentTarget.style.background = selected.has(a.id) ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)'; }}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                {activeTab === 'my' && selectMode ? (
                  <div className="w-5 h-5 rounded border flex items-center justify-center flex-shrink-0"
                    style={{
                      borderColor: selected.has(a.id) ? 'var(--color-primary)' : 'var(--color-border)',
                      background: selected.has(a.id) ? 'var(--color-primary)' : 'transparent',
                    }}>
                    {selected.has(a.id) && <Check size={12} color="white" />}
                  </div>
                ) : (
                  <Bot size={18} style={{ color: 'var(--color-primary)' }} />
                )}
                <span className="font-medium">{a.name}</span>
                <StatusBadge status={a.status} />
                <span className="px-2 py-0.5 rounded text-xs"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {a.type}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={e => { e.stopPropagation(); navigate(`/agents/${a.id}/memories`); }}
                  className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs border cursor-pointer"
                  style={{ borderColor: 'var(--color-primary)', color: 'var(--color-primary)' }}
                  title="Agent Memory">
                  <Brain size={13} /> Memory
                </button>
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {formatTime(a.updated_at)}
                </span>
              </div>
            </div>
            {a.description && (
              <p className="text-sm mt-1 ml-8" style={{ color: 'var(--color-text-secondary)' }}>{a.description}</p>
            )}
            <div className="flex items-center gap-4 mt-2 ml-8 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {a.model && <span>Model: {a.model}</span>}
              {a.max_turns && <span>Max turns: {a.max_turns}</span>}
              {a.created_by && <span>By: {a.created_by}</span>}
            </div>
          </div>
        ))}
      </div>

      {currentTotal > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {currentOffset + 1}-{Math.min(currentOffset + limit, currentTotal)} of {currentTotal}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setMyOffset(0); setOtherOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setCurrentOffset(Math.max(0, currentOffset - limit))} disabled={currentOffset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setCurrentOffset(currentOffset + limit)} disabled={currentOffset + limit >= currentTotal}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
    {showCreateDialog && (
      <div className="fixed inset-0 z-50 flex items-center justify-center"
        style={{ background: 'rgba(0,0,0,0.5)' }}
        onClick={() => setShowCreateDialog(false)}>
        <div className="rounded-2xl shadow-2xl w-[480px] p-6"
          style={{ background: 'var(--color-bg)' }}
          onClick={e => e.stopPropagation()}>
          <h2 className="text-xl font-semibold mb-2">Create New Agent</h2>
          <p className="text-sm mb-6" style={{ color: 'var(--color-text-secondary)' }}>
            Choose how you want to create your agent
          </p>
          <div className="grid grid-cols-2 gap-4">
            <button
              onClick={() => { setShowCreateDialog(false); navigate('/agents/new'); }}
              className="flex flex-col items-center gap-3 p-5 rounded-xl border cursor-pointer transition-all hover:border-[var(--color-primary)] hover:shadow-md"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <div className="w-12 h-12 rounded-xl flex items-center justify-center"
                style={{ background: 'var(--color-bg-tertiary)' }}>
                <Pencil size={22} style={{ color: 'var(--color-primary)' }} />
              </div>
              <div className="text-center">
                <div className="font-medium text-sm">Manual</div>
                <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Configure all settings yourself in the editor
                </div>
              </div>
            </button>
            <button
              onClick={() => { setShowCreateDialog(false); navigate('/chat?agent=agent-builder&auto=help'); }}
              className="flex flex-col items-center gap-3 p-5 rounded-xl border cursor-pointer transition-all hover:border-[var(--color-primary)] hover:shadow-md"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <div className="w-12 h-12 rounded-xl flex items-center justify-center"
                style={{ background: 'var(--color-primary)' + '18' }}>
                <Sparkles size={22} style={{ color: 'var(--color-primary)' }} />
              </div>
              <div className="text-center">
                <div className="font-medium text-sm">Auto (AI-assisted)</div>
                <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Chat with Agent Builder to create your agent
                </div>
              </div>
            </button>
          </div>
          <button
            onClick={() => setShowCreateDialog(false)}
            className="w-full mt-4 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            Cancel
          </button>
        </div>
      </div>
    )}
  </>
  );
}
