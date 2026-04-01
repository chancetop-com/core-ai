import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Upload, Play, Copy, Check, Code } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, SystemPrompt, AgentRun } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function AgentEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // system prompts for dropdown
  const [systemPrompts, setSystemPrompts] = useState<SystemPrompt[]>([]);

  // run
  const [runInput, setRunInput] = useState('');
  const [triggering, setTriggering] = useState(false);
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);

  useEffect(() => {
    if (!id) return;
    api.agents.get(id).then(setAgent).catch(console.error).finally(() => setLoading(false));
    api.systemPrompts.list(0, 100).then(setSystemPrompts).catch(console.error);
    // auto-load runs
    setRunsLoading(true);
    api.agents.runs(id).then(res => setRuns(res.runs || [])).catch(console.error).finally(() => setRunsLoading(false));
  }, [id]);

  const loadRuns = async () => {
    if (!id) return;
    setRunsLoading(true);
    try {
      const res = await api.agents.runs(id);
      setRuns(res.runs || []);
    } finally {
      setRunsLoading(false);
    }
  };

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!agent) return <div className="p-6">Agent not found</div>;

  const update = (field: string, value: unknown) => {
    setAgent({ ...agent, [field]: value } as AgentDefinition);
  };

  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    try {
      const updated = await api.agents.update(id, {
        name: agent.name,
        description: agent.description,
        system_prompt: agent.system_prompt,
        system_prompt_id: agent.system_prompt_id,
        model: agent.model,
        temperature: agent.temperature,
        max_turns: agent.max_turns,
        timeout_seconds: agent.timeout_seconds,
        tool_ids: agent.tool_ids,
        input_template: agent.input_template,
        variables: agent.variables,
      });
      setAgent(updated);
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!id) return;
    const published = await api.agents.publish(id);
    setAgent(published);
  };

  const handleDelete = async () => {
    if (!id || !confirm('Delete this agent?')) return;
    await api.agents.delete(id);
    navigate('/agents');
  };

  const handleTrigger = async () => {
    if (!id || !runInput.trim()) return;
    setTriggering(true);
    try {
      await api.agents.trigger(id, runInput);
      setRunInput('');
      loadRuns();
      // Refresh again after a delay since run may still be in progress
      setTimeout(loadRuns, 3000);
      setTimeout(loadRuns, 8000);
    } finally {
      setTriggering(false);
    }
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
          <button onClick={handlePublish}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <Upload size={14} /> Publish
          </button>
          <button onClick={handleSave} disabled={saving}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

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
                  <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Inline System Prompt
                  </label>
                  <textarea value={agent.system_prompt || ''}
                    onChange={e => update('system_prompt', e.target.value)}
                    rows={8}
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
        </div>

        {/* Right: info + run */}
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
              <div className="flex justify-between"><dt>Created</dt><dd>{agent.created_at ? new Date(agent.created_at).toLocaleString() : '-'}</dd></div>
              <div className="flex justify-between"><dt>Updated</dt><dd>{agent.updated_at ? new Date(agent.updated_at).toLocaleString() : '-'}</dd></div>
            </dl>
          </div>

          {/* API Usage */}
          {agent.status === 'PUBLISHED' && (
            <ApiUsagePanel agentId={agent.id} />
          )}

          {/* Trigger Run */}
          {agent.status === 'PUBLISHED' && (
            <div className="rounded-xl border p-4"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
                <Play size={16} style={{ color: 'var(--color-success)' }} /> Trigger Run
              </h3>
              <div className="space-y-3">
                <textarea value={runInput} onChange={e => setRunInput(e.target.value)}
                  rows={3}
                  className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none resize-y"
                  style={inputStyle}
                  placeholder="Enter input for the agent..." />
                <button onClick={handleTrigger} disabled={triggering || !runInput.trim()}
                  className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-success)' }}>
                  <Play size={14} /> {triggering ? 'Triggering...' : 'Run'}
                </button>
              </div>
            </div>
          )}

          {/* Recent Runs */}
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
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                No runs yet. {agent.status !== 'PUBLISHED' ? 'Publish the agent first.' : 'Trigger a run above.'}
              </p>
            ) : (
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {runs.map(r => (
                  <div key={r.id} className="p-2 rounded-lg border text-xs cursor-pointer"
                    style={{ borderColor: 'var(--color-border)' }}
                    onClick={() => navigate(`/runs/${r.id}`)}>
                    <div className="flex items-center justify-between">
                      <StatusBadge status={r.status} />
                      <span style={{ color: 'var(--color-text-secondary)' }}>
                        {r.started_at ? new Date(r.started_at).toLocaleString() : '-'}
                      </span>
                    </div>
                    {r.input && (
                      <p className="mt-1 truncate" style={{ color: 'var(--color-text-secondary)' }}>
                        Input: {r.input}
                      </p>
                    )}
                    {r.output && (
                      <p className="mt-1 truncate">Output: {r.output}</p>
                    )}
                    {r.error && (
                      <p className="mt-1 truncate" style={{ color: 'var(--color-error)' }}>
                        Error: {r.error}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function ApiUsagePanel({ agentId }: { agentId: string }) {
  const [copied, setCopied] = useState('');
  const [tab, setTab] = useState<'trigger' | 'session'>('trigger');
  const apiKey = localStorage.getItem('apiKey') || '<YOUR_API_KEY>';
  const baseUrl = window.location.origin;

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
curl -N -X PUT "${baseUrl}/api/sessions/events?sessionId=$SESSION_ID" \\
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
    { key: 'trigger' as const, label: 'Trigger Run' },
    { key: 'session' as const, label: 'Session (Streaming)' },
  ];

  const currentCode = tab === 'trigger' ? triggerCurl : sessionCurl;

  return (
    <div className="rounded-xl border overflow-hidden"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2">
          <Code size={14} style={{ color: 'var(--color-primary)' }} />
          <h3 className="font-medium text-sm">API Usage</h3>
        </div>
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
