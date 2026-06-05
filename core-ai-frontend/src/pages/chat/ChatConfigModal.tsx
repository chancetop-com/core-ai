import { useState, useEffect, useCallback } from 'react';
import { X, Search, Check, Loader2, Wrench, Sparkles, Users, Database, ChevronDown, ChevronRight, Plus, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { ToolRegistryView, SkillDefinition, AgentDefinition, DatasetView, ApiAppView, ApiServiceView, McpToolInfo, AgentDatasetConfig } from '../../api/client';

type TabKey = 'tools' | 'skills' | 'subagents' | 'dataset';

export interface DatasetConfigDraft extends AgentDatasetConfig {
  // extends AgentDatasetConfig with runtime-only key for React reconciliation
  _key: string;
}

interface ChatConfigModalProps {
  // Tools
  availableTools: ToolRegistryView[];
  toolsLoading: boolean;
  loadedToolIds: Set<string>;
  preToolIds: Set<string>;
  selectedToolIds: Set<string>;
  onToggleTool: (id: string) => void;
  onLoadTools: () => void;
  fetchTools: () => void;

  // Skills
  availableSkills: SkillDefinition[];
  skillsLoading: boolean;
  loadedSkillIds: Set<string>;
  preSkillIds: Set<string>;
  selectedSkillIds: Set<string>;
  onToggleSkill: (id: string) => void;
  onLoadSkills: () => void;

  // Subagents
  agents: AgentDefinition[];
  selectedAgentId: string;
  loadedSubAgentIds: Set<string>;
  preSubAgentIds: Set<string>;
  selectedAgentIds: Set<string>;
  onToggleAgent: (id: string) => void;
  onLoadAgents: () => void;

  // Datasets — full editor for session-level override
  draftDatasetConfigs: DatasetConfigDraft[];
  onUpdateDatasetConfigs: (configs: DatasetConfigDraft[]) => void;

  onClose: () => void;
}

const TABS: { key: TabKey; label: string; icon: React.ReactNode; color: string; count: number }[] = [
  { key: 'tools', label: 'Tools', icon: <Wrench size={16} />, color: '#f59e0b', count: 0 },
  { key: 'skills', label: 'Skills', icon: <Sparkles size={16} />, color: '#f59e0b', count: 0 },
  { key: 'subagents', label: 'Subagents', icon: <Users size={16} />, color: '#8b5cf6', count: 0 },
  { key: 'dataset', label: 'Dataset', icon: <Database size={16} />, color: '#3b82f6', count: 0 },
];

export default function ChatConfigModal(props: ChatConfigModalProps) {
  const { onClose } = props;
  const [activeTab, setActiveTab] = useState<TabKey>('tools');

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  const tabCounts: Record<TabKey, number> = {
    tools: props.loadedToolIds.size + props.preToolIds.size + props.selectedToolIds.size,
    skills: props.loadedSkillIds.size + props.preSkillIds.size + props.selectedSkillIds.size,
    subagents: props.loadedSubAgentIds.size + props.preSubAgentIds.size + props.selectedAgentIds.size,
    dataset: props.draftDatasetConfigs.length,
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="rounded-2xl shadow-2xl flex flex-col overflow-hidden"
        style={{ width: 'min(640px, 92vw)', maxHeight: '85vh', background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
        onClick={e => e.stopPropagation()}>

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="text-base font-semibold">Agent Configuration</h2>
          <button onClick={onClose} className="p-1.5 rounded-lg cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
          {TABS.map(tab => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className="flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium cursor-pointer transition-colors relative"
              style={{
                color: activeTab === tab.key ? 'var(--color-text)' : 'var(--color-text-secondary)',
                borderBottom: activeTab === tab.key ? `2px solid var(--color-primary)` : '2px solid transparent',
              }}>
              {tab.icon}
              <span>{tab.label}</span>
              {tabCounts[tab.key] > 0 && (
                <span className="text-[10px] px-1.5 py-0.5 rounded-full" style={{
                  background: activeTab === tab.key ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
                  color: activeTab === tab.key ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                }}>{tabCounts[tab.key]}</span>
              )}
            </button>
          ))}
        </div>

        {/* Tab Content */}
        <div className="flex-1 overflow-auto" style={{ maxHeight: '60vh' }}>
          {activeTab === 'tools' && <ToolsTab {...props} />}
          {activeTab === 'skills' && <SkillsTab {...props} />}
          {activeTab === 'subagents' && <SubAgentsTab {...props} />}
          {activeTab === 'dataset' && <DatasetTab {...props} />}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 py-3 border-t"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {tabCounts[activeTab] > 0 ? `${tabCounts[activeTab]} selected` : `Configure ${activeTab}`}
          </span>
          <div className="flex gap-2">
            <button onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm cursor-pointer"
              style={{ background: 'transparent', color: 'var(--color-text-secondary)', border: '1px solid var(--color-border)' }}>
              Done
            </button>
            {activeTab !== 'dataset' && (
              <button
                onClick={() => {
                  if (activeTab === 'tools') props.onLoadTools();
                  else if (activeTab === 'skills') props.onLoadSkills();
                  else if (activeTab === 'subagents') props.onLoadAgents();
                }}
                disabled={
                  (activeTab === 'tools' && props.selectedToolIds.size === 0) ||
                  (activeTab === 'skills' && props.selectedSkillIds.size === 0) ||
                  (activeTab === 'subagents' && props.selectedAgentIds.size === 0)
                }
                className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)', color: 'white' }}>
                Load
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ======================== Tools Tab ======================== */

function ToolsTab(props: ChatConfigModalProps) {
  const [showBuiltin, setShowBuiltin] = useState(false);
  const [showMcp, setShowMcp] = useState(false);
  const [showApi, setShowApi] = useState(false);
  const [apiApps, setApiApps] = useState<ApiAppView[]>([]);
  const [apiAppsLoaded, setApiAppsLoaded] = useState(false);
  const [expandedApp, setExpandedApp] = useState<string | null>(null);
  const [expandedService, setExpandedService] = useState<string | null>(null);
  const [appServices, setAppServices] = useState<Record<string, ApiServiceView[]>>({});
  const [mcpServerTools, setMcpServerTools] = useState<Record<string, McpToolInfo[]>>({});
  const [expandedMcpServer, setExpandedMcpServer] = useState<string | null>(null);

  const builtinTools = props.availableTools.filter(t => t.type === 'BUILTIN' && t.id !== 'builtin-service-api');
  const mcpTools = props.availableTools.filter(t => t.type === 'MCP');

  const isIdActive = (id: string) => props.selectedToolIds.has(id) || props.loadedToolIds.has(id) || props.preToolIds.has(id);

  const loadApiApps = useCallback(async () => {
    if (apiAppsLoaded) return;
    try {
      const res = await api.tools.listApiApps();
      setApiApps(res.apps || []);
      setApiAppsLoaded(true);
    } catch (e) { console.error('Failed to load API apps:', e); }
  }, [apiAppsLoaded]);

  const loadAppServices = useCallback(async (appName: string) => {
    if (appServices[appName]) return;
    try {
      const res = await api.tools.listApiAppServices(appName);
      setAppServices(prev => ({ ...prev, [appName]: res.services || [] }));
    } catch (e) { console.error('Failed to load services:', e); }
  }, [appServices]);

  const loadMcpServerTools = async (serverId: string) => {
    if (mcpServerTools[serverId]) return;
    try {
      const res = await api.tools.listMcpServerTools(serverId);
      setMcpServerTools(prev => ({ ...prev, [serverId]: res.tools || [] }));
    } catch (e) { console.error('Failed to load MCP server tools:', serverId, e); }
  };

  useEffect(() => { props.fetchTools(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (props.toolsLoading) {
    return (
      <div className="flex items-center justify-center py-12 gap-2" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={18} className="animate-spin" /> <span className="text-sm">Loading tools...</span>
      </div>
    );
  }

  return (
    <div className="p-4 space-y-2">
      {/* Builtin */}
      <ToolGroupSection title="Builtin" color="#f59e0b"
        count={builtinTools.filter(t => isIdActive(t.id)).length} total={builtinTools.length}
        open={showBuiltin} onToggle={() => setShowBuiltin(!showBuiltin)}>
        {builtinTools.map(t => (
          <ToolCheckItem key={t.id} name={t.name} description={t.description}
            state={props.loadedToolIds.has(t.id) ? 'loaded' : props.preToolIds.has(t.id) ? 'pending' : props.selectedToolIds.has(t.id) ? 'selected' : 'none'}
            color="#f59e0b"
            onToggle={() => !props.loadedToolIds.has(t.id) && props.onToggleTool(t.id)} />
        ))}
      </ToolGroupSection>

      {/* MCP */}
      <ToolGroupSection title="MCP" color="#8b5cf6"
        count={
          mcpTools.filter(t => isIdActive(t.id)).length +
          Array.from(props.selectedToolIds).filter(id => id.startsWith('mcp-tool:')).length +
          Array.from(props.loadedToolIds).filter(id => id.startsWith('mcp-tool:')).length +
          Array.from(props.preToolIds).filter(id => id.startsWith('mcp-tool:')).length
        }
        total={mcpTools.length}
        open={showMcp} onToggle={() => setShowMcp(!showMcp)}>
        {mcpTools.length === 0 ? (
          <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No MCP tools available</div>
        ) : mcpTools.map(server => {
          const serverActive = isIdActive(server.id);
          return (
            <div key={server.id} className="border-t first:border-t-0" style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex items-center px-3 py-1.5">
                <button className="flex-1 flex items-center gap-2 text-xs cursor-pointer text-left"
                  onClick={() => { setExpandedMcpServer(expandedMcpServer === server.id ? null : server.id); if (expandedMcpServer !== server.id) loadMcpServerTools(server.id); }}>
                  {expandedMcpServer === server.id ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                  <span className="font-medium truncate">{server.name}</span>
                </button>
                <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                  style={{ background: serverActive ? 'var(--color-bg-tertiary)' : '#8b5cf620', color: serverActive ? 'var(--color-text-secondary)' : '#8b5cf6' }}
                  disabled={serverActive}
                  onClick={() => props.onToggleTool(server.id)}>
                  {props.loadedToolIds.has(server.id) ? 'Loaded' : props.preToolIds.has(server.id) ? 'Pending' : serverActive ? 'Selected' : 'Add All'}
                </button>
              </div>
              {expandedMcpServer === server.id && (
                <div className="pl-6 pb-1">
                  {!mcpServerTools[server.id] ? (
                    <div className="px-3 py-1 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                      <Loader2 size={10} className="animate-spin" /> Loading...
                    </div>
                  ) : mcpServerTools[server.id].map(tool => {
                    const toolId = `mcp-tool:${server.id}:${tool.name}`;
                    const toolActive = isIdActive(toolId) || serverActive;
                    return (
                      <div key={tool.name} className="flex items-center justify-between px-3 py-0.5 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                        <span className="truncate flex-1">{tool.name}</span>
                        <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                          style={{ background: toolActive ? 'var(--color-bg-tertiary)' : '#8b5cf620', color: toolActive ? 'var(--color-text-secondary)' : '#8b5cf6' }}
                          disabled={toolActive}
                          onClick={() => props.onToggleTool(toolId)}>
                          {props.loadedToolIds.has(toolId) ? '✓' : props.preToolIds.has(toolId) ? 'Wait' : toolActive ? 'Added' : 'Add'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </ToolGroupSection>

      {/* Service API */}
      <ToolGroupSection title="Service API" color="#10b981"
        count={
          Array.from(props.selectedToolIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length +
          Array.from(props.loadedToolIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length +
          Array.from(props.preToolIds).filter(id => id.startsWith('api-app:') || id.startsWith('api-service:') || id.startsWith('api-operation:')).length
        }
        total={apiApps.length}
        open={showApi} onToggle={() => { setShowApi(!showApi); if (!apiAppsLoaded) loadApiApps(); }}>
        {!apiAppsLoaded ? (
          <div className="px-3 py-2 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 size={12} className="animate-spin" /> Loading...
          </div>
        ) : apiApps.length === 0 ? (
          <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No API apps available</div>
        ) : apiApps.map(app => {
          const appId = `api-app:${app.name}`;
          const appActive = isIdActive(appId);
          return (
            <div key={app.name} className="border-t first:border-t-0" style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex items-center px-3 py-1.5">
                <button className="flex-1 flex items-center gap-2 text-xs cursor-pointer text-left"
                  onClick={() => { setExpandedApp(expandedApp === app.name ? null : app.name); if (expandedApp !== app.name) loadAppServices(app.name); }}>
                  {expandedApp === app.name ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                  <span className="font-medium">{app.name}</span>
                  {app.version && <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>v{app.version}</span>}
                </button>
                <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer"
                  style={{ background: appActive ? 'var(--color-bg-tertiary)' : '#10b98120', color: appActive ? 'var(--color-text-secondary)' : '#10b981' }}
                  disabled={appActive}
                  onClick={() => props.onToggleTool(appId)}>
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
                    return (
                      <div key={svc.name}>
                        <div className="flex items-center justify-between px-3 py-1 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                          <button className="flex items-center gap-2 flex-1 min-w-0 cursor-pointer text-left"
                            onClick={() => setExpandedService(expandedService === svcKey ? null : svcKey)}>
                            {expandedService === svcKey ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                            <span className="font-medium truncate">{svc.name}</span>
                            <span className="text-[10px] flex-shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{svc.operation_count} ops</span>
                          </button>
                          <button className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                            style={{ background: svcActive ? 'var(--color-bg-tertiary)' : '#10b98120', color: svcActive ? 'var(--color-text-secondary)' : '#10b981' }}
                            disabled={svcActive}
                            onClick={() => props.onToggleTool(svcId)}>
                            {appActive ? 'App added' : isIdActive(svcId) ? 'Added' : 'Add All'}
                          </button>
                        </div>
                        {expandedService === svcKey && svc.operations && (
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
                                    onClick={() => props.onToggleTool(opId)}>
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
        })}
      </ToolGroupSection>
    </div>
  );
}

/* ======================== Skills Tab ======================== */

function SkillsTab(props: ChatConfigModalProps) {
  const [search, setSearch] = useState('');
  const [showAll, setShowAll] = useState(false);
  const MAX_VISIBLE = 5;

  const filtered = props.availableSkills.filter(s =>
    s.qualified_name.toLowerCase().includes(search.toLowerCase()) ||
    s.description.toLowerCase().includes(search.toLowerCase())
  );
  const visible = showAll ? filtered : filtered.slice(0, MAX_VISIBLE);

  useEffect(() => {
    if (props.availableSkills.length === 0 && !props.skillsLoading) {
      // trigger fetch via parent if needed
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (props.skillsLoading) {
    return (
      <div className="flex items-center justify-center py-12 gap-2" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={18} className="animate-spin" /> <span className="text-sm">Loading skills...</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <div className="px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg"
          style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
          <Search size={14} style={{ color: 'var(--color-text-muted)' }} />
          <input
            value={search}
            onChange={e => { setSearch(e.target.value); setShowAll(false); }}
            placeholder="Search skills..."
            className="flex-1 text-sm outline-none"
            style={{ background: 'transparent', color: 'var(--color-text)' }}
          />
        </div>
      </div>
      <div className="flex-1 overflow-auto p-4">
        {filtered.length === 0 ? (
          <div className="text-center py-12 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No skills found</div>
        ) : (
          <div className="flex flex-col gap-1">
            {visible.map(s => {
              const isLoaded = props.loadedSkillIds.has(s.id);
              const isPending = props.preSkillIds.has(s.id);
              const isSelected = props.selectedSkillIds.has(s.id);
              return (
                <button key={s.id}
                  onClick={() => !isLoaded && props.onToggleSkill(s.id)}
                  disabled={isLoaded}
                  className={`flex items-start gap-3 px-3 py-2.5 rounded-lg text-left transition-colors cursor-pointer ${isLoaded ? 'opacity-50 cursor-not-allowed' : 'hover:opacity-90'}`}
                  style={{
                    background: isSelected ? 'var(--color-primary)' + '18' : isPending ? 'var(--color-warning)' + '10' : isLoaded ? 'var(--color-success)' + '10' : 'transparent',
                    border: isSelected ? '1px solid var(--color-primary)' : isPending ? '1px dashed var(--color-warning)' : '1px solid transparent',
                  }}>
                  <div className="mt-0.5 shrink-0 w-4 flex items-center justify-center">
                    {isLoaded ? <span className="text-[10px] px-1 rounded" style={{ color: 'var(--color-success)', background: 'var(--color-success)' + '18' }}>✓</span>
                      : isPending ? <span className="text-[10px] px-1 rounded" style={{ color: 'var(--color-warning)', background: 'var(--color-warning)' + '18' }}>~</span>
                        : isSelected ? <Check size={16} style={{ color: 'var(--color-primary)' }} />
                          : <div className="w-4 h-4 rounded" style={{ border: '1.5px solid var(--color-border)' }} />}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>{s.qualified_name}</div>
                    {s.description && <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</div>}
                  </div>
                </button>
              );
            })}
            {!showAll && filtered.length > MAX_VISIBLE && (
              <button onClick={() => setShowAll(true)}
                className="text-xs py-2 cursor-pointer hover:opacity-80" style={{ color: 'var(--color-primary)' }}>
                Show all {filtered.length} skills...
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/* ======================== SubAgents Tab ======================== */

function SubAgentsTab(props: ChatConfigModalProps) {
  const [search, setSearch] = useState('');
  const [showAll, setShowAll] = useState(false);
  const MAX_VISIBLE = 5;

  const items = props.agents.filter(a => a.id !== props.selectedAgentId).map(a => ({
    id: a.id, name: a.name, description: a.description || '', type: a.type,
  }));

  const filtered = items.filter(item =>
    item.name.toLowerCase().includes(search.toLowerCase()) ||
    item.description.toLowerCase().includes(search.toLowerCase())
  );
  const visible = showAll ? filtered : filtered.slice(0, MAX_VISIBLE);

  return (
    <div className="flex flex-col h-full">
      <div className="px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 px-3 py-2 rounded-lg"
          style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
          <Search size={14} style={{ color: 'var(--color-text-muted)' }} />
          <input
            value={search}
            onChange={e => { setSearch(e.target.value); setShowAll(false); }}
            placeholder="Search agents..."
            className="flex-1 text-sm outline-none"
            style={{ background: 'transparent', color: 'var(--color-text)' }}
          />
        </div>
      </div>
      <div className="flex-1 overflow-auto p-4">
        {filtered.length === 0 ? (
          <div className="text-center py-12 text-sm" style={{ color: 'var(--color-text-secondary)' }}>No agents found</div>
        ) : (
          <div className="flex flex-col gap-1">
            {visible.map(item => {
              const isLoaded = props.loadedSubAgentIds.has(item.id);
              const isPending = props.preSubAgentIds.has(item.id);
              const isSelected = props.selectedAgentIds.has(item.id);
              return (
                <button key={item.id}
                  onClick={() => !isLoaded && props.onToggleAgent(item.id)}
                  disabled={isLoaded}
                  className={`flex items-start gap-3 px-3 py-2.5 rounded-lg text-left transition-colors cursor-pointer ${isLoaded ? 'opacity-50 cursor-not-allowed' : 'hover:opacity-90'}`}
                  style={{
                    background: isSelected ? '#8b5cf618' : isPending ? 'var(--color-warning)' + '10' : isLoaded ? 'var(--color-success)' + '10' : 'transparent',
                    border: isSelected ? '1px solid #8b5cf6' : isPending ? '1px dashed var(--color-warning)' : '1px solid transparent',
                  }}>
                  <div className="mt-0.5 shrink-0 w-4 flex items-center justify-center">
                    {isLoaded ? <span className="text-[10px] px-1 rounded" style={{ color: 'var(--color-success)', background: 'var(--color-success)' + '18' }}>✓</span>
                      : isPending ? <span className="text-[10px] px-1 rounded" style={{ color: 'var(--color-warning)', background: 'var(--color-warning)' + '18' }}>~</span>
                        : isSelected ? <Check size={16} style={{ color: '#8b5cf6' }} />
                          : <div className="w-4 h-4 rounded" style={{ border: '1.5px solid var(--color-border)' }} />}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium">{item.name}</div>
                    {item.description && <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }}>{item.description}</div>}
                  </div>
                </button>
              );
            })}
            {!showAll && filtered.length > MAX_VISIBLE && (
              <button onClick={() => setShowAll(true)}
                className="text-xs py-2 cursor-pointer hover:opacity-80" style={{ color: '#8b5cf6' }}>
                Show all {filtered.length} agents...
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/* ======================== Dataset Tab ======================== */

function DatasetTab(props: ChatConfigModalProps) {
  const [datasets, setDatasets] = useState<DatasetView[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [search, setSearch] = useState('');

  useEffect(() => {
    (async () => {
      try {
        const res = await api.datasets.list();
        setDatasets(res.datasets || []);
      } catch (e) { console.error('Failed to load datasets:', e); }
      finally { setLoading(false); }
    })();
  }, []);

  const configs = props.draftDatasetConfigs;
  const boundIds = new Set(configs.map(c => c.dataset_id));
  const availableDatasets = datasets.filter(d => !boundIds.has(d.id));
  const filteredAvailable = availableDatasets.filter(d =>
    d.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleAdd = (datasetId: string) => {
    if (!datasetId) return;
    const newEntry: DatasetConfigDraft = {
      _key: crypto.randomUUID(),
      dataset_id: datasetId,
      permission: 'READ',
      is_output: false,
    };
    props.onUpdateDatasetConfigs([...configs, newEntry]);
    setAdding(false);
    setSearch('');
  };

  const handleRemove = (key: string) => {
    props.onUpdateDatasetConfigs(configs.filter(c => c._key !== key));
  };

  const handlePermissionChange = (key: string, permission: 'READ' | 'WRITE' | 'FULL') => {
    props.onUpdateDatasetConfigs(configs.map(c => c._key === key ? { ...c, permission } : c));
  };

  const handleSetOutput = (key: string) => {
    props.onUpdateDatasetConfigs(configs.map(c => ({
      ...c,
      is_output: c._key === key,
    })));
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 gap-2" style={{ color: 'var(--color-text-secondary)' }}>
        <Loader2 size={18} className="animate-spin" /> <span className="text-sm">Loading datasets...</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full p-4">
      <div className="text-xs font-medium mb-3" style={{ color: 'var(--color-text-secondary)' }}>
        Session datasets {configs.length > 0 && `(${configs.length})`}
      </div>

      {configs.length > 0 && (
        <div className="space-y-1.5 mb-4">
          {configs.map(cfg => {
            const ds = datasets.find(d => d.id === cfg.dataset_id);
            const label = ds?.name || cfg.dataset_id;
            return (
              <div key={cfg._key} className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm"
                style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
                <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: 'var(--color-primary)' }} />
                <span className="font-medium flex-1 truncate" style={{ color: 'var(--color-text)' }}>{label}</span>
                <select
                  value={cfg.permission}
                  onChange={e => handlePermissionChange(cfg._key, e.target.value as 'READ' | 'WRITE' | 'FULL')}
                  className="text-xs px-1.5 py-0.5 rounded flex-shrink-0 cursor-pointer outline-none"
                  style={{
                    background: 'var(--color-bg-tertiary)',
                    color: 'var(--color-text)',
                    border: '1px solid var(--color-border)',
                  }}>
                  <option value="READ">READ</option>
                  <option value="WRITE">WRITE</option>
                  <option value="FULL">FULL</option>
                </select>
                {cfg.is_output ? (
                  <button onClick={() => handleSetOutput(cfg._key)}
                    className="text-xs px-1.5 py-0.5 rounded flex-shrink-0 cursor-pointer border-none"
                    style={{ background: 'var(--color-primary)', color: '#fff' }}>output</button>
                ) : cfg.permission !== 'READ' ? (
                  <button onClick={() => handleSetOutput(cfg._key)}
                    className="text-xs px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0 bg-transparent border-none"
                    style={{ color: 'var(--color-text-secondary)', border: '1px dashed var(--color-border)' }}>output</button>
                ) : (
                  <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-text-secondary)', opacity: 0.4 }}>—</span>
                )}
                <button onClick={() => handleRemove(cfg._key)}
                  className="flex-shrink-0 cursor-pointer p-0.5 rounded hover:opacity-70 bg-transparent border-none"
                  style={{ color: 'var(--color-text-muted)' }}>
                  <Trash2 size={14} />
                </button>
              </div>
            );
          })}
        </div>
      )}

      {!adding ? (
        <button
          onClick={() => setAdding(true)}
          className="flex items-center gap-1.5 text-xs cursor-pointer hover:opacity-80 px-3 py-2 rounded-lg border-dashed bg-transparent border-none"
          style={{ color: 'var(--color-text-secondary)', border: '1px dashed var(--color-border)' }}>
          <Plus size={14} />
          <span>Add dataset</span>
        </button>
      ) : (
        <div className="space-y-2">
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg"
            style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
            <Search size={14} style={{ color: 'var(--color-text-muted)' }} />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Filter datasets..."
              className="flex-1 text-sm outline-none"
              style={{ background: 'transparent', color: 'var(--color-text)' }}
            />
          </div>
          {filteredAvailable.length > 0 ? (
            <div className="max-h-40 overflow-auto space-y-1">
              {filteredAvailable.slice(0, 20).map(ds => (
                <button key={ds.id}
                  onClick={() => handleAdd(ds.id)}
                  className="w-full text-left px-3 py-2 rounded-lg text-sm cursor-pointer hover:opacity-80 bg-transparent border-none"
                  style={{ color: 'var(--color-text)', background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
                  <span className="font-medium">{ds.name}</span>
                  {ds.description && (
                    <span className="ml-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>{ds.description}</span>
                  )}
                </button>
              ))}
            </div>
          ) : (
            <div className="text-xs py-3 text-center" style={{ color: 'var(--color-text-muted)' }}>
              {availableDatasets.length === 0 ? 'All datasets already added.' : 'No datasets match your filter.'}
            </div>
          )}
          <button
            onClick={() => { setAdding(false); setSearch(''); }}
            className="text-xs cursor-pointer hover:opacity-80 bg-transparent border-none"
            style={{ color: 'var(--color-text-secondary)' }}>
            Cancel
          </button>
        </div>
      )}
    </div>
  );
}

/* ======================== Shared components ======================== */

function ToolGroupSection({ title, color, count, total, open, onToggle, children }: {
  title: string; color: string; count: number; total: number;
  open: boolean; onToggle: () => void; children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
      <button className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-lg"
        onClick={onToggle}>
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
        <div className="border-t max-h-[220px] overflow-auto" style={{ borderColor: 'var(--color-border)' }}>
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
      {description && <span className="text-[10px] truncate max-w-[160px]" style={{ color: 'var(--color-text-secondary)' }}>{description}</span>}
    </label>
  );
}
