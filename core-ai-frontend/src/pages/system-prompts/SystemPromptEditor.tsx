import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Play, Clock, ChevronDown, ChevronRight } from 'lucide-react';
import { api } from '../../api/client';
import type { SystemPrompt, SystemPromptVersion, SystemPromptTestResult } from '../../api/client';

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
  const [testResult, setTestResult] = useState<SystemPromptTestResult | null>(null);
  const [testing, setTesting] = useState(false);

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

  const handleTest = async () => {
    if (!promptId || !testMessage.trim()) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = await api.systemPrompts.test(promptId, {
        model: testModel,
        userMessage: testMessage,
        variables: Object.keys(testVariables).length > 0 ? testVariables : undefined,
      });
      setTestResult(result);
    } catch (e) {
      setTestResult({ output: `Error: ${e instanceof Error ? e.message : String(e)}`, inputTokens: 0, outputTokens: 0, resolvedPrompt: '' });
    } finally {
      setTesting(false);
    }
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

      <div className="grid grid-cols-3 gap-6">
        {/* Left: editor */}
        <div className="col-span-2 space-y-4">
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
          <div>
            <label className="block text-sm font-medium mb-1">System Prompt Content</label>
            <textarea value={prompt.content || ''} onChange={e => setPrompt({ ...prompt, content: e.target.value })}
              rows={16}
              className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
              style={inputStyle}
              placeholder="Enter your system prompt. Use {{variable}} for variables." />
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

        {/* Right: panels */}
        <div className="space-y-4">
          {/* Variables */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Variables</h3>
            {variables.length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {variables.map(v => (
                  <span key={v} className="px-2 py-1 rounded text-xs font-mono"
                    style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-primary)' }}>
                    {`{{${v}}}`}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Use {'{{variable}}'} syntax in content
              </p>
            )}
          </div>

          {/* Version History */}
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

          {/* Test Panel */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Play size={16} style={{ color: 'var(--color-success)' }} /> Test Prompt
            </h3>
            <div className="space-y-3">
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

              <button onClick={handleTest} disabled={testing || !testMessage.trim()}
                className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-success)' }}>
                <Play size={14} /> {testing ? 'Testing...' : 'Run Test'}
              </button>

              {testResult && (
                <div className="mt-2">
                  {testResult.resolvedPrompt && (
                    <details className="mb-2">
                      <summary className="text-xs cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                        Resolved Prompt
                      </summary>
                      <pre className="text-xs mt-1 p-2 rounded overflow-auto max-h-32"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {testResult.resolvedPrompt}
                      </pre>
                    </details>
                  )}
                  <div className="rounded-lg p-3 text-sm"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    <pre className="whitespace-pre-wrap text-sm">{testResult.output}</pre>
                  </div>
                  {(testResult.inputTokens > 0 || testResult.outputTokens > 0) && (
                    <div className="flex gap-4 mt-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      <span>Input: {testResult.inputTokens} tokens</span>
                      <span>Output: {testResult.outputTokens} tokens</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
