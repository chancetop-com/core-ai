import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, ChevronLeft, ChevronRight, FileText } from 'lucide-react';
import { api } from '../../api/client';
import type { SystemPrompt } from '../../api/client';

export default function SystemPromptList() {
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const limit = 20;
  const navigate = useNavigate();

  const load = () => {
    setLoading(true);
    api.systemPrompts.list(offset, limit).then(setPrompts).finally(() => setLoading(false));
  };

  useEffect(load, [offset]);

  const handleCreate = async () => {
    const created = await api.systemPrompts.create({
      name: 'New System Prompt',
      content: 'You are a helpful assistant.',
    });
    if (created?.promptId) navigate(`/system-prompts/${created.promptId}`);
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

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">System Prompts</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Manage system prompts with version history and testing
          </p>
        </div>
        <button onClick={handleCreate}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={16} /> New System Prompt
        </button>
      </div>

      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : prompts.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No system prompts yet. Click "New System Prompt" to create one.
          </div>
        ) : prompts.map(p => (
          <div key={p.promptId} onClick={() => navigate(`/system-prompts/${p.promptId}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'var(--color-bg-secondary)')}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <FileText size={18} style={{ color: 'var(--color-primary)' }} />
                <span className="font-medium">{p.name}</span>
                <span className="px-2 py-0.5 rounded text-xs font-mono"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  v{p.version}
                </span>
              </div>
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {formatTime(p.createdAt)}
              </span>
            </div>
            {p.description && (
              <p className="text-sm mt-1 ml-8" style={{ color: 'var(--color-text-secondary)' }}>{p.description}</p>
            )}
            <div className="flex items-center gap-2 mt-2 ml-8">
              {p.tags?.map(tag => (
                <span key={tag} className="px-2 py-0.5 rounded text-xs"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {tag}
                </span>
              ))}
              {p.variables?.length > 0 && (
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {p.variables.length} variable{p.variables.length > 1 ? 's' : ''}
                </span>
              )}
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center justify-between mt-4">
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Showing {offset + 1}-{offset + prompts.length}
        </span>
        <div className="flex gap-2">
          <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <ChevronLeft size={14} /> Prev
          </button>
          <button onClick={() => setOffset(offset + limit)} disabled={prompts.length < limit}
            className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            Next <ChevronRight size={14} />
          </button>
        </div>
      </div>
    </div>
  );
}
