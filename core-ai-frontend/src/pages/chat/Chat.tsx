import { useState, useRef, useEffect, useCallback } from 'react';
import { Send, Square, Shield, ShieldOff, Loader2, Bot, User } from 'lucide-react';
import { a2aApi } from '../../api/a2a';
import type { StreamEvent } from '../../api/a2a';

interface AwaitInfo {
  callId: string;
  tool: string;
  arguments: string;
}

interface ChatMessage {
  role: 'user' | 'agent';
  content: string;
  tools?: ToolEvent[];
  approval?: AwaitInfo;
}

interface ToolEvent {
  type: 'start' | 'result';
  tool: string;
  callId: string;
  arguments?: string;
  result?: string;
  resultStatus?: string;
}

export default function Chat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<'idle' | 'running' | 'awaiting'>('idle');
  const [awaitInfo, setAwaitInfo] = useState<AwaitInfo | null>(null);
  const [currentTaskId, setCurrentTaskId] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const streamingContentRef = useRef('');

  const scrollToBottom = useCallback(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  useEffect(() => {
    if (status === 'idle') inputRef.current?.focus();
  }, [status]);

  const handleSend = async () => {
    const text = input.trim();
    if (!text || status !== 'idle') return;

    setInput('');
    setMessages(prev => [...prev, { role: 'user', content: text }]);
    setStatus('running');
    streamingContentRef.current = '';

    setMessages(prev => [...prev, { role: 'agent', content: '', tools: [] }]);

    try {
      await a2aApi.sendMessageStream(text, (event: StreamEvent) => {
        if (event.taskId) setCurrentTaskId(event.taskId);

        if (event.type === 'status' && event.status) {
          const state = event.status.state;

          // streaming text chunk
          if (state === 'working' && event.status.message) {
            const chunk = event.status.message.parts?.[0]?.text || '';
            if (chunk) {
              streamingContentRef.current += chunk;
              setMessages(prev => {
                const updated = [...prev];
                const last = updated[updated.length - 1];
                if (last?.role === 'agent') {
                  updated[updated.length - 1] = { ...last, content: streamingContentRef.current };
                }
                return updated;
              });
            }
          }

          // tool events via metadata
          if (state === 'working' && event.metadata) {
            const meta = event.metadata;
            if (meta.event === 'tool_start') {
              setMessages(prev => {
                const updated = [...prev];
                const last = updated[updated.length - 1];
                if (last?.role === 'agent') {
                  const tools = [...(last.tools || [])];
                  tools.push({ type: 'start', tool: meta.tool, callId: meta.call_id, arguments: meta.arguments });
                  updated[updated.length - 1] = { ...last, tools };
                }
                return updated;
              });
            } else if (meta.event === 'tool_result') {
              setMessages(prev => {
                const updated = [...prev];
                const last = updated[updated.length - 1];
                if (last?.role === 'agent') {
                  const tools = [...(last.tools || [])];
                  const idx = tools.findLastIndex(t => t.callId === meta.call_id);
                  if (idx >= 0) {
                    tools[idx] = { ...tools[idx], type: 'result', result: meta.result, resultStatus: meta.result_status };
                  }
                  updated[updated.length - 1] = { ...last, tools };
                }
                return updated;
              });
            }
          }

          // input required (tool approval)
          if (state === 'input-required' && event.metadata) {
            const info: AwaitInfo = {
              callId: event.metadata.call_id,
              tool: event.metadata.tool,
              arguments: event.metadata.arguments || '',
            };
            setStatus('awaiting');
            setAwaitInfo(info);
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') {
                updated[updated.length - 1] = { ...last, approval: info };
              }
              return updated;
            });
          }

          // terminal states
          if (state === 'completed' || state === 'failed' || state === 'canceled') {
            if (state === 'failed' && event.status.message) {
              const errText = event.status.message.parts?.[0]?.text || 'Unknown error';
              setMessages(prev => {
                const updated = [...prev];
                const last = updated[updated.length - 1];
                if (last?.role === 'agent') {
                  updated[updated.length - 1] = { ...last, content: `Error: ${errText}` };
                }
                return updated;
              });
            }
            setStatus('idle');
            setAwaitInfo(null);
            setCurrentTaskId(null);
          }
        }

        // artifact event (final output)
        if (event.type === 'artifact' && event.artifact) {
          const text = event.artifact.parts?.[0]?.text || '';
          if (text) {
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') {
                updated[updated.length - 1] = { ...last, content: text };
              }
              return updated;
            });
          }
        }
      }, () => {
        setStatus('idle');
        setAwaitInfo(null);
      });
    } catch (err) {
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent') {
          updated[updated.length - 1] = { ...last, content: `Connection error: ${err}` };
        }
        return updated;
      });
      setStatus('idle');
    }
  };

  const handleApproval = async (decision: 'approve' | 'deny') => {
    if (!currentTaskId || !awaitInfo) return;
    try {
      await a2aApi.resumeTask(currentTaskId, decision, awaitInfo.callId);
      setStatus('running');
      setAwaitInfo(null);
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent' && last.approval) {
          updated[updated.length - 1] = { ...last, approval: undefined };
        }
        return updated;
      });
    } catch (err) {
      console.error('approval failed:', err);
    }
  };

  const handleCancel = async () => {
    if (!currentTaskId) return;
    try {
      await a2aApi.cancelTask(currentTaskId);
    } catch {
      // ignore
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-auto p-6">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full gap-4"
            style={{ color: 'var(--color-text-secondary)' }}>
            <Bot size={48} strokeWidth={1.5} />
            <div className="text-lg font-medium">Core AI Assistant</div>
            <div className="text-sm">Send a message to start</div>
          </div>
        )}
        <div className="max-w-4xl mx-auto flex flex-col gap-4">
          {messages.map((msg, i) => (
            <div key={i} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`}>
              {msg.role === 'agent' && (
                <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  <Bot size={18} />
                </div>
              )}
              <div className={`max-w-[80%] ${msg.role === 'user' ? 'order-first' : ''}`}>
                <div className="rounded-xl px-4 py-3 text-sm"
                  style={{
                    background: msg.role === 'user' ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                    color: msg.role === 'user' ? 'white' : 'var(--color-text)',
                    border: msg.role === 'agent' ? '1px solid var(--color-border)' : 'none',
                  }}>
                  <pre className="whitespace-pre-wrap font-[inherit] m-0">{msg.content}</pre>
                  {status === 'running' && msg.role === 'agent' && i === messages.length - 1 && !msg.content && (
                    <Loader2 size={16} className="animate-spin" style={{ color: 'var(--color-text-secondary)' }} />
                  )}
                </div>
                {msg.tools && msg.tools.length > 0 && (
                  <div className="mt-2 flex flex-col gap-1">
                    {msg.tools.map((t, j) => (
                      <div key={j} className="text-xs rounded-lg px-3 py-2"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        <span className="font-mono font-medium" style={{ color: 'var(--color-primary)' }}>
                          {t.tool}
                        </span>
                        {t.type === 'start' && t.arguments && (
                          <span className="ml-2 opacity-70">{t.arguments.length > 80 ? t.arguments.slice(0, 80) + '...' : t.arguments}</span>
                        )}
                        {t.type === 'result' && (
                          <span className="ml-2" style={{ color: t.resultStatus === 'COMPLETED' ? 'var(--color-success)' : 'var(--color-error)' }}>
                            {t.resultStatus === 'COMPLETED' ? 'done' : t.resultStatus}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
                {msg.approval && (
                  <div className="mt-2 rounded-xl border px-4 py-3"
                    style={{ borderColor: 'var(--color-warning)', background: 'var(--color-bg-secondary)' }}>
                    <div className="text-sm font-medium mb-2" style={{ color: 'var(--color-warning)' }}>
                      Tool requires approval
                    </div>
                    <div className="text-xs font-mono mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                      <span className="font-bold">{msg.approval.tool}</span>
                      {msg.approval.arguments && (
                        <pre className="mt-1 whitespace-pre-wrap opacity-70">
                          {msg.approval.arguments.length > 200 ? msg.approval.arguments.slice(0, 200) + '...' : msg.approval.arguments}
                        </pre>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <button onClick={() => handleApproval('approve')}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer transition-colors"
                        style={{ background: 'var(--color-success)', color: 'white' }}>
                        <Shield size={14} /> Approve
                      </button>
                      <button onClick={() => handleApproval('deny')}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer transition-colors"
                        style={{ background: 'var(--color-error)', color: 'white' }}>
                        <ShieldOff size={14} /> Deny
                      </button>
                    </div>
                  </div>
                )}
              </div>
              {msg.role === 'user' && (
                <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  <User size={18} />
                </div>
              )}
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
      </div>
      <div className="border-t p-4" style={{ borderColor: 'var(--color-border)' }}>
        <div className="max-w-4xl mx-auto flex gap-3 items-end">
          <textarea
            ref={inputRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Send a message..."
            rows={1}
            className="flex-1 rounded-xl border px-4 py-3 text-sm resize-none focus:outline-none"
            style={{
              background: 'var(--color-bg-secondary)',
              borderColor: 'var(--color-border)',
              color: 'var(--color-text)',
            }}
            disabled={status !== 'idle'}
          />
          {status === 'idle' ? (
            <button onClick={handleSend}
              disabled={!input.trim()}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-40"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              <Send size={18} />
            </button>
          ) : (
            <button onClick={handleCancel}
              className="p-3 rounded-xl cursor-pointer transition-colors"
              style={{ background: 'var(--color-error)', color: 'white' }}>
              <Square size={18} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
