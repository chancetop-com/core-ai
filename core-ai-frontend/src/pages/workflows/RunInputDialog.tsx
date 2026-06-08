import { useState, type CSSProperties } from 'react';
import { Play, X } from 'lucide-react';

interface Props {
  onRun: (input: string) => void;
  onClose: () => void;
  busy: boolean;
}

/** Modal that collects the run input JSON. v1 is a free-form JSON editor; a schema-derived form lands with the P2
 *  variable model. */
export default function RunInputDialog({ onRun, onClose, busy }: Props) {
  const [input, setInput] = useState('{}');
  const [err, setErr] = useState('');

  const submit = () => {
    try {
      JSON.parse(input || '{}');
    } catch {
      setErr('Invalid JSON');
      return;
    }
    onRun(input || '{}');
  };

  return (
    <div style={overlay} onClick={onClose}>
      <div style={dialog} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 16, color: 'var(--color-text)' }}>Run workflow</h3>
          <div style={{ flex: 1 }} />
          <button onClick={onClose} style={iconBtn} title="Close"><X size={16} /></button>
        </div>
        <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 4 }}>
          Input (JSON)
        </label>
        <textarea
          value={input}
          onChange={(e) => { setInput(e.target.value); setErr(''); }}
          spellCheck={false}
          autoFocus
          style={textarea}
        />
        {err && <div style={{ color: '#dc2626', fontSize: 12, marginTop: 4 }}>{err}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          <button onClick={onClose} style={btn}>Cancel</button>
          <button onClick={submit} disabled={busy} style={btnPrimary}><Play size={15} /> Run</button>
        </div>
      </div>
    </div>
  );
}

const overlay: CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)', display: 'flex',
  alignItems: 'center', justifyContent: 'center', zIndex: 1000,
};
const dialog: CSSProperties = {
  width: 460, maxWidth: '90vw', padding: 20, borderRadius: 12,
  background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)', boxShadow: '0 12px 40px rgba(0,0,0,0.25)',
};
const textarea: CSSProperties = {
  width: '100%', boxSizing: 'border-box', minHeight: 140, padding: '8px 10px', fontFamily: 'monospace', fontSize: 12,
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none', resize: 'vertical',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const btn: CSSProperties = {
  padding: '7px 14px', border: '1px solid var(--color-border)', borderRadius: 8,
  background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer', fontWeight: 500,
};
const btnPrimary: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, padding: '7px 14px', border: 'none', borderRadius: 8,
  background: 'var(--color-primary)', color: '#fff', cursor: 'pointer', fontWeight: 500,
};
