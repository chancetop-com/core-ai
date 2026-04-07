import { useEffect, useState, useRef, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Upload, Play, Copy, Check, Code, Download, Maximize2, Minimize2, Square, Loader2, ChevronDown, ChevronRight, X, Wrench, Search } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, SystemPrompt, AgentRun, AgentRunDetail, ToolRegistryView } from '../../api/client';
import { sessionApi } from '../../api/session';
import StatusBadge from '../../components/StatusBadge';

export default function AgentEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [publishSuccess, setPublishSuccess] = useState(false);
  const [promptExpanded, setPromptExpanded] = useState(false);

  // system prompts for dropdown
  const [systemPrompts, setSystemPrompts] = useState<SystemPrompt[]>([]);

  // tools
  const [allTools, setAllTools] = useState<ToolRegistryView[]>([]);
  const [toolSearch, setToolSearch] = useState('');

  // test panel
  const [testInput, setTestInput] = useState('');
  const [testOutput, setTestOutput] = useState('');
  const [testing, setTesting] = useState(false);
  const testControllerRef = useRef<AbortController | null>(null);
  const testOutputRef = useRef('');

  // runs
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null);
  const [expandedRunDetail, setExpandedRunDetail] = useState<AgentRunDetail | null>(null);
  const [modalRunDetail, setModalRunDetail] = useState<AgentRunDetail | null>(null);

  useEffect(() => {
    if (!id) return;
    api.agents.get(id).then(setAgent).catch(console.error).finally(() => setLoading(false));
    api.systemPrompts.list(0, 100).then(setSystemPrompts).catch(console.error);
    api.tools.list().then(res => setAllTools(res.tools || [])).catch(console.error);
    setRunsLoading(true);
    api.agents.runs(id).then(res => setRuns(res.runs || [])).catch(console.error).finally(() => setRunsLoading(false));
  }, [id]);

  const loadRuns = useCallback(async () => {
    if (!id) return;
    setRunsLoading(true);
    try {
      const res = await api.agents.runs(id);
      setRuns(res.runs || []);
    } finally {
      setRunsLoading(false);
    }
  }, [id]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!agent) return <div className="p-6">Agent not found</div>;

  const update = (field: string, value: unknown) => {
    setAgent({ ...agent, [field]: value } as AgentDefinition);
  };

  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    setSaveError('');
    try {
      const updated = await api.agents.update(id, {
        name: agent.name,
        description: agent.description,
        type: agent.type,
        system_prompt: agent.system_prompt,
        system_prompt_id: agent.system_prompt_id,
        model: agent.model,
        temperature: agent.temperature,
        max_turns: agent.max_turns,
        timeout_seconds: agent.timeout_seconds,
        tool_ids: agent.tool_ids,
        input_template: agent.input_template,
        variables: agent.variables,
        response_schema: agent.response_schema && !Array.isArray(agent.response_schema) ? [agent.response_schema] : agent.response_schema,
      });
      setAgent(updated);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!id) return;
    setPublishing(true);
    setPublishSuccess(false);
    try {
      const published = await api.agents.publish(id);
      setAgent(published);
      setPublishSuccess(true);
      setTimeout(() => setPublishSuccess(false), 3000);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Publish failed');
    } finally {
      setPublishing(false);
    }
  };

  const handleDelete = async () => {
    if (!id || !confirm('Delete this agent?')) return;
    await api.agents.delete(id);
    navigate('/agents');
  };

  const handleTest = async () => {
    if (!testInput.trim()) return;
    setTesting(true);
    setTestOutput('');
    testOutputRef.current = '';

    try {
      // Resolve system prompt
      let systemPrompt = agent.system_prompt || '';
      if (agent.system_prompt_id) {
        const sp = systemPrompts.find(s => s.promptId === agent.system_prompt_id);
        if (sp) systemPrompt = sp.content;
      }

      const config: Record<string, unknown> = { systemPrompt };
      if (agent.model) config.model = agent.model;
      if (agent.temperature != null) config.temperature = agent.temperature;
      if (agent.max_turns) config.maxTurns = agent.max_turns;

      const res = await sessionApi.create('', config);
      const sid = res.sessionId;

      const controller = sessionApi.connectSSE(sid, (event) => {
        try {
          const data = JSON.parse(event.data);
          if (event.type === 'text_chunk') {
            const chunk = data.text || data.chunk || '';
            testOutputRef.current += chunk;
            setTestOutput(testOutputRef.current);
          } else if (event.type === 'turn_complete') {
            setTesting(false);
            sessionApi.close(sid).catch(() => {});
          } else if (event.type === 'error') {
            testOutputRef.current += `\nError: ${data.message || data.error}`;
            setTestOutput(testOutputRef.current);
            setTesting(false);
            sessionApi.close(sid).catch(() => {});
          }
        } catch { /* ignore */ }
      }, () => { setTesting(false); });
      testControllerRef.current = controller;

      setTimeout(() => {
        sessionApi.sendMessage(sid, testInput).catch(err => {
          setTestOutput(`Error: ${err}`);
          setTesting(false);
        });
      }, 500);
    } catch (e) {
      setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      setTesting(false);
    }
  };

  const handleStopTest = () => {
    testControllerRef.current?.abort();
    testControllerRef.current = null;
    setTesting(false);
  };

  const handleTriggerRun = async () => {
    if (!id || !testInput.trim()) return;
    setTesting(true);
    setTestOutput('');
    try {
      const res = await api.agents.trigger(id, testInput);
      setTestOutput(`Run triggered: ${res.run_id}\nWaiting for result...`);
      // Poll for result
      const poll = async (attempts: number) => {
        if (attempts <= 0) { setTesting(false); return; }
        await new Promise(r => setTimeout(r, 2000));
        try {
          const detail = await api.agents.getRun(res.run_id);
          if (detail.status === 'COMPLETED' || detail.status === 'FAILED' || detail.status === 'ERROR') {
            setTestOutput(detail.output || detail.error || 'No output');
            setTesting(false);
            loadRuns();
          } else {
            poll(attempts - 1);
          }
        } catch { poll(attempts - 1); }
      };
      poll(30);
    } catch (e) {
      setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      setTesting(false);
    }
  };

  const toggleRunDetail = async (runId: string) => {
    if (expandedRunId === runId) {
      setExpandedRunId(null);
      setExpandedRunDetail(null);
      return;
    }
    setExpandedRunId(runId);
    setExpandedRunDetail(null);
    try {
      const detail = await api.agents.getRun(runId);
      setExpandedRunDetail(detail);
    } catch (e) {
      console.error('Failed to load run detail:', e);
    }
  };

  const openRunModal = async (runId: string) => {
    try {
      const detail = await api.agents.getRun(runId);
      setModalRunDetail(detail);
    } catch (e) {
      console.error('Failed to load run detail:', e);
    }
  };

  const handleExport = (a: AgentDefinition) => {
    const exportData = {
      name: a.name, description: a.description, type: a.type,
      system_prompt: a.system_prompt, model: a.model, temperature: a.temperature,
      max_turns: a.max_turns, timeout_seconds: a.timeout_seconds, tool_ids: a.tool_ids,
      input_template: a.input_template, variables: a.variables, response_schema: a.response_schema,
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${a.name.replace(/\s+/g, '-').toLowerCase()}.agent.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <button onClick={() => navigate('/agents')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Agents
      </button>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-semibold">{agent.name}</h1>
          <StatusBadge status={agent.status} />
          <span className="px-2 py-0.5 rounded text-xs"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            {agent.type}
          </span>
        </div>
        <div className="flex gap-2">
          <button onClick={handleDelete}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
            <Trash2 size={14} /> Delete
          </button>
          <button onClick={() => handleExport(agent)}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <Download size={14} /> Export
          </button>
          <button onClick={handlePublish} disabled={publishing}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
            style={{
              borderColor: publishSuccess ? 'var(--color-success)' : 'var(--color-border)',
              color: publishSuccess ? 'var(--color-success)' : undefined,
            }}>
            {publishSuccess ? <><Check size={14} /> Published!</> : <><Upload size={14} /> {publishing ? 'Publishing...' : 'Publish'}</>}
          </button>
          <button onClick={handleSave} disabled={saving}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {saveError && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
          {saveError}
        </div>
      )}

      <div className="grid grid-cols-3 gap-6">
        {/* Left: config */}
        <div className="col-span-2 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium mb-1">Name</label>
              <input value={agent.name || ''} onChange={e => update('name', e.target.value)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle} />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Type</label>
              <select value={agent.type || 'AGENT'} onChange={e => update('type', e.target.value)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle}>
                <option value="AGENT">AGENT</option>
                <option value="LLM_CALL">LLM_CALL</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">Description</label>
            <input value={agent.description || ''} onChange={e => update('description', e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>

          {/* System Prompt Selection */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">System Prompt</h3>
            <div className="space-y-3">
              <div>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Select from System Prompts
                </label>
                <select value={agent.system_prompt_id || ''}
                  onChange={e => {
                    const promptId = e.target.value || null;
                    setAgent(prev => prev ? { ...prev, system_prompt_id: promptId, system_prompt: promptId ? null : prev.system_prompt } as AgentDefinition : prev);
                  }}
                  className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                  style={inputStyle}>
                  <option value="">-- None (use inline prompt) --</option>
                  {systemPrompts.map(sp => (
                    <option key={sp.promptId} value={sp.promptId}>
                      {sp.name} (v{sp.version})
                    </option>
                  ))}
                </select>
                {agent.system_prompt_id && (() => {
                  const sp = systemPrompts.find(s => s.promptId === agent.system_prompt_id);
                  return sp ? (
                    <div className="mt-2">
                      <p className="text-xs mb-1" style={{ color: 'var(--color-success)' }}>
                        Using managed prompt: {sp.name} (v{sp.version})
                      </p>
                      <pre className="text-xs p-3 rounded-lg overflow-auto max-h-48 whitespace-pre-wrap"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {sp.content}
                      </pre>
                    </div>
                  ) : null;
                })()}
              </div>

              {!agent.system_prompt_id && (
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="block text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      Inline System Prompt
                    </label>
                    <button onClick={() => setPromptExpanded(!promptExpanded)}
                      className="text-xs px-2 py-0.5 rounded cursor-pointer flex items-center gap-1"
                      style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                      {promptExpanded ? <><Minimize2 size={12} /> Collapse</> : <><Maximize2 size={12} /> Expand</>}
                    </button>
                  </div>
                  <textarea value={agent.system_prompt || ''}
                    onChange={e => update('system_prompt', e.target.value)}
                    rows={promptExpanded ? 30 : 8}
                    className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                    style={inputStyle}
                    placeholder="Enter system prompt directly..." />
                </div>
              )}
            </div>
          </div>

          {/* Model & Parameters */}
          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-medium mb-1">Model</label>
              <input value={agent.model || ''} onChange={e => update('model', e.target.value)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle}
                placeholder="e.g. gpt-4" />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Temperature</label>
              <input type="number" step="0.1" min="0" max="2"
                value={agent.temperature ?? ''} onChange={e => update('temperature', e.target.value ? parseFloat(e.target.value) : null)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle}
                placeholder="0.7" />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Max Turns</label>
              <input type="number" min="1"
                value={agent.max_turns ?? ''} onChange={e => update('max_turns', e.target.value ? parseInt(e.target.value) : null)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle}
                placeholder="20" />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">Input Template</label>
            <textarea value={agent.input_template || ''}
              onChange={e => update('input_template', e.target.value)}
              rows={4}
              className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
              style={inputStyle}
              placeholder="Input template with {{variable}} placeholders..." />
          </div>

          {/* Response Schema */}
          <ResponseSchemaEditor
            value={agent.response_schema}
            onChange={v => update('response_schema', v)}
            inputStyle={inputStyle}
          />

          {/* Tools */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Wrench size={16} style={{ color: '#f59e0b' }} /> Tools
            </h3>

            {/* Selected tools */}
            {agent.tool_ids && agent.tool_ids.length > 0 ? (
              <div className="flex flex-wrap gap-2 mb-3">
                {agent.tool_ids.map(tid => {
                  const tool = allTools.find(t => t.id === tid);
                  return (
                    <span key={tid}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
                      <span className="w-1.5 h-1.5 rounded-full"
                        style={{ background: tool?.type === 'MCP' ? '#8b5cf6' : '#f59e0b' }} />
                      <span className="font-medium">{tool?.name || tid}</span>
                      {tool?.type && (
                        <span className="text-[10px] px-1 rounded"
                          style={{ background: tool.type === 'MCP' ? '#8b5cf615' : '#f59e0b15',
                            color: tool.type === 'MCP' ? '#8b5cf6' : '#f59e0b' }}>
                          {tool.type}
                        </span>
                      )}
                      <button onClick={() => update('tool_ids', agent.tool_ids.filter((i: string) => i !== tid))}
                        className="cursor-pointer ml-0.5 rounded hover:bg-[var(--color-bg-tertiary)]"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        <X size={12} />
                      </button>
                    </span>
                  );
                })}
              </div>
            ) : (
              <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>No tools selected</p>
            )}

            {/* Add tools */}
            <div className="relative">
              <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border text-sm"
                style={inputStyle}>
                <Search size={14} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />
                <input value={toolSearch} onChange={e => setToolSearch(e.target.value)}
                  className="flex-1 bg-transparent outline-none text-sm"
                  placeholder="Search tools to add..." />
              </div>
              {toolSearch && (
                <div className="absolute z-10 mt-1 w-full rounded-lg border shadow-lg overflow-auto"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', maxHeight: '200px' }}>
                  {allTools
                    .filter(t => !agent.tool_ids?.includes(t.id))
                    .filter(t => t.name.toLowerCase().includes(toolSearch.toLowerCase()) || t.type?.toLowerCase().includes(toolSearch.toLowerCase()))
                    .map(t => (
                      <button key={t.id}
                        onClick={() => { update('tool_ids', [...(agent.tool_ids || []), t.id]); setToolSearch(''); }}
                        className="w-full px-3 py-2 text-left text-xs flex items-center gap-2 cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                        <span className="w-1.5 h-1.5 rounded-full flex-shrink-0"
                          style={{ background: t.type === 'MCP' ? '#8b5cf6' : '#f59e0b' }} />
                        <span className="font-medium">{t.name}</span>
                        <span className="text-[10px] px-1 rounded ml-auto"
                          style={{ background: t.type === 'MCP' ? '#8b5cf615' : '#f59e0b15',
                            color: t.type === 'MCP' ? '#8b5cf6' : '#f59e0b' }}>
                          {t.type}
                        </span>
                        {t.category && (
                          <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>{t.category}</span>
                        )}
                      </button>
                    ))}
                  {allTools.filter(t => !agent.tool_ids?.includes(t.id))
                    .filter(t => t.name.toLowerCase().includes(toolSearch.toLowerCase())).length === 0 && (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No matching tools</div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Right: info + test + runs */}
        <div className="space-y-4">
          {/* Info */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Info</h3>
            <dl className="text-xs space-y-2" style={{ color: 'var(--color-text-secondary)' }}>
              <div className="flex justify-between"><dt>ID</dt><dd className="font-mono truncate ml-2 max-w-32">{agent.id}</dd></div>
              <div className="flex justify-between"><dt>Status</dt><dd>{agent.status}</dd></div>
              <div className="flex justify-between"><dt>Created by</dt><dd>{agent.created_by || '-'}</dd></div>
              <div className="flex justify-between"><dt>Timeout</dt><dd>{agent.timeout_seconds || 600}s</dd></div>
              <div className="flex justify-between"><dt>Published</dt><dd>{agent.published_at ? new Date(agent.published_at).toLocaleString() : '-'}</dd></div>
            </dl>
          </div>

          {/* Test Panel - always available */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Play size={16} style={{ color: 'var(--color-success)' }} /> Test
            </h3>
            <div className="space-y-3">
              <textarea value={testInput} onChange={e => setTestInput(e.target.value)}
                rows={3}
                className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none resize-y"
                style={inputStyle}
                placeholder="Enter test input..." />
              <div className="flex gap-2">
                {!testing ? (
                  <>
                    <button onClick={handleTest} disabled={!testInput.trim()}
                      className="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                      style={{ background: 'var(--color-success)' }}>
                      <Play size={14} /> Test
                    </button>
                    {agent.status === 'PUBLISHED' && (
                      <button onClick={handleTriggerRun} disabled={!testInput.trim()}
                        className="flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50 border"
                        style={{ borderColor: 'var(--color-border)' }}>
                        Run
                      </button>
                    )}
                  </>
                ) : (
                  <button onClick={handleStopTest}
                    className="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                    style={{ background: 'var(--color-error)' }}>
                    <Square size={14} /> Stop
                  </button>
                )}
              </div>
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Test uses current config directly. Run uses published config.
              </p>

              {/* Test output */}
              {(testOutput || testing) && (
                <div className="rounded-lg p-3 text-sm overflow-auto"
                  style={{ background: 'var(--color-bg-tertiary)', maxHeight: '400px' }}>
                  <pre className="whitespace-pre-wrap text-sm">
                    {testOutput || ''}
                    {testing && !testOutput && <Loader2 size={14} className="animate-spin inline" />}
                  </pre>
                  {testing && testOutput && (
                    <span className="inline-block w-1.5 h-4 ml-0.5 animate-pulse" style={{ background: 'var(--color-primary)' }} />
                  )}
                </div>
              )}
            </div>
          </div>

          {/* API Usage - collapsible */}
          {agent.status === 'PUBLISHED' && (
            <CollapsibleApiUsage agentId={agent.id} />
          )}

          {/* Recent Runs - inline expandable */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-medium text-sm">Recent Runs</h3>
              <button onClick={loadRuns}
                className="text-xs px-2 py-1 rounded cursor-pointer"
                style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                {runsLoading ? 'Loading...' : 'Refresh'}
              </button>
            </div>
            {runs.length === 0 ? (
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No runs yet.</p>
            ) : (
              <div className="space-y-2 max-h-[500px] overflow-y-auto">
                {runs.map(r => (
                  <div key={r.id} className="rounded-lg border text-xs"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <button className="w-full p-2 text-left cursor-pointer flex items-center justify-between"
                      onClick={() => toggleRunDetail(r.id)}>
                      <div className="flex items-center gap-2">
                        {expandedRunId === r.id ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        <StatusBadge status={r.status} />
                        <span className="truncate max-w-32" style={{ color: 'var(--color-text-secondary)' }}>
                          {r.input || '-'}
                        </span>
                      </div>
                      <span style={{ color: 'var(--color-text-secondary)' }}>
                        {r.started_at ? new Date(r.started_at).toLocaleTimeString() : '-'}
                      </span>
                    </button>
                    {expandedRunId === r.id && (
                      <RunInlineDetail run={r} detail={expandedRunDetail} onViewFull={() => openRunModal(r.id)} />
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Run Detail Modal */}
      {modalRunDetail && (
        <RunDetailModal detail={modalRunDetail} onClose={() => setModalRunDetail(null)} />
      )}
    </div>
  );
}

function RunDetailModal({ detail, onClose }: { detail: AgentRunDetail; onClose: () => void }) {
  const [copied, setCopied] = useState('');
  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  const duration = detail.started_at && detail.completed_at
    ? (new Date(detail.completed_at).getTime() - new Date(detail.started_at).getTime()) / 1000
    : null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="rounded-xl border shadow-2xl w-[90vw] max-w-4xl max-h-[85vh] overflow-hidden flex flex-col"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold">Run Detail</h2>
            <StatusBadge status={detail.status} />
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {detail.started_at ? new Date(detail.started_at).toLocaleString() : ''}
            </span>
          </div>
          <div className="flex items-center gap-4">
            <div className="flex gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {duration !== null && <span>{duration < 1 ? `${(duration * 1000).toFixed(0)}ms` : `${duration.toFixed(1)}s`}</span>}
              <span>{(detail.token_usage?.input || 0) + (detail.token_usage?.output || 0)} tokens</span>
            </div>
            <button onClick={onClose} className="cursor-pointer p-1 rounded hover:bg-[var(--color-bg-tertiary)]">
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto p-6 space-y-4">
          {/* I/O */}
          <div className="grid grid-cols-2 gap-4">
            {detail.input && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">Input</span>
                  <button onClick={() => handleCopy(detail.input, 'in')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'in' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg overflow-auto max-h-40"
                  style={{ background: 'var(--color-bg-tertiary)' }}>{detail.input}</pre>
              </div>
            )}
            {detail.output && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">Output</span>
                  <button onClick={() => handleCopy(detail.output, 'out')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'out' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg overflow-auto max-h-40"
                  style={{ background: 'var(--color-bg-tertiary)' }}>{detail.output}</pre>
              </div>
            )}
          </div>
          {detail.error && (
            <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
              {detail.error}
            </pre>
          )}

          {/* Transcript */}
          {detail.transcript && detail.transcript.length > 0 && (
            <div>
              <h3 className="text-sm font-medium mb-2">Transcript ({detail.transcript.length})</h3>
              <div className="space-y-2">
                {detail.transcript.map((entry, i) => (
                  <div key={i} className="p-3 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-xs font-medium px-1.5 py-0.5 rounded"
                        style={{
                          background: entry.role === 'user' ? 'rgba(59,130,246,0.1)' : entry.role === 'assistant' ? 'rgba(34,197,94,0.1)' : entry.role === 'tool' ? 'rgba(245,158,11,0.1)' : 'rgba(139,92,246,0.1)',
                          color: entry.role === 'user' ? '#3b82f6' : entry.role === 'assistant' ? '#22c55e' : entry.role === 'tool' ? '#f59e0b' : '#8b5cf6',
                        }}>
                        {entry.role}
                      </span>
                      {entry.name && <span className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>{entry.name}</span>}
                      {entry.ts && <span className="text-xs ml-auto" style={{ color: 'var(--color-text-secondary)' }}>{new Date(entry.ts).toLocaleTimeString()}</span>}
                    </div>
                    {entry.content && (
                      <pre className="text-xs whitespace-pre-wrap mt-1" style={{ color: 'var(--color-text)' }}>{entry.content}</pre>
                    )}
                    {entry.args && (
                      <pre className="text-xs whitespace-pre-wrap mt-1 p-2 rounded overflow-auto max-h-24"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {tryFormatJson(entry.args)}
                      </pre>
                    )}
                    {entry.result && (
                      <pre className="text-xs whitespace-pre-wrap mt-1 p-2 rounded overflow-auto max-h-24"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {tryFormatJson(entry.result)}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function tryFormatJson(str: string): string {
  try { return JSON.stringify(JSON.parse(str), null, 2); } catch { return str; }
}

function RunInlineDetail({ run, detail, onViewFull }: {
  run: AgentRun;
  detail: AgentRunDetail | null;
  onViewFull: () => void;
}) {
  const [copied, setCopied] = useState('');
  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  return (
    <div className="border-t p-3 space-y-2" style={{ borderColor: 'var(--color-border)' }}>
      {run.input && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Input</span>
            <button onClick={() => handleCopy(run.input, 'in')}
              className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
              style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
              {copied === 'in' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
            </button>
          </div>
          <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-24"
            style={{ background: 'var(--color-bg-tertiary)' }}>{run.input}</pre>
        </div>
      )}
      {run.output && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Output</span>
            <button onClick={() => handleCopy(run.output, 'out')}
              className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
              style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
              {copied === 'out' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
            </button>
          </div>
          <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-48"
            style={{ background: 'var(--color-bg-tertiary)' }}>{run.output}</pre>
        </div>
      )}
      {run.error && (
        <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg" style={{ color: 'var(--color-error)' }}>
          {run.error}
        </pre>
      )}
      {detail && (
        <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          <span>Tokens: {(detail.token_usage?.input || 0) + (detail.token_usage?.output || 0)}</span>
          {detail.started_at && detail.completed_at && (
            <span className="ml-3">
              Duration: {((new Date(detail.completed_at).getTime() - new Date(detail.started_at).getTime()) / 1000).toFixed(1)}s
            </span>
          )}
        </div>
      )}
      <button onClick={onViewFull}
        className="text-xs px-2 py-1 rounded cursor-pointer"
        style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
        View Full Detail →
      </button>
    </div>
  );
}

interface SchemaConstraints {
  notNull?: boolean;
  notBlank?: boolean;
  min?: number | null;
  max?: number | null;
  sizeMin?: number | null;
  sizeMax?: number | null;
  pattern?: string;
}

interface SchemaField {
  name: string;
  type: string;
  description: string;
  constraints: SchemaConstraints;
}

const FIELD_TYPES = ['String', 'Integer', 'Long', 'Double', 'Boolean', 'List', 'Map', 'Object'];

function parseConstraints(c: Record<string, unknown> | undefined): SchemaConstraints {
  if (!c) return {};
  const size = c.size as Record<string, unknown> | undefined;
  return {
    notNull: c.notNull as boolean | undefined,
    notBlank: c.notBlank as boolean | undefined,
    min: c.min as number | null | undefined,
    max: c.max as number | null | undefined,
    sizeMin: size?.min as number | null | undefined,
    sizeMax: size?.max as number | null | undefined,
    pattern: c.pattern as string | undefined,
  };
}

function buildConstraintsJson(c: SchemaConstraints): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  if (c.notNull) result.notNull = true;
  if (c.notBlank) result.notBlank = true;
  if (c.min != null) result.min = c.min;
  if (c.max != null) result.max = c.max;
  if (c.sizeMin != null || c.sizeMax != null) {
    const size: Record<string, unknown> = {};
    if (c.sizeMin != null) size.min = c.sizeMin;
    if (c.sizeMax != null) size.max = c.sizeMax;
    result.size = size;
  }
  if (c.pattern) result.pattern = c.pattern;
  return result;
}

function parseSchemaToForm(value: unknown): { typeName: string; fields: SchemaField[] } {
  if (!value) return { typeName: '', fields: [] };
  try {
    const arr = Array.isArray(value) ? value : [value];
    const first = arr[0];
    if (first && typeof first === 'object') {
      const fs = (first.fields || []).map((f: Record<string, unknown>) => ({
        name: (f.name as string) || '',
        type: (f.type as string) || 'String',
        description: (f.description as string) || '',
        constraints: parseConstraints(f.constraints as Record<string, unknown> | undefined),
      }));
      return { typeName: first.name || '', fields: fs };
    }
  } catch { /* ignore */ }
  return { typeName: '', fields: [] };
}

function buildSchemaJson(name: string, fs: SchemaField[]): unknown[] | null {
  const validFields = fs.filter(f => f.name.trim());
  if (!name.trim() && validFields.length === 0) return null;
  return [{
    name: name || 'Response',
    type: 'object',
    fields: validFields.map(f => ({
      name: f.name,
      type: f.type,
      description: f.description || undefined,
      constraints: buildConstraintsJson(f.constraints),
    })),
  }];
}

function ResponseSchemaEditor({ value, onChange, inputStyle }: {
  value: unknown;
  onChange: (v: unknown) => void;
  inputStyle: React.CSSProperties;
}) {
  const [mode, setMode] = useState<'form' | 'json'>('form');
  const [typeName, setTypeName] = useState('');
  const [fields, setFields] = useState<SchemaField[]>([]);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');

  // Parse existing value on mount
  useEffect(() => {
    const { typeName: tn, fields: fs } = parseSchemaToForm(value);
    setTypeName(tn);
    setFields(fs.length > 0 ? fs : []);
    setJsonText(value ? JSON.stringify(value, null, 2) : '');
  }, []);

  const handleFormChange = (name: string, fs: SchemaField[]) => {
    const schema = buildSchemaJson(name, fs);
    onChange(schema);
    setJsonText(schema ? JSON.stringify(schema, null, 2) : '');
  };

  const updateField = (idx: number, key: string, val: unknown) => {
    const updated = fields.map((f, i) => {
      if (i !== idx) return f;
      if (key.startsWith('constraints.')) {
        const cKey = key.split('.')[1];
        return { ...f, constraints: { ...f.constraints, [cKey]: val } };
      }
      return { ...f, [key]: val };
    });
    setFields(updated);
    handleFormChange(typeName, updated);
  };

  const addField = () => {
    const updated = [...fields, { name: '', type: 'String', description: '', constraints: {} }];
    setFields(updated);
  };

  const removeField = (idx: number) => {
    const updated = fields.filter((_, i) => i !== idx);
    setFields(updated);
    handleFormChange(typeName, updated);
  };

  const handleJsonChange = (raw: string) => {
    setJsonText(raw);
    if (!raw.trim()) { setJsonError(''); onChange(null); return; }
    try {
      const parsed = JSON.parse(raw);
      setJsonError('');
      onChange(parsed);
      // Sync to form
      const { typeName: tn, fields: fs } = parseSchemaToForm(parsed);
      setTypeName(tn);
      setFields(fs);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Invalid JSON');
    }
  };

  const switchMode = (m: 'form' | 'json') => {
    if (m === 'json' && mode === 'form') {
      // Form → JSON: rebuild JSON from form
      const schema = buildSchemaJson(typeName, fields);
      setJsonText(schema ? JSON.stringify(schema, null, 2) : '');
    } else if (m === 'form' && mode === 'json') {
      // JSON → Form: parse JSON into form
      try {
        const parsed = jsonText.trim() ? JSON.parse(jsonText) : null;
        const { typeName: tn, fields: fs } = parseSchemaToForm(parsed);
        setTypeName(tn);
        setFields(fs.length > 0 ? fs : []);
      } catch { /* keep current form state */ }
    }
    setMode(m);
  };

  return (
    <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-medium text-sm">Response Schema</h3>
        <div className="flex gap-1">
          {(['form', 'json'] as const).map(m => (
            <button key={m} onClick={() => switchMode(m)}
              className="text-xs px-2 py-1 rounded cursor-pointer"
              style={{
                background: mode === m ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                color: mode === m ? 'white' : 'var(--color-text-secondary)',
              }}>
              {m === 'form' ? 'Form' : 'JSON'}
            </button>
          ))}
        </div>
      </div>

      {mode === 'form' ? (
        <div className="space-y-3">
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Type Name</label>
            <input value={typeName} onChange={e => { setTypeName(e.target.value); handleFormChange(e.target.value, fields); }}
              className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none"
              style={inputStyle} placeholder="e.g. AnalysisResult" />
          </div>

          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Fields</label>
            <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
              <div className="grid grid-cols-[1fr_90px_1fr_44px_36px] gap-0 text-xs font-medium px-2 py-1.5"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                <span>Name</span><span>Type</span><span>Description</span><span>Req</span><span />
              </div>
              {fields.map((f, i) => (
                <div key={i} className="grid grid-cols-[1fr_90px_1fr_44px_36px] gap-0 items-center border-t"
                  style={{ borderColor: 'var(--color-border)' }}>
                  <input value={f.name} onChange={e => updateField(i, 'name', e.target.value)}
                    className="px-2 py-1.5 text-xs outline-none border-r" style={{ ...inputStyle, borderColor: 'var(--color-border)' }}
                    placeholder="field name" />
                  <select value={f.type} onChange={e => updateField(i, 'type', e.target.value)}
                    className="px-1 py-1.5 text-xs outline-none border-r" style={{ ...inputStyle, borderColor: 'var(--color-border)' }}>
                    {FIELD_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                  <input value={f.description} onChange={e => updateField(i, 'description', e.target.value)}
                    className="px-2 py-1.5 text-xs outline-none border-r" style={{ ...inputStyle, borderColor: 'var(--color-border)' }}
                    placeholder="optional" />
                  <div className="flex justify-center border-r" style={{ borderColor: 'var(--color-border)' }}>
                    <input type="checkbox" checked={!!f.constraints.notNull} onChange={e => updateField(i, 'constraints.notNull', e.target.checked || undefined)}
                      className="cursor-pointer" />
                  </div>
                  <button onClick={() => removeField(i)} className="flex justify-center cursor-pointer text-xs"
                    style={{ color: 'var(--color-error)' }}>✕</button>
                </div>
              ))}
            </div>
            <button onClick={addField}
              className="mt-2 text-xs px-3 py-1 rounded cursor-pointer"
              style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
              + Add Field
            </button>
          </div>
        </div>
      ) : (
        <div>
          <textarea value={jsonText} onChange={e => handleJsonChange(e.target.value)}
            rows={8}
            className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
            style={{ ...inputStyle, borderColor: jsonError ? 'var(--color-error)' : inputStyle.borderColor }}
            placeholder='[{"name": "Result", "type": "object", "fields": [...]}]' />
          {jsonError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{jsonError}</p>}
        </div>
      )}
    </div>
  );
}

function CollapsibleApiUsage({ agentId }: { agentId: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <button onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between p-4 cursor-pointer text-left">
        <div className="flex items-center gap-2">
          <Code size={14} style={{ color: 'var(--color-text-secondary)' }} />
          <h3 className="font-medium text-sm">API Usage</h3>
        </div>
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>
      {open && (
        <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
          <ApiUsagePanel agentId={agentId} />
        </div>
      )}
    </div>
  );
}

function ApiUsagePanel({ agentId }: { agentId: string }) {
  const [copied, setCopied] = useState('');
  const [tab, setTab] = useState<'call' | 'trigger' | 'session'>('call');
  const apiKey = localStorage.getItem('apiKey') || '<YOUR_API_KEY>';
  const baseUrl = window.location.origin;

  const callCurl = `curl -X POST '${baseUrl}/api/agents/${agentId}/call' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"input": "Hello, what can you do?"}'

# Response:
# {"output": "...", "token_usage": {"input": 27, "output": 42}, "run_id": "..."}`;

  const triggerCurl = `curl -X POST '${baseUrl}/api/runs/agent/${agentId}/trigger' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"input": "Hello, what can you do?"}'`;

  const sessionCurl = `# 1. Create session
SESSION_ID=$(curl -s -X POST '${baseUrl}/api/sessions' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"agent_id": "${agentId}"}' | jq -r '.sessionId')

# 2. Connect SSE (in another terminal)
curl -N -X PUT "${baseUrl}/api/sessions/events?agent-session-id=$SESSION_ID" \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -H 'Accept: text/event-stream'

# 3. Send message
curl -X POST "${baseUrl}/api/sessions/$SESSION_ID/messages" \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"message": "Hello!"}'`;

  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  const tabs = [
    { key: 'call' as const, label: 'Call (Sync)' },
    { key: 'trigger' as const, label: 'Trigger (Async)' },
    { key: 'session' as const, label: 'Session (Stream)' },
  ];

  const currentCode = tab === 'call' ? callCurl : tab === 'trigger' ? triggerCurl : sessionCurl;

  return (
    <div>
      <div className="flex items-center justify-between px-4 py-3">
        <div />
        <button onClick={() => handleCopy(currentCode, tab)}
          className="flex items-center gap-1 text-xs px-2 py-1 rounded cursor-pointer"
          style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
          {copied === tab ? <><Check size={12} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={12} /> Copy</>}
        </button>
      </div>
      <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className="px-4 py-1.5 text-xs font-medium cursor-pointer"
            style={{
              color: tab === t.key ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              borderBottom: tab === t.key ? '2px solid var(--color-primary)' : '2px solid transparent',
            }}>
            {t.label}
          </button>
        ))}
      </div>
      <pre className="px-4 py-3 text-xs font-mono overflow-auto whitespace-pre-wrap"
        style={{ maxHeight: '200px', color: 'var(--color-text-secondary)' }}>
        {currentCode}
      </pre>
    </div>
  );
}
