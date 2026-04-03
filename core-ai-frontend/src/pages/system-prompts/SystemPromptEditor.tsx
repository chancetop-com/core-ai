import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Play, Clock, ChevronDown, ChevronRight, Square, Loader2, Maximize2, Minimize2 } from 'lucide-react';
import { api } from '../../api/client';
import type { SystemPrompt, SystemPromptVersion } from '../../api/client';
import { sessionApi } from '../../api/session';

export default function SystemPromptEditor() {
  const { promptId } = useParams<{ promptId: string }>();
  const navigate = useNavigate();

  const [prompt, setPrompt] = useState<SystemPrompt | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [changelog, setChangelog] = useState('');

  // version history
  const [versions, setVersions] = useState<SystemPromptVersion[]>([]);
  const [versionsOpen, setVersionsOpen] = useState(false);

  // test panel
  const [testModel, setTestModel] = useState('');
  const [testMessage, setTestMessage] = useState('');
  const [testVariables, setTestVariables] = useState<Record<string, string>>({});
  const [testOutput, setTestOutput] = useState('');
  const [testing, setTesting] = useState(false);
  const testControllerRef = useRef<AbortController | null>(null);
  const testOutputRef = useRef('');

  // layout
  const [contentExpanded, setContentExpanded] = useState(false);

  useEffect(() => {
    if (!promptId) return;
    api.systemPrompts.get(promptId).then(setPrompt).finally(() => setLoading(false));
  }, [promptId]);

  const loadVersions = async () => {
    if (!promptId) return;
    if (versionsOpen) { setVersionsOpen(false); return; }
    const v = await api.systemPrompts.versions(promptId);
    setVersions(v);
    setVersionsOpen(true);
  };

  const restoreVersion = async (v: SystemPromptVersion) => {
    if (!prompt) return;
    setPrompt({ ...prompt, content: v.content });
  };

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!prompt) return <div className="p-6">System Prompt not found</div>;

  const extractVariables = (content: string): string[] => {
    const matches = content.match(/\{\{(\w+)\}\}/g) || [];
    return [...new Set(matches.map(m => m.slice(2, -2)))];
  };

  const variables = extractVariables(prompt.content || '');

  const handleSave = async () => {
    if (!promptId) return;
    setSaving(true);
    try {
      const updated = await api.systemPrompts.update(promptId, {
        name: prompt.name,
        description: prompt.description,
        content: prompt.content,
        tags: prompt.tags,
        changelog: changelog || undefined,
      });
      setPrompt(updated);
      setChangelog('');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!promptId || !confirm('Delete this system prompt and all versions?')) return;
    await api.systemPrompts.delete(promptId);
    navigate('/system-prompts');
  };

  const resolvePromptVariables = (content: string, vars: Record<string, string>): string => {
    let resolved = content;
    for (const [k, v] of Object.entries(vars)) {
      resolved = resolved.replaceAll(`{{${k}}}`, v);
    }
    return resolved;
  };

  const handleTest = async () => {
    if (!prompt || !testMessage.trim()) return;
    setTesting(true);
    setTestOutput('');
    testOutputRef.current = '';

    const resolvedPrompt = Object.keys(testVariables).length > 0
      ? resolvePromptVariables(prompt.content, testVariables)
      : prompt.content;

    try {
      const config: Record<string, unknown> = { systemPrompt: resolvedPrompt };
      if (testModel.trim()) config.model = testModel;
      config.maxTurns = 1;

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
            testOutputRef.current += `\n\nError: ${data.message || data.error}`;
            setTestOutput(testOutputRef.current);
            setTesting(false);
            sessionApi.close(sid).catch(() => {});
          }
        } catch { /* ignore */ }
      }, () => {
        setTesting(false);
      });
      testControllerRef.current = controller;

      setTimeout(() => {
        sessionApi.sendMessage(sid, testMessage).catch(err => {
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

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <button onClick={() => navigate('/system-prompts')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to System Prompts
      </button>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-semibold">{prompt.name}</h1>
          <span className="px-2 py-0.5 rounded text-xs font-mono"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            v{prompt.version}
          </span>
        </div>
        <div className="flex gap-2">
          <button onClick={handleDelete}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
            <Trash2 size={14} /> Delete
          </button>
          <button onClick={handleSave} disabled={saving}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {/* Top: name, description, tags, changelog */}
      <div className="space-y-4 mb-6">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium mb-1">Name</label>
            <input value={prompt.name} onChange={e => setPrompt({ ...prompt, name: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Description</label>
            <input value={prompt.description || ''} onChange={e => setPrompt({ ...prompt, description: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium mb-1">Tags (comma separated)</label>
            <input value={prompt.tags?.join(', ') || ''}
              onChange={e => setPrompt({ ...prompt, tags: e.target.value.split(',').map(s => s.trim()).filter(Boolean) })}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Changelog</label>
            <input value={changelog} onChange={e => setChangelog(e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="What changed in this version?" />
          </div>
        </div>
      </div>

      {/* Main: prompt editor (left) + test panel (right) side by side */}
      <div className="grid grid-cols-2 gap-6 mb-6">
        {/* Left: prompt content editor */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="block text-sm font-medium">System Prompt Content</label>
            <button onClick={() => setContentExpanded(!contentExpanded)}
              className="text-xs px-2 py-0.5 rounded cursor-pointer flex items-center gap-1"
              style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
              {contentExpanded ? <><Minimize2 size={12} /> Collapse</> : <><Maximize2 size={12} /> Expand</>}
            </button>
          </div>
          <textarea value={prompt.content || ''} onChange={e => setPrompt({ ...prompt, content: e.target.value })}
            rows={contentExpanded ? 32 : 18}
            className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
            style={inputStyle}
            placeholder="Enter your system prompt. Use {{variable}} for variables." />
          {variables.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-2">
              {variables.map(v => (
                <span key={v} className="px-2 py-1 rounded text-xs font-mono"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-primary)' }}>
                  {`{{${v}}}`}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Right: test panel */}
        <div className="rounded-xl border p-4 flex flex-col"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
            <Play size={16} style={{ color: 'var(--color-success)' }} /> Test Prompt
          </h3>
          <div className="space-y-3 flex-1 flex flex-col">
            <div>
              <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Model</label>
              <input value={testModel} onChange={e => setTestModel(e.target.value)}
                className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none"
                style={inputStyle}
                placeholder="leave empty to use default model" />
            </div>

            {variables.length > 0 && (
              <div>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Variables</label>
                {variables.map(v => (
                  <div key={v} className="flex items-center gap-2 mb-1">
                    <span className="text-xs font-mono w-24 shrink-0" style={{ color: 'var(--color-primary)' }}>{v}</span>
                    <input value={testVariables[v] || ''} onChange={e => setTestVariables({ ...testVariables, [v]: e.target.value })}
                      className="flex-1 px-2 py-1 rounded border text-xs outline-none"
                      style={inputStyle}
                      placeholder={`Value for ${v}`} />
                  </div>
                ))}
              </div>
            )}

            <div>
              <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>User Message</label>
              <textarea value={testMessage} onChange={e => setTestMessage(e.target.value)}
                rows={3}
                className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none resize-y"
                style={inputStyle}
                placeholder="Enter a test message..." />
            </div>

            {!testing ? (
              <button onClick={handleTest} disabled={!testMessage.trim()}
                className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-success)' }}>
                <Play size={14} /> Run Test
              </button>
            ) : (
              <button onClick={handleStopTest}
                className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-error)' }}>
                <Square size={14} /> Stop
              </button>
            )}

            {/* Test output - takes remaining space */}
            <div className="flex-1 min-h-0">
              {(testOutput || testing) && (
                <div className="rounded-lg p-3 text-sm overflow-auto"
                  style={{ background: 'var(--color-bg-tertiary)', maxHeight: '300px' }}>
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
        </div>
      </div>

      {/* Bottom: Version History */}
      <div className="rounded-xl border"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <button onClick={loadVersions}
          className="w-full flex items-center justify-between p-4 cursor-pointer text-left">
          <div className="flex items-center gap-2">
            <Clock size={16} style={{ color: 'var(--color-text-secondary)' }} />
            <h3 className="font-medium text-sm">Version History</h3>
          </div>
          {versionsOpen ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        {versionsOpen && (
          <div className="border-t px-4 pb-4 max-h-60 overflow-y-auto" style={{ borderColor: 'var(--color-border)' }}>
            {versions.map(v => (
              <div key={v.version} className="flex items-center justify-between py-2 border-b last:border-0"
                style={{ borderColor: 'var(--color-border)' }}>
                <div>
                  <span className="text-sm font-mono">v{v.version}</span>
                  {v.changelog && (
                    <span className="text-xs ml-2" style={{ color: 'var(--color-text-secondary)' }}>{v.changelog}</span>
                  )}
                  <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {new Date(v.createdAt).toLocaleString()}
                  </div>
                </div>
                {v.version !== prompt.version && (
                  <button onClick={() => restoreVersion(v)}
                    className="text-xs px-2 py-1 rounded cursor-pointer"
                    style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                    Restore
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
