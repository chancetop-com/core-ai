/**
 * @author stephen
 */
import { useState, useEffect, useCallback } from 'react';
import { X, ChevronDown, ChevronRight, Loader2, Check } from 'lucide-react';
import { api } from '../../api/client';
import type { ToolRegistryView, ApiAppView, ApiServiceView } from '../../api/client';

interface ToolPickerModalProps {
  availableTools: ToolRegistryView[];
  loading: boolean;
  loadedIds: Set<string>;
  pendingIds: Set<string>;
  selectedIds: Set<string>;
  onToggle: (id: string) => void;
  onLoad: () => void;
  onClose: () => void;
}

export default function ToolPickerModal({
  availableTools,
  loading,
  loadedIds,
  pendingIds,
  selectedIds,
  onToggle,
  onLoad,
  onClose,
}: ToolPickerModalProps) {
  const [apiApps, setApiApps] = useState<ApiAppView[]>([]);
  const [apiAppsLoaded, setApiAppsLoaded] = useState(false);
  const [expandedApp, setExpandedApp] = useState<string | null>(null);
  const [expandedService, setExpandedService] = useState<string | null>(null);
  const [appServices, setAppServices] = useState<Record<string, ApiServiceView[]>>({});
  const [showBuiltin, setShowBuiltin] = useState(false);
  const [showMcp, setShowMcp] = useState(false);
  const [showApi, setShowApi] = useState(false);

  const builtinTools = availableTools.filter(t => t.type === 'BUILTIN' && t.id !== 'builtin-service-api');
  const mcpTools = availableTools.filter(t => t.type === 'MCP');

  const builtinSelected = builtinTools.filter(t => selectedIds.has(t.id) || loadedIds.has(t.id) || pendingIds.has(t.id)).length;
  const mcpSelected = mcpTools.filter(t => selectedIds.has(t.id) || loadedIds.has(t.id) || pendingIds.has(t.id)).length;
  const apiSelected = Array.from(selectedIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length
    + Array.from(pendingIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length
    + Array.from(loadedIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length;

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  const loadApiApps = useCallback(async () => {
    if (apiAppsLoaded) return;
    try {
      const res = await api.tools.listApiApps();
      setApiApps(res.apps || []);
      setApiAppsLoaded(true);
    } catch (e) {
      console.error('Failed to load API apps:', e);
    }
  }, [apiAppsLoaded]);

  const loadAppServices = useCallback(async (appName: string) => {
    if (appServices[appName]) return;
    try {
      const res = await api.tools.listApiAppServices(appName);
      setAppServices(prev => ({ ...prev, [appName]: res.services || [] }));
    } catch (e) {
      console.error('Failed to load services:', e);
    }
  }, [appServices]);

  const toggleApp = (appName: string) => {
    if (expandedApp === appName) {
      setExpandedApp(null);
    } else {
      setExpandedApp(appName);
      loadAppServices(appName);
    }
  };

  const isIdActive = (id: string) => selectedIds.has(id) || loadedIds.has(id) || pendingIds.has(id);

  const getApiDisplayName = (id: string): string => {
    if (id.startsWith('api-app:')) return id.substring('api-app:'.length);
    if (id.startsWith('api-operation:')) {
      const parts = id.substring('api-operation:'.length).split(':');
      return `${parts[0]} > ${parts[1] || ''} > ${parts[2] || ''}`;
    }
    if (id.startsWith('api-service:')) {
      const parts = id.substring('api-service:'.length).split(':');
      return `${parts[0]} > ${parts[1] || ''}`;
    }
    return id;
  };

  const getItemState = (id: string): 'loaded' | 'pending' | 'selected' | 'none' => {
    if (loadedIds.has(id)) return 'loaded';
    if (pendingIds.has(id)) return 'pending';
    if (selectedIds.has(id)) return 'selected';
    return 'none';
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="rounded-2xl shadow-2xl flex flex-col overflow-hidden"
        style={{ width: 'min(600px, 90vw)', maxHeight: '80vh', background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
        onClick={e => e.stopPropagation()}>

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold">Load Tools</h2>
            {selectedIds.size > 0 && (
              <span className="text-[10px] px-1.5 py-0.5 rounded-full" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {selectedIds.size} selected
              </span>
            )}
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        {/* Selected tools summary */}
        {selectedIds.size > 0 && (
          <div className="px-5 py-3 border-b flex flex-wrap gap-1.5 max-h-[120px] overflow-auto" style={{ borderColor: 'var(--color-border)' }}>
            {Array.from(selectedIds).map(id => {
              const isApi = id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:');
              const tool = isApi ? null : availableTools.find(t => t.id === id);
              const name = isApi ? getApiDisplayName(id) : (tool?.name || id);
              const color = isApi ? '#10b981' : tool?.type === 'MCP' ? '#8b5cf6' : '#f59e0b';
              const isLoaded = loadedIds.has(id);
              return (
                <span key={id} className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px]"
                  style={{ background: `${color}10`, border: `1px solid ${color}30` }}>
                  <span style={{ color }}>{name}</span>
                  {isLoaded ? (
                    <span className="text-[9px]" style={{ color: 'var(--color-success)' }}>loaded</span>
                  ) : (
                    <button onClick={() => onToggle(id)} className="cursor-pointer rounded hover:opacity-70" style={{ color }}>
                      <X size={10} />
                    </button>
                  )}
                </span>
              );
            })}
          </div>
        )}

        {/* Body */}
        <div className="flex-1 overflow-auto px-5 py-3" style={{ maxHeight: '500px' }}>
          {loading ? (
            <div className="flex items-center justify-center py-12 gap-2" style={{ color: 'var(--color-text-secondary)' }}>
              <Loader2 size={18} className="animate-spin" /> <span className="text-sm">Loading...</span>
            </div>
          ) : (
            <div className="space-y-2">
              {/* Builtin */}
              <ToolGroupSection
                title="Builtin" color="#f59e0b" count={builtinSelected} total={builtinTools.length}
                open={showBuiltin} onToggleOpen={() => setShowBuiltin(!showBuiltin)}>
                {builtinTools.map(t => (
                  <ToolCheckItem key={t.id} name={t.name} description={t.description}
                    state={getItemState(t.id)} color="#f59e0b"
                    onToggle={() => !loadedIds.has(t.id) && onToggle(t.id)} />
                ))}
              </ToolGroupSection>

              {/* MCP */}
              <ToolGroupSection
                title="MCP" color="#8b5cf6" count={mcpSelected} total={mcpTools.length}
                open={showMcp} onToggleOpen={() => setShowMcp(!showMcp)}>
                {mcpTools.length === 0 ? (
                  <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No MCP tools available</div>
                ) : mcpTools.map(t => (
                  <ToolCheckItem key={t.id} name={t.name} description={t.description}
                    state={getItemState(t.id)} color="#8b5cf6"
                    onToggle={() => !loadedIds.has(t.id) && onToggle(t.id)} />
                ))}
              </ToolGroupSection>

              {/* Service API */}
              <ToolGroupSection
                title="Service API" color="#10b981" count={apiSelected} total={apiApps.length}
                open={showApi} onToggleOpen={() => { setShowApi(!showApi); if (!apiAppsLoaded) loadApiApps(); }}>
                {!apiAppsLoaded ? (
                  <div className="px-3 py-2 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                    <Loader2 size={12} className="animate-spin" /> Loading...
                  </div>
                ) : apiApps.length === 0 ? (
                  <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No API apps available</div>
                ) : (
                  apiApps.map(app => {
                    const appId = `api-app:${app.name}`;
                    const appActive = isIdActive(appId);
                    return (
                      <div key={app.name} className="border-t first:border-t-0" style={{ borderColor: 'var(--color-border)' }}>
                        <div className="flex items-center px-3 py-1.5">
                          <button className="flex-1 flex items-center gap-2 text-xs cursor-pointer text-left"
                            onClick={() => toggleApp(app.name)}>
                            {expandedApp === app.name ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            <span className="font-medium">{app.name}</span>
                            {app.version && <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>v{app.version}</span>}
                          </button>
                          <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer"
                            style={{ background: appActive ? 'var(--color-bg-tertiary)' : '#10b98120', color: appActive ? 'var(--color-text-secondary)' : '#10b981' }}
                            disabled={appActive}
                            onClick={() => onToggle(appId)}>
                            {appActive ? 'Added' : 'Add All'}
                          </button>
                        </div>
                        {expandedApp === app.name && (
                          <div className="pl-6 pb-1">
                            {!appServices[app.name] ? (
                              <div className="px-3 py-1 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                                <Loader2 size={10} className="animate-spin" /> Loading...
                              </div>
                            ) : appServices[app.name].map(svc => {
                              const svcId = `api-service:${app.name}:${svc.name}`;
                              const svcActive = isIdActive(svcId) || appActive;
                              const svcKey = `${app.name}:${svc.name}`;
                              const svcExpanded = expandedService === svcKey;
                              return (
                                <div key={svc.name}>
                                  <div className="flex items-center justify-between px-3 py-1 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                                    <button className="flex items-center gap-2 flex-1 min-w-0 cursor-pointer text-left"
                                      onClick={() => setExpandedService(svcExpanded ? null : svcKey)}>
                                      {svcExpanded ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                                      <span className="font-medium truncate">{svc.name}</span>
                                      <span className="text-[10px] flex-shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{svc.operation_count} ops</span>
                                    </button>
                                    <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                                      style={{ background: svcActive ? 'var(--color-bg-tertiary)' : '#10b98120', color: svcActive ? 'var(--color-text-secondary)' : '#10b981' }}
                                      disabled={svcActive}
                                      onClick={() => onToggle(svcId)}>
                                      {appActive ? 'App added' : isIdActive(svcId) ? 'Added' : 'Add All'}
                                    </button>
                                  </div>
                                  {svcExpanded && svc.operations && (
                                    <div className="pl-6 pb-0.5">
                                      {svc.operations.map(op => {
                                        const opId = `api-operation:${app.name}:${svc.name}:${op.name}`;
                                        const opActive = isIdActive(opId) || svcActive;
                                        return (
                                          <div key={op.name} className="flex items-center justify-between px-3 py-0.5 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                                            <div className="flex items-center gap-2 flex-1 min-w-0">
                                              {op.method && (
                                                <span className="text-[9px] font-mono px-1 rounded flex-shrink-0"
                                                  style={{
                                                    background: op.method === 'GET' ? '#3b82f615' : op.method === 'POST' ? '#10b98115' : op.method === 'PUT' ? '#f59e0b15' : '#ef444415',
                                                    color: op.method === 'GET' ? '#3b82f6' : op.method === 'POST' ? '#10b981' : op.method === 'PUT' ? '#f59e0b' : '#ef4444',
                                                  }}>{op.method}</span>
                                              )}
                                              <span className="truncate">{op.name}</span>
                                            </div>
                                            <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                                              style={{ background: opActive ? 'var(--color-bg-tertiary)' : '#10b98120', color: opActive ? 'var(--color-text-secondary)' : '#10b981' }}
                                              disabled={opActive}
                                              onClick={() => onToggle(opId)}>
                                              {opActive ? '✓' : 'Add'}
                                            </button>
                                          </div>
                                        );
                                      })}
                                    </div>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    );
                  })
                )}
              </ToolGroupSection>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 py-3 border-t"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {selectedIds.size > 0 ? `${selectedIds.size} selected` : 'Select tools to load'}
          </span>
          <div className="flex gap-2">
            <button onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm cursor-pointer"
              style={{ background: 'transparent', color: 'var(--color-text-secondary)', border: '1px solid var(--color-border)' }}>
              Cancel
            </button>
            <button onClick={onLoad} disabled={selectedIds.size === 0}
              className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              Load
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function ToolGroupSection({ title, color, count, total, open, onToggleOpen, children }: {
  title: string; color: string; count: number; total: number;
  open: boolean; onToggleOpen: () => void; children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
      <button className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-lg"
        onClick={onToggleOpen}>
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full" style={{ background: color }} />
          <span>{title}</span>
          {count > 0 && (
            <span className="text-[10px] px-1 rounded-full" style={{ background: `${color}20`, color }}>{count}</span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>{total} available</span>
          {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </div>
      </button>
      {open && (
        <div className="border-t max-h-[250px] overflow-auto" style={{ borderColor: 'var(--color-border)' }}>
          {children}
        </div>
      )}
    </div>
  );
}

function ToolCheckItem({ name, description, state, color, onToggle }: {
  name: string; description?: string; state: 'loaded' | 'pending' | 'selected' | 'none'; color: string; onToggle: () => void;
}) {
  const disabled = state === 'loaded';
  return (
    <label className={`flex items-center gap-2.5 px-3 py-1.5 text-xs ${disabled ? 'opacity-50' : 'cursor-pointer hover:bg-[var(--color-bg-tertiary)]'}`}
      style={{ borderBottom: '1px solid var(--color-border)' }}>
      {state === 'loaded' ? (
        <Check size={14} style={{ color: 'var(--color-success)' }} />
      ) : state === 'pending' ? (
        <Check size={14} style={{ color: 'var(--color-warning)' }} />
      ) : (
        <input type="checkbox" checked={state === 'selected'} onChange={onToggle}
          className="accent-current" style={{ accentColor: color }} />
      )}
      <span className="font-medium flex-1">{name}</span>
      {state === 'loaded' && <span className="text-[10px]" style={{ color: 'var(--color-success)' }}>loaded</span>}
      {state === 'pending' && <span className="text-[10px]" style={{ color: 'var(--color-warning)' }}>pending</span>}
      {description && <span className="text-[10px] truncate max-w-[180px]" style={{ color: 'var(--color-text-secondary)' }}>{description}</span>}
    </label>
  );
}

