import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { Send, X } from 'lucide-react';

export interface ChatTurn { role: 'user' | 'assistant'; content: string }

interface Msg { role: 'user' | 'assistant'; text: string; runId?: string }

interface Props {
  onSend: (message: string, history: ChatTurn[]) => Promise<{ reply: string; runId: string }>;
  onClose: () => void;
}

/** Chatflow preview: a chat UI over the run engine. Each message runs the current DRAFT with {query, history};
 *  the reply is the END/ANSWER node output. Conversation history is client-side for v1 (server-side conversation
 *  variables land with the variable model). */
export default function ChatPanel({ onSend, onClose }: Props) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [running, setRunning] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages, running]);

  const send = async () => {
    const text = input.trim();
    if (!text || running) return;
    setInput('');
    const history: ChatTurn[] = messages.map((m) => ({ role: m.role, content: m.text }));
    setMessages((m) => [...m, { role: 'user', text }]);
    setRunning(true);
    try {
      const { reply, runId } = await onSend(text, history);
      setMessages((m) => [...m, { role: 'assistant', text: reply, runId }]);
    } catch (e) {
      setMessages((m) => [...m, { role: 'assistant', text: `Error: ${(e as Error).message}` }]);
    } finally {
      setRunning(false);
    }
  };

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '12px 14px', borderBottom: '1px solid var(--color-border)' }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>Chat preview</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      <div style={list}>
        {messages.length === 0 && <div style={hint}>Send a message to test the chatflow on the current draft.</div>}
        {messages.map((m, i) => (
          <div key={i} style={{ display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
            <div style={bubble(m.role)}>
              {m.text}
              {m.runId && <span style={traceLink} title="run id">run {m.runId.slice(0, 8)}</span>}
            </div>
          </div>
        ))}
        {running && <div style={{ display: 'flex' }}><div style={bubble('assistant')}>…</div></div>}
        <div ref={endRef} />
      </div>

      <div style={composer}>
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder="Type a message…  (Enter to send)"
          rows={2}
          style={textarea}
        />
        <button onClick={send} disabled={running || !input.trim()} style={sendBtn} title="Send"><Send size={16} /></button>
      </div>
    </div>
  );
}

const panel: CSSProperties = {
  width: 360, flexShrink: 0, display: 'flex', flexDirection: 'column',
  borderLeft: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const list: CSSProperties = { flex: 1, overflowY: 'auto', padding: 14, display: 'flex', flexDirection: 'column', gap: 10 };
function bubble(role: 'user' | 'assistant'): CSSProperties {
  const user = role === 'user';
  return {
    maxWidth: '85%', padding: '8px 11px', borderRadius: 12, fontSize: 13, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
    background: user ? 'var(--color-primary)' : 'var(--color-bg)', color: user ? '#fff' : 'var(--color-text)',
    border: user ? 'none' : '1px solid var(--color-border)',
  };
}
const composer: CSSProperties = { display: 'flex', gap: 8, padding: 12, borderTop: '1px solid var(--color-border)', alignItems: 'flex-end' };
const textarea: CSSProperties = {
  flex: 1, boxSizing: 'border-box', padding: '8px 10px', fontSize: 13, resize: 'none',
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const sendBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 36, height: 36, flexShrink: 0,
  border: 'none', borderRadius: 8, background: 'var(--color-primary)', color: '#fff', cursor: 'pointer',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const hint: CSSProperties = { fontSize: 12, color: 'var(--color-text-secondary)', textAlign: 'center', marginTop: 20, lineHeight: 1.5 };
const traceLink: CSSProperties = { display: 'block', marginTop: 4, fontSize: 10, fontFamily: 'monospace', opacity: 0.7, color: 'inherit', textDecoration: 'none' };
