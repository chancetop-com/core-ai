import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Sparkles, FileText, FolderOpen, Plus, Trash2, Upload, X, ChevronRight, ChevronDown, RefreshCw, Download } from 'lucide-react';
import { api } from '../../api/client';
import JSZip from 'jszip';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';

interface SkillFile {
  path: string;
  content: string;
}

const SKILL_MD = 'SKILL.md';

function parseFrontmatter(content: string): { frontmatter: Record<string, string | string[]>; body: string } {
  const match = content.match(/^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)$/);
  if (!match) return { frontmatter: {}, body: content };
  const fm: Record<string, string | string[]> = {};
  for (const line of match[1].split('\n')) {
    const idx = line.indexOf(':');
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    const val = line.slice(idx + 1).trim();
    fm[key] = val;
  }
  return { frontmatter: fm, body: match[2] };
}

function buildSkillMdContent(fm: Record<string, string | string[]>, body: string): string {
  const lines = ['---'];
  for (const [k, v] of Object.entries(fm)) {
    if (v == null || v === '') continue;
    lines.push(`${k}: ${Array.isArray(v) ? v.join(' ') : v}`);
  }
  lines.push('---');
  return lines.join('\n') + '\n' + body;
}

export default function SkillEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');

  const [name, setName] = useState('');
  const [namespace, setNamespace] = useState('');
  const [sourceType, setSourceType] = useState<'UPLOAD' | 'REPO'>('UPLOAD');
  const [description, setDescription] = useState('');
  const [allowedTools, setAllowedTools] = useState<string[]>([]);
  const [newTool, setNewTool] = useState('');
  const [version, setVersion] = useState('');

  const [skillBody, setSkillBody] = useState('');
  const [resources, setResources] = useState<SkillFile[]>([]);
  const [selectedFile, setSelectedFile] = useState<string>(SKILL_MD);

  const [expandScripts, setExpandScripts] = useState(true);
  const [expandRefs, setExpandRefs] = useState(true);

  const [showNewFile, setShowNewFile] = useState(false);
  const [newFileName, setNewFileName] = useState('');
  const [newFileDir, setNewFileDir] = useState<'scripts' | 'references'>('scripts');

  const uploadRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    Promise.all([api.skills.get(id), api.skills.download(id)])
      .then(([skill, downloaded]) => {
        setNamespace(skill.namespace);
        setSourceType(skill.source_type);
        setVersion(skill.version || '');

        const { frontmatter, body } = parseFrontmatter(downloaded.content);
        setName(String(frontmatter.name || skill.name));
        setDescription(String(frontmatter.description || skill.description || ''));
        const tools = frontmatter['allowed-tools'];
        if (tools) {
          setAllowedTools(typeof tools === 'string' ? tools.split(/\s+/).filter(Boolean) : tools);
        } else {
          setAllowedTools(skill.allowed_tools || []);
        }
        setSkillBody(body);
        setResources(downloaded.resources || []);
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [id]);

  const scriptFiles = resources.filter(r => r.path.startsWith('scripts/'));
  const refFiles = resources.filter(r => r.path.startsWith('references/'));

  const selectedContent = selectedFile === SKILL_MD
    ? skillBody
    : resources.find(r => r.path === selectedFile)?.content || '';

  const handleContentChange = (value: string) => {
    if (selectedFile === SKILL_MD) {
      setSkillBody(value);
    } else {
      setResources(prev => prev.map(r => r.path === selectedFile ? { ...r, content: value } : r));
    }
  };

  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    setSaveError('');
    try {
      const fm: Record<string, string | string[]> = { name, description };
      if (allowedTools.length > 0) fm['allowed-tools'] = allowedTools;
      if (version) fm.version = version;
      const fullContent = buildSkillMdContent(fm, skillBody);

      await api.skills.update(id, {
        description,
        content: fullContent,
        allowed_tools: allowedTools.length > 0 ? allowedTools : [],
        resources: resources.map(r => ({ path: r.path, content: r.content })),
      });
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handleAddTool = () => {
    const t = newTool.trim();
    if (t && !allowedTools.includes(t)) {
      setAllowedTools([...allowedTools, t]);
    }
    setNewTool('');
  };

  const handleNewFile = () => {
    const fname = newFileName.trim();
    if (!fname) return;
    const path = `${newFileDir}/${fname}`;
    if (resources.some(r => r.path === path)) {
      alert(`File "${path}" already exists`);
      return;
    }
    setResources([...resources, { path, content: '' }]);
    setSelectedFile(path);
    setShowNewFile(false);
    setNewFileName('');
  };

  const handleUploadFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const content = reader.result as string;
      const path = `scripts/${file.name}`;
      if (resources.some(r => r.path === path)) {
        if (!confirm(`File "${path}" already exists. Overwrite?`)) return;
        setResources(prev => prev.map(r => r.path === path ? { ...r, content } : r));
      } else {
        setResources(prev => [...prev, { path, content }]);
      }
      setSelectedFile(path);
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const handleDeleteFile = (path: string) => {
    if (!confirm(`Delete "${path}"?`)) return;
    setResources(prev => prev.filter(r => r.path !== path));
    if (selectedFile === path) setSelectedFile(SKILL_MD);
  };

  const handleDelete = async () => {
    if (!id || !confirm('Delete this skill?')) return;
    await api.skills.delete(id);
    navigate('/skills');
  };

  const handleSync = async () => {
    if (!id) return;
    await api.skills.sync(id);
    window.location.reload();
  };

  const handleExport = async () => {
    const fm: Record<string, string | string[]> = { name, description };
    if (allowedTools.length > 0) fm['allowed-tools'] = allowedTools;
    if (version) fm.version = version;
    const fullContent = buildSkillMdContent(fm, skillBody);

    const zip = new JSZip();
    const folder = zip.folder(name) !;
    folder.file('SKILL.md', fullContent);
    for (const r of resources) {
      folder.file(r.path, r.content);
    }
    const blob = await zip.generateAsync({ type: 'blob' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${name.replace(/\s+/g, '-').toLowerCase()}.zip`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;

  return (
    <div className="p-6 h-full flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }}>
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <button onClick={() => navigate('/skills')}
            className="flex items-center gap-1 text-sm cursor-pointer"
            style={{ color: 'var(--color-primary)' }}>
            <ArrowLeft size={16} /> Back to Skills
          </button>
          <Sparkles size={20} style={{ color: 'var(--color-primary)' }} />
          <h1 className="text-xl font-semibold">{name}</h1>
          <span className="px-2 py-0.5 rounded text-xs"
            style={{ background: sourceType === 'REPO' ? '#064e3b' : 'var(--color-bg-tertiary)', color: sourceType === 'REPO' ? '#6ee7b7' : 'var(--color-text-secondary)' }}>
            {sourceType}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={handleDelete}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
            <Trash2 size={14} /> Delete
          </button>
          <button onClick={handleExport}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <Download size={14} /> Export
          </button>
          {sourceType === 'REPO' && (
            <button onClick={handleSync}
              className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: 'var(--color-border)' }}>
              <RefreshCw size={14} /> Sync
            </button>
          )}
          <button onClick={handleSave} disabled={saving}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {saveError && (
        <div className="mb-3 p-3 rounded-lg text-sm" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
          {saveError}
        </div>
      )}

      {/* Metadata form */}
      <div className="rounded-xl border p-4 mb-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="grid grid-cols-3 gap-3 mb-3">
          <div>
            <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Name</label>
            <input value={name} onChange={e => setName(e.target.value)}
              className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle} />
          </div>
          <div>
            <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Namespace</label>
            <input value={namespace} readOnly
              className="w-full px-3 py-1.5 rounded-lg border text-sm opacity-60" style={inputStyle} />
          </div>
          <div>
            <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Version</label>
            <input value={version} onChange={e => setVersion(e.target.value)}
              className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle}
              placeholder="e.g. 1.0" />
          </div>
        </div>
        <div className="mb-3">
          <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
          <input value={description} onChange={e => setDescription(e.target.value)}
            className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle} />
        </div>
        <div>
          <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Allowed Tools</label>
          <div className="flex flex-wrap items-center gap-2">
            {allowedTools.map(t => (
              <span key={t} className="flex items-center gap-1 px-2 py-0.5 rounded text-xs"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {t}
                <button onClick={() => setAllowedTools(allowedTools.filter(x => x !== t))}
                  className="cursor-pointer hover:opacity-70"><X size={10} /></button>
              </span>
            ))}
            <div className="flex items-center gap-1">
              <input value={newTool} onChange={e => setNewTool(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleAddTool()}
                className="px-2 py-0.5 rounded border text-xs w-36" style={inputStyle}
                placeholder="Add tool..." />
              <button onClick={handleAddTool}
                className="px-2 py-0.5 rounded text-xs border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                <Plus size={12} />
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* IDE area: file tree + editor */}
      <div className="flex-1 flex rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)', minHeight: 0 }}>
        {/* File tree */}
        <div className="w-56 flex-shrink-0 border-r flex flex-col"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="px-3 py-2 text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>FILES</div>
          <div className="flex-1 overflow-auto">
            <FileItem name={SKILL_MD} icon={<FileText size={14} />}
              selected={selectedFile === SKILL_MD}
              onClick={() => setSelectedFile(SKILL_MD)} />

            <FolderItem name="scripts" expanded={expandScripts}
              onToggle={() => setExpandScripts(!expandScripts)} count={scriptFiles.length} />
            {expandScripts && scriptFiles.map(f => (
              <FileItem key={f.path} name={f.path.replace('scripts/', '')} indent
                selected={selectedFile === f.path}
                onClick={() => setSelectedFile(f.path)}
                onDelete={() => handleDeleteFile(f.path)} />
            ))}

            <FolderItem name="references" expanded={expandRefs}
              onToggle={() => setExpandRefs(!expandRefs)} count={refFiles.length} />
            {expandRefs && refFiles.map(f => (
              <FileItem key={f.path} name={f.path.replace('references/', '')} indent
                selected={selectedFile === f.path}
                onClick={() => setSelectedFile(f.path)}
                onDelete={() => handleDeleteFile(f.path)} />
            ))}
          </div>

          <div className="border-t p-2 flex flex-col gap-1" style={{ borderColor: 'var(--color-border)' }}>
            <button onClick={() => setShowNewFile(true)}
              className="flex items-center gap-1.5 px-2 py-1.5 rounded text-xs w-full cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              onMouseEnter={e => e.currentTarget.style.background = 'var(--color-bg-tertiary)'}
              onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
              <Plus size={12} /> New File
            </button>
            <label
              className="flex items-center gap-1.5 px-2 py-1.5 rounded text-xs w-full cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              onMouseEnter={e => e.currentTarget.style.background = 'var(--color-bg-tertiary)'}
              onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
              <Upload size={12} /> Upload File
              <input ref={uploadRef} type="file" className="hidden" onChange={handleUploadFile} />
            </label>
          </div>
        </div>

        {/* Editor */}
        <div className="flex-1 flex flex-col min-w-0">
          <div className="flex items-center px-3 py-1.5 border-b text-xs"
            style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            <span>{selectedFile}</span>
          </div>
          <div className="flex-1" style={{ minHeight: 0 }}>
            <CodeMirrorEditor
              key={selectedFile}
              value={selectedContent}
              filename={selectedFile}
              onChange={handleContentChange} />
          </div>
        </div>
      </div>

      {/* New file dialog */}
      {showNewFile && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}>
          <div className="rounded-xl p-5 w-96" style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
            <h3 className="text-sm font-semibold mb-3">New File</h3>
            <div className="mb-3">
              <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Directory</label>
              <div className="flex gap-2">
                {(['scripts', 'references'] as const).map(d => (
                  <button key={d} onClick={() => setNewFileDir(d)}
                    className="px-3 py-1.5 rounded-lg text-xs border cursor-pointer"
                    style={{
                      borderColor: newFileDir === d ? 'var(--color-primary)' : 'var(--color-border)',
                      background: newFileDir === d ? 'var(--color-primary)' : 'transparent',
                      color: newFileDir === d ? 'white' : 'var(--color-text)',
                    }}>
                    {d}/
                  </button>
                ))}
              </div>
            </div>
            <div className="mb-4">
              <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>File Name</label>
              <input value={newFileName} onChange={e => setNewFileName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleNewFile()}
                className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle}
                placeholder="e.g. run.sh" autoFocus />
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => { setShowNewFile(false); setNewFileName(''); }}
                className="px-3 py-1.5 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
              <button onClick={handleNewFile} disabled={!newFileName.trim()}
                className="px-3 py-1.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                Create
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function FileItem({ name, icon, selected, indent, onClick, onDelete }: {
  name: string;
  icon?: React.ReactNode;
  selected?: boolean;
  indent?: boolean;
  onClick: () => void;
  onDelete?: () => void;
}) {
  return (
    <div className="group flex items-center gap-1.5 px-2 py-1 cursor-pointer text-xs"
      style={{
        paddingLeft: indent ? '1.75rem' : '0.5rem',
        background: selected ? 'var(--color-bg-tertiary)' : 'transparent',
        color: selected ? 'var(--color-text)' : 'var(--color-text-secondary)',
      }}
      onClick={onClick}
      onMouseEnter={e => { if (!selected) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
      onMouseLeave={e => { if (!selected) e.currentTarget.style.background = 'transparent'; }}>
      {icon || <FileText size={13} />}
      <span className="flex-1 truncate">{name}</span>
      {onDelete && (
        <button onClick={e => { e.stopPropagation(); onDelete(); }}
          className="opacity-0 group-hover:opacity-100 cursor-pointer p-0.5 rounded hover:bg-[var(--color-bg-secondary)]">
          <Trash2 size={11} style={{ color: 'var(--color-error)' }} />
        </button>
      )}
    </div>
  );
}

function FolderItem({ name, expanded, onToggle, count }: {
  name: string;
  expanded: boolean;
  onToggle: () => void;
  count: number;
}) {
  return (
    <div className="flex items-center gap-1.5 px-2 py-1 cursor-pointer text-xs"
      style={{ color: 'var(--color-text-secondary)' }}
      onClick={onToggle}
      onMouseEnter={e => e.currentTarget.style.background = 'var(--color-bg-tertiary)'}
      onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
      {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
      <FolderOpen size={13} />
      <span className="flex-1">{name}/</span>
      {count > 0 && <span className="text-[10px] opacity-60">{count}</span>}
    </div>
  );
}
