import { useEffect, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { Minimize2 } from 'lucide-react';
import CodeMirrorEditor from '../../components/CodeMirrorEditor';
import { CODE_TEMPLATES, applyTemplate } from './codeTemplates';

interface Props {
  title: string;
  code: string;
  onChange: (code: string) => void;
  onClose: () => void;
}

/** Fullscreen Python editor for the CODE node — the config-panel editor is too small for real scripts. Rendered
 *  through a portal so the canvas layout can't clip it; Esc or the collapse button returns to the panel. */
export default function CodeEditorModal({ title, code, onChange, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div style={overlay} onClick={onClose}>
      <div style={sheet} onClick={(e) => e.stopPropagation()}>
        <div style={header}>
          <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>{title}</span>
          <span style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>Python</span>
          <div style={{ flex: 1 }} />
          <select
            value=""
            onChange={(e) => { if (e.target.value) onChange(applyTemplate(code, CODE_TEMPLATES.find((t) => t.key === e.target.value)?.code ?? '')); }}
            style={templateSelect}
          >
            <option value="">+ insert template</option>
            {CODE_TEMPLATES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
          </select>
          <button onClick={onClose} style={collapseBtn} title="Collapse (Esc)"><Minimize2 size={14} /> Collapse</button>
        </div>
        <div style={editorArea}>
          <CodeMirrorEditor value={code} filename="script.py" onChange={onChange} />
        </div>
        <div style={footer}>
          Read mapped variables via <code>inputs["name"]</code> · assign <code>result</code> to set the node output · <code>print()</code> is debug-only
        </div>
      </div>
    </div>,
    document.body,
  );
}

const overlay: CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,0.45)',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};
const sheet: CSSProperties = {
  width: '90vw', height: '88vh', display: 'flex', flexDirection: 'column', overflow: 'hidden',
  background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', borderRadius: 12,
  boxShadow: '0 18px 50px rgba(0,0,0,0.35)',
};
const header: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
  borderBottom: '1px solid var(--color-border)',
};
const editorArea: CSSProperties = { flex: 1, minHeight: 0 };
const footer: CSSProperties = {
  padding: '8px 14px', fontSize: 11.5, color: 'var(--color-text-secondary)',
  borderTop: '1px solid var(--color-border)', lineHeight: 1.5,
};
const templateSelect: CSSProperties = {
  fontSize: 12, padding: '5px 8px', border: '1px solid var(--color-border)', borderRadius: 6,
  background: 'var(--color-bg)', color: 'var(--color-text-secondary)', outline: 'none', cursor: 'pointer',
};
const collapseBtn: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 10px', fontSize: 12,
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)',
  color: 'var(--color-text)', cursor: 'pointer',
};
