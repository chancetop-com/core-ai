import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Upload, Trash2 } from 'lucide-react';
import { api } from '../../api/client';
import type { PromptTemplate } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function PromptEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [prompt, setPrompt] = useState<PromptTemplate | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!id) return;
    api.prompts.get(id).then(setPrompt).finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!prompt) return <div className="p-6">Prompt not found</div>;

  const update = (field: keyof PromptTemplate, value: unknown) => {
    setPrompt({ ...prompt, [field]: value } as PromptTemplate);
  };

  const extractVariables = (tpl: string): string[] => {
    const matches = tpl.match(/\{\{(\w+)\}\}/g) || [];
    return [...new Set(matches.map(m => m.slice(2, -2)))];
  };

  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    const vars = extractVariables(prompt.template);
    update('variables', vars);
    await api.prompts.update(id, { ...prompt, variables: vars });
    setSaving(false);
  };

  const handlePublish = async () => {
    if (!id) return;
    const published = await api.prompts.publish(id);
    setPrompt(published);
  };

  const handleDelete = async () => {
    if (!id || !confirm('Delete this prompt?')) return;
    await api.prompts.delete(id);
    navigate('/prompts');
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <button onClick={() => navigate('/prompts')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Prompts
      </button>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-semibold">{prompt.name}</h1>
          <StatusBadge status={prompt.status} />
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>v{prompt.version}</span>
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
        {/* Left: editor */}
        <div className="col-span-2 space-y-4">
          <div>
            <label className="block text-sm font-medium mb-1">Name</label>
            <input value={prompt.name} onChange={e => update('name', e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Description</label>
            <input value={prompt.description || ''} onChange={e => update('description', e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Template</label>
            <textarea value={prompt.template || ''} onChange={e => update('template', e.target.value)}
              rows={16}
              className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
              style={inputStyle}
              placeholder="Enter your prompt template. Use {{variable}} for variables." />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1">Tags (comma separated)</label>
            <input value={prompt.tags?.join(', ') || ''} onChange={e => update('tags', e.target.value.split(',').map(s => s.trim()).filter(Boolean))}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>
        </div>

        {/* Right: metadata */}
        <div className="space-y-4">
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Model Settings</h3>
            <div className="space-y-3">
              <div>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Model</label>
                <input value={prompt.model || ''} onChange={e => update('model', e.target.value)}
                  className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none"
                  style={inputStyle}
                  placeholder="e.g. gpt-4o" />
              </div>
              <div>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Temperature</label>
                <input value={prompt.model_parameters?.temperature || ''}
                  onChange={e => update('model_parameters', { ...prompt.model_parameters, temperature: e.target.value })}
                  className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none"
                  style={inputStyle}
                  placeholder="0.7" />
              </div>
            </div>
          </div>

          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Variables</h3>
            {extractVariables(prompt.template || '').length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {extractVariables(prompt.template || '').map(v => (
                  <span key={v} className="px-2 py-1 rounded text-xs font-mono"
                    style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-primary)' }}>
                    {`{{${v}}}`}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Use {'{{variable}}'} syntax in template
              </p>
            )}
          </div>

          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Info</h3>
            <dl className="text-xs space-y-2" style={{ color: 'var(--color-text-secondary)' }}>
              <div className="flex justify-between"><dt>Version</dt><dd>{prompt.version}</dd></div>
              <div className="flex justify-between"><dt>Published</dt><dd>{prompt.published_version ? `v${prompt.published_version}` : '-'}</dd></div>
              <div className="flex justify-between"><dt>Created</dt><dd>{prompt.created_at ? new Date(prompt.created_at).toLocaleDateString() : '-'}</dd></div>
              <div className="flex justify-between"><dt>Updated</dt><dd>{prompt.updated_at ? new Date(prompt.updated_at).toLocaleDateString() : '-'}</dd></div>
            </dl>
          </div>
        </div>
      </div>
    </div>
  );
}
