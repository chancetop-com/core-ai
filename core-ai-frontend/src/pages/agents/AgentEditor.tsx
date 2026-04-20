import { useEffect, useState, useRef, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Upload, Play, Copy, Check, Code, Download, FileUp, Maximize2, Minimize2, Square, Loader2, ChevronDown, ChevronRight, X, Wrench, Search, Link, Trash, Users } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, SystemPrompt, AgentRun, AgentRunDetail, ToolRegistryView, ToolRef } from '../../api/client';
import { sessionApi } from '../../api/session';
import StatusBadge from '../../components/StatusBadge';

export default function AgentEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [publishSuccess, setPublishSuccess] = useState(false);
  const [promptExpanded, setPromptExpanded] = useState(false);

  // system prompts for dropdown
  const [systemPrompts, setSystemPrompts] = useState<SystemPrompt[]>([]);

  // tools
  const [allTools, setAllTools] = useState<ToolRegistryView[]>([]);
  const [toolSearch, setToolSearch] = useState('');

  // subagents
  const [allAgents, setAllAgents] = useState<AgentDefinition[]>([]);
  const [subAgentSearch, setSubAgentSearch] = useState('');

  // test panel
  const [testInput, setTestInput] = useState('');
  const [testOutput, setTestOutput] = useState('');
  const [testing, setTesting] = useState(false);
  const testControllerRef = useRef<AbortController | null>(null);
  const testOutputRef = useRef('');

  // image attachments for LLM_CALL
  const [imageAttachments, setImageAttachments] = useState<{ url?: string; data?: string; mediaType?: string; preview: string }[]>([]);
  const [showImageUrlInput, setShowImageUrlInput] = useState(false);
  const [imageUrlValue, setImageUrlValue] = useState('');
  const [showBase64Input, setShowBase64Input] = useState(false);
  const [base64Value, setBase64Value] = useState('');
  const [base64MediaType, setBase64MediaType] = useState('image/png');
  const imageFileRef = useRef<HTMLInputElement>(null);
  const importFileRef = useRef<HTMLInputElement>(null);
  const [showImportConfirm, setShowImportConfirm] = useState(false);
  const [pendingImportData, setPendingImportData] = useState<Record<string, unknown> | null>(null);

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
    api.agents.list().then(res => {
      // Include both AGENT type and local type (CLI mode), exclude current agent
      const published = (res.agents || []).filter(a =>
        a.id !== id &&
        (a.status === 'PUBLISHED' || a.status === 'DRAFT') &&
        (a.type === 'AGENT' || a.type === 'local')
      );
      setAllAgents(published);
    }).catch(console.error);
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
    setSaveSuccess(false);
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
        tools: agent.tools,
        input_template: agent.input_template,
        variables: agent.variables,
        response_schema: agent.response_schema,
        subagent_ids: (agent as unknown as Record<string, unknown>).subagent_ids as string[] | undefined,
      });
      setAgent(updated);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
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

  const handleImageUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      setImageAttachments(prev => [...prev, { data: base64, mediaType: file.type, preview: result }]);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  const handleAddImageUrl = () => {
    if (!imageUrlValue.trim()) return;
    setImageAttachments(prev => [...prev, { url: imageUrlValue.trim(), preview: imageUrlValue.trim() }]);
    setImageUrlValue('');
    setShowImageUrlInput(false);
  };

  const handleAddBase64 = () => {
    if (!base64Value.trim()) return;
    const raw = base64Value.trim().replace(/^data:[^;]+;base64,/, '');
    const preview = `data:${base64MediaType};base64,${raw}`;
    setImageAttachments(prev => [...prev, { data: raw, mediaType: base64MediaType, preview }]);
    setBase64Value('');
    setShowBase64Input(false);
  };

  const handleTest = async () => {
    if (!testInput.trim()) return;
    setTesting(true);
    setTestOutput('');
    testOutputRef.current = '';

    // LLM_CALL: use direct API call with attachments
    if (agent.type === 'LLM_CALL' && id) {
      try {
        const attachments = imageAttachments.length > 0
          ? imageAttachments.map(img => img.data
            ? { type: 'IMAGE', data: img.data, media_type: img.mediaType }
            : { type: 'IMAGE', url: img.url })
          : undefined;
        const res = await api.agents.llmCall(id, testInput, attachments as Parameters<typeof api.agents.llmCall>[2]);
        setTestOutput(res.output || '(empty)');
      } catch (e) {
        setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      } finally {
        setTesting(false);
      }
      return;
    }

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
      const tools = agent.tools && agent.tools.length > 0 ? agent.tools : undefined;

      const res = await sessionApi.create('', { config, tools });
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

  const IMPORT_FIELDS = ['name', 'description', 'type', 'system_prompt', 'model', 'temperature',
    'max_turns', 'timeout_seconds', 'tools', 'input_template', 'variables', 'response_schema', 'subagent_ids'] as const;

  const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    file.text().then(text => {
      try {
        const data = JSON.parse(text);
        const item = Array.isArray(data) ? data[0] : data;
        if (!item || typeof item !== 'object') {
          alert('Invalid agent JSON file');
          return;
        }
        const filtered: Record<string, unknown> = {};
        for (const key of IMPORT_FIELDS) {
          if (item[key] != null) filtered[key] = item[key];
        }
        setPendingImportData(filtered);
        setShowImportConfirm(true);
      } catch {
        alert('Invalid JSON file');
      }
    });
    e.target.value = '';
  };

  const applyImport = () => {
    if (!pendingImportData) return;
    setAgent({ ...agent, ...pendingImportData } as AgentDefinition);
    setShowImportConfirm(false);
    setPendingImportData(null);
  };

  const cancelImport = () => {
    setShowImportConfirm(false);
    setPendingImportData(null);
  };

  const handleExport = (a: AgentDefinition) => {
    const exportData = {
      name: a.name, description: a.description, type: a.type,
      system_prompt: a.system_prompt, model: a.model, temperature: a.temperature,
      max_turns: a.max_turns, timeout_seconds: a.timeout_seconds,         tools: a.tools,
      input_template: a.input_template, variables: a.variables, response_schema: a.response_schema,
      subagent_ids: (a as unknown as Record<string, unknown>).subagent_ids,
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
          <label className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <FileUp size={14} /> Import
            <input ref={importFileRef} type="file" accept=".json" className="hidden" onChange={handleImportFile} />
          </label>
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
          <button onClick={handleSave} disabled={saving || saveSuccess}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{
              background: saveSuccess ? 'var(--color-success)' : 'var(--color-primary)',
              color: 'white',
            }}>
            {saveSuccess ? <><Check size={14} /> Saved!</> : <><Save size={14} /> {saving ? 'Saving...' : 'Save'}</>}
          </button>
        </div>
      </div>

      {showImportConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}>
          <div className="rounded-xl p-6 max-w-md w-full mx-4" style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
            <h3 className="text-lg font-semibold mb-2">Confirm Import</h3>
            <p className="text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>
              This will overwrite the current agent configuration with the imported data.
            </p>
            {!!(pendingImportData as Record<string, unknown>)?.name && (
              <p className="text-sm mb-4" style={{ color: 'var(--color-text-secondary)' }}>
                Importing: <strong style={{ color: 'var(--color-text)' }}>{String(((pendingImportData as Record<string, unknown>)?.name as string) || '')}</strong>
              </p>
            )}
            <p className="text-sm mb-4" style={{ color: 'var(--color-warning, #f59e0b)' }}>
              Unsaved changes will be lost. You can review and save after import.
            </p>
            <div className="flex justify-end gap-2">
              <button onClick={cancelImport}
                className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
              <button onClick={applyImport}
                className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                Confirm Import
              </button>
            </div>
          </div>
        </div>
      )}

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
            {agent.tools && agent.tools.length > 0 ? (
              <div className="flex flex-wrap gap-2 mb-3">
                {agent.tools.map((toolRef: ToolRef) => {
                  const tool = allTools.find(t => t.id === toolRef.id);
                  return (
                    <span key={toolRef.id}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
                      <span className="w-1.5 h-1.5 rounded-full"
                        style={{ background: tool?.type === 'MCP' ? '#8b5cf6' : '#f59e0b' }} />
                      <span className="font-medium">{tool?.name || toolRef.id}</span>
                      {(tool?.type || toolRef.type) && (
                        <span className="text-[10px] px-1 rounded"
                          style={{ background: (tool?.type || toolRef.type) === 'MCP' ? '#8b5cf615' : '#f59e0b15',
                            color: (tool?.type || toolRef.type) === 'MCP' ? '#8b5cf6' : '#f59e0b' }}>
                          {tool?.type || toolRef.type}
                        </span>
                      )}
                      <button onClick={() => update('tools', agent.tools.filter((t: ToolRef) => t.id !== toolRef.id))}
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
                    .filter(t => !agent.tools?.some((tr: ToolRef) => tr.id === t.id))
                    .filter(t => t.name.toLowerCase().includes(toolSearch.toLowerCase()) || t.type?.toLowerCase().includes(toolSearch.toLowerCase()))
                    .map(t => (
                      <button key={t.id}
                        onClick={() => { update('tools', [...(agent.tools || []), { id: t.id, type: t.type, source: t.category }]); setToolSearch(''); }}
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
                  {allTools.filter(t => !agent.tools?.some((tr: ToolRef) => tr.id === t.id))
                    .filter(t => t.name.toLowerCase().includes(toolSearch.toLowerCase())).length === 0 && (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No matching tools</div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Subagents */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Users size={16} style={{ color: '#8b5cf6' }} /> Subagents
            </h3>
            <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
              Select agents to load as subagents when this agent is used in a chat session.
            </p>

            {/* Selected subagents */}
            {(agent as unknown as Record<string, unknown>).subagent_ids && ((agent as unknown as Record<string, unknown>).subagent_ids as string[]).length > 0 ? (
              <div className="flex flex-wrap gap-2 mb-3">
                {((agent as unknown as Record<string, unknown>).subagent_ids as string[]).map((subAgentId: string) => {
                  const subAgent = allAgents.find(a => a.id === subAgentId);
                  return (
                    <span key={subAgentId}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#8b5cf6' }} />
                      <span className="font-medium">{subAgent?.name || subAgentId}</span>
                      <button onClick={() => update('subagent_ids', ((agent as unknown as Record<string, unknown>).subagent_ids as string[]).filter((id: string) => id !== subAgentId))}
                        className="cursor-pointer ml-0.5 rounded hover:bg-[var(--color-bg-tertiary)]"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        <X size={12} />
                      </button>
                    </span>
                  );
                })}
              </div>
            ) : (
              <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>No subagents selected</p>
            )}

            {/* Add subagents */}
            <div className="relative">
              <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border text-sm"
                style={inputStyle}>
                <Search size={14} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />
                <input value={subAgentSearch} onChange={e => setSubAgentSearch(e.target.value)}
                  className="flex-1 bg-transparent outline-none text-sm"
                  placeholder="Search agents to add as subagent..." />
              </div>
              {subAgentSearch && (
                <div className="absolute z-10 mt-1 w-full rounded-lg border shadow-lg overflow-auto"
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', maxHeight: '200px' }}>
                  {allAgents
                    .filter(a => !((agent as unknown as Record<string, unknown>).subagent_ids as string[] | undefined)?.includes(a.id))
                    .filter(a => a.name.toLowerCase().includes(subAgentSearch.toLowerCase()) || a.type?.toLowerCase().includes(subAgentSearch.toLowerCase()))
                    .map(a => (
                      <button key={a.id}
                        onClick={() => {
                          const currentIds = ((agent as unknown as Record<string, unknown>).subagent_ids as string[]) || [];
                          update('subagent_ids', [...currentIds, a.id]);
                          setSubAgentSearch('');
                        }}
                        className="w-full px-3 py-2 text-left text-xs flex items-center gap-2 cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                        <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: '#8b5cf6' }} />
                        <span className="font-medium">{a.name}</span>
                        <span className="text-[10px] px-1 rounded ml-auto"
                          style={{ background: '#8b5cf615', color: '#8b5cf6' }}>
                          {a.type}
                        </span>
                        {a.description && (
                          <span className="text-[10px] truncate" style={{ color: 'var(--color-text-secondary)' }}>{a.description}</span>
                        )}
                      </button>
                    ))}
                  {allAgents.filter(a => !((agent as unknown as Record<string, unknown>).subagent_ids as string[] | undefined)?.includes(a.id))
                    .filter(a => a.name.toLowerCase().includes(subAgentSearch.toLowerCase())).length === 0 && (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No matching agents</div>
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

              {/* Image attachments for LLM_CALL */}
              {agent.type === 'LLM_CALL' && (
                <div>
                  {imageAttachments.length > 0 && (
                    <div className="flex flex-wrap gap-2 mb-2">
                      {imageAttachments.map((img, i) => (
                        <div key={i} className="relative group">
                          <img src={img.preview} alt="attachment"
                            className="w-16 h-16 object-cover rounded-lg border"
                            style={{ borderColor: 'var(--color-border)' }} />
                          <button onClick={() => setImageAttachments(prev => prev.filter((_, j) => j !== i))}
                            className="absolute -top-1.5 -right-1.5 w-5 h-5 rounded-full flex items-center justify-center cursor-pointer opacity-0 group-hover:opacity-100 transition-opacity"
                            style={{ background: 'var(--color-error)', color: 'white' }}>
                            <X size={10} />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="flex gap-1.5">
                    <input ref={imageFileRef} type="file" accept="image/*" className="hidden" onChange={handleImageUpload} />
                    <button onClick={() => imageFileRef.current?.click()}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Upload size={12} /> Upload
                    </button>
                    <button onClick={() => { setShowImageUrlInput(!showImageUrlInput); setShowBase64Input(false); }}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Link size={12} /> URL
                    </button>
                    <button onClick={() => { setShowBase64Input(!showBase64Input); setShowImageUrlInput(false); }}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Code size={12} /> Base64
                    </button>
                    {imageAttachments.length > 0 && (
                      <button onClick={() => setImageAttachments([])}
                        className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer"
                        style={{ color: 'var(--color-error)' }}>
                        <Trash size={12} /> Clear
                      </button>
                    )}
                  </div>
                  {showImageUrlInput && (
                    <div className="flex gap-1.5 mt-1.5">
                      <input value={imageUrlValue} onChange={e => setImageUrlValue(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && handleAddImageUrl()}
                        className="flex-1 px-2 py-1 rounded-md border text-xs outline-none"
                        style={inputStyle}
                        placeholder="https://example.com/image.png" />
                      <button onClick={handleAddImageUrl} disabled={!imageUrlValue.trim()}
                        className="px-2 py-1 rounded-md text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                        style={{ background: 'var(--color-primary)' }}>
                        Add
                      </button>
                    </div>
                  )}
                  {showBase64Input && (
                    <div className="mt-1.5 space-y-1.5">
                      <div className="flex gap-1.5">
                        <select value={base64MediaType} onChange={e => setBase64MediaType(e.target.value)}
                          className="px-2 py-1 rounded-md border text-xs outline-none"
                          style={inputStyle}>
                          <option value="image/png">PNG</option>
                          <option value="image/jpeg">JPEG</option>
                          <option value="image/webp">WebP</option>
                          <option value="image/gif">GIF</option>
                        </select>
                        <button onClick={handleAddBase64} disabled={!base64Value.trim()}
                          className="px-2 py-1 rounded-md text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                          style={{ background: 'var(--color-primary)' }}>
                          Add
                        </button>
                      </div>
                      <textarea value={base64Value} onChange={e => setBase64Value(e.target.value)}
                        rows={3}
                        className="w-full px-2 py-1 rounded-md border text-xs font-mono outline-none resize-y"
                        style={inputStyle}
                        placeholder="Paste base64 string or data URI (data:image/png;base64,...)" />
                    </div>
                  )}
                </div>
              )}

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
            <CollapsibleApiUsage agentId={agent.id} agentType={agent.type} />
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

const JSON_SCHEMA_PLACEHOLDER = `{
  "title": "Response",
  "type": "object",
  "properties": {
    "result": {
      "type": "string",
      "description": "The result text"
    },
    "score": {
      "type": "integer",
      "description": "Confidence score 0-100"
    }
  },
  "required": ["result", "score"]
}`;

const JAVA_CLASS_PLACEHOLDER = `public class SentimentResult {
    @CoreAiParameter(description = "sentiment label")
    public String sentiment;

    @CoreAiParameter(description = "confidence 0-100")
    public int confidence;

    public List<String> keywords;
}`;

function ResponseSchemaEditor({ value, onChange, inputStyle }: {
  value: unknown;
  onChange: (v: unknown) => void;
  inputStyle: React.CSSProperties;
}) {
  const [mode, setMode] = useState<'json' | 'java'>('json');
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [javaCode, setJavaCode] = useState('');
  const [converting, setConverting] = useState(false);
  const [convertError, setConvertError] = useState('');

  useEffect(() => {
    if (typeof value === 'string' && value) {
      try { setJsonText(JSON.stringify(JSON.parse(value), null, 2)); } catch { setJsonText(value); }
    } else {
      setJsonText('');
    }
  }, []);

  const handleJsonChange = (raw: string) => {
    setJsonText(raw);
    if (!raw.trim()) { setJsonError(''); onChange(null); return; }
    try {
      JSON.parse(raw);
      setJsonError('');
      onChange(raw);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Invalid JSON');
    }
  };

  const handleConvert = async () => {
    if (!javaCode.trim()) return;
    setConverting(true);
    setConvertError('');
    try {
      const res = await api.agents.javaToSchema(javaCode);
      if (res.error) {
        setConvertError(res.error);
      } else if (res.schema) {
        const formatted = JSON.stringify(JSON.parse(res.schema), null, 2);
        setJsonText(formatted);
        onChange(res.schema);
        setMode('json');
      }
    } catch (e) {
      setConvertError(e instanceof Error ? e.message : 'Convert failed');
    } finally {
      setConverting(false);
    }
  };

  return (
    <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-medium text-sm">Response Schema</h3>
        <div className="flex gap-1">
          {(['json', 'java'] as const).map(m => (
            <button key={m} onClick={() => setMode(m)}
              className="text-xs px-2 py-1 rounded cursor-pointer"
              style={{
                background: mode === m ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                color: mode === m ? 'white' : 'var(--color-text-secondary)',
              }}>
              {m === 'json' ? 'JSON Schema' : 'Java Class'}
            </button>
          ))}
        </div>
      </div>

      {mode === 'json' ? (
        <>
          <textarea value={jsonText} onChange={e => handleJsonChange(e.target.value)}
            rows={10}
            className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
            style={{ ...inputStyle, borderColor: jsonError ? 'var(--color-error)' : inputStyle.borderColor }}
            placeholder={JSON_SCHEMA_PLACEHOLDER} />
          {jsonError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{jsonError}</p>}
          {!jsonText.trim() && (
            <p className="text-xs mt-1.5" style={{ color: 'var(--color-text-secondary)' }}>
              Optional. Use standard JSON Schema to define structured output format.
            </p>
          )}
        </>
      ) : (
        <>
          <textarea value={javaCode} onChange={e => setJavaCode(e.target.value)}
            rows={10}
            className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
            style={inputStyle}
            placeholder={JAVA_CLASS_PLACEHOLDER} />
          <div className="flex items-center gap-2 mt-2">
            <button onClick={handleConvert} disabled={!javaCode.trim() || converting}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-primary)' }}>
              {converting ? <><Loader2 size={12} className="animate-spin" /> Converting...</> : 'Convert to JSON Schema'}
            </button>
            <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
              Uses LLM to convert Java class to JSON Schema
            </span>
          </div>
          {convertError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{convertError}</p>}
        </>
      )}
    </div>
  );
}

function CollapsibleApiUsage({ agentId, agentType }: { agentId: string; agentType?: string }) {
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
          <ApiUsagePanel agentId={agentId} agentType={agentType} />
        </div>
      )}
    </div>
  );
}

function ApiUsagePanel({ agentId, agentType }: { agentId: string; agentType?: string }) {
  const [copied, setCopied] = useState('');
  const isLlmCall = agentType === 'LLM_CALL';
  const [tab, setTab] = useState<'call' | 'trigger' | 'session'>(isLlmCall ? 'call' : 'trigger');
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

  const allTabs = [
    { key: 'call' as const, label: 'Call (Sync)' },
    { key: 'trigger' as const, label: 'Trigger (Async)' },
    { key: 'session' as const, label: 'Session (Stream)' },
  ];
  const tabs = isLlmCall
    ? allTabs.filter(t => t.key === 'call')
    : allTabs.filter(t => t.key !== 'call');

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
