import { useState, useRef, useEffect, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Send, Square, Shield, ShieldOff, Loader2, Bot, User, ChevronDown, ChevronRight, Brain, Wrench, Plus } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type { SseEvent } from '../../api/session';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';

interface AwaitInfo {
  callId: string;
  tool: string;
  arguments: string;
}

interface ChatMessage {
  role: 'user' | 'agent';
  content: string;
  thinking?: string;
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

function ThinkingBlock({ thinking, isStreaming }: { thinking: string; isStreaming: boolean }) {
  const [expanded, setExpanded] = useState(isStreaming);
  return (
    <div className="mb-2 rounded-xl border text-xs"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      <button onClick={() => setExpanded(e => !e)}
        className="flex items-center gap-1.5 w-full px-3 py-2 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <Brain size={14} />
        <span className="font-medium">Thinking</span>
        {isStreaming && <Loader2 size={12} className="animate-spin ml-1" />}
      </button>
      {expanded && (
        <div className="px-3 pb-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <pre className="whitespace-pre-wrap font-mono opacity-70 leading-relaxed"
            style={{ color: 'var(--color-text-secondary)', fontSize: '11px' }}>
            {thinking}
          </pre>
        </div>
      )}
    </div>
  );
}

function ToolsBlock({ tools }: { tools: ToolEvent[] }) {
  const [expanded, setExpanded] = useState(true);
  return (
    <div className="mb-2 rounded-xl border text-xs"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      <button onClick={() => setExpanded(e => !e)}
        className="flex items-center gap-1.5 w-full px-3 py-2 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <Wrench size={14} />
        <span className="font-medium">Tools ({tools.length})</span>
      </button>
      {expanded && (
        <div className="px-3 pb-2 border-t flex flex-col gap-1" style={{ borderColor: 'var(--color-border)' }}>
          {tools.map((t, j) => (
            <div key={j} className="rounded-lg px-3 py-2"
              style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
              <span className="font-mono font-medium" style={{ color: 'var(--color-primary)' }}>{t.tool}</span>
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
    </div>
  );
}

export default function Chat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<'idle' | 'running'>('idle');
  const [awaitInfo, setAwaitInfo] = useState<AwaitInfo | null>(null);

  // Agent selection
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string>('');
  const [sessionId, setSessionId] = useState<string | null>(null);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const sseControllerRef = useRef<AbortController | null>(null);
  const streamingContentRef = useRef('');
  const streamingThinkingRef = useRef('');

  const scrollToBottom = useCallback(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => { scrollToBottom(); }, [messages, scrollToBottom]);
  useEffect(() => { if (status === 'idle') inputRef.current?.focus(); }, [status]);

  // Load published agents
  useEffect(() => {
    api.agents.list().then(res => {
      const published = (res.agents || []).filter(a => a.status === 'PUBLISHED');
      setAgents(published);
      if (published.length > 0 && !selectedAgentId) {
        setSelectedAgentId(published[0].id);
      }
    }).catch(console.error);
  }, []);

  const handleSSEEvent = useCallback((event: SseEvent) => {
    try {
      const data = JSON.parse(event.data);

      switch (event.type) {
        case 'text_chunk': {
          const chunk = data.text || data.chunk || '';
          if (chunk) {
            streamingContentRef.current += chunk;
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') updated[updated.length - 1] = { ...last, content: streamingContentRef.current };
              return updated;
            });
          }
          break;
        }
        case 'reasoning_chunk': {
          const chunk = data.text || data.chunk || '';
          if (chunk) {
            streamingThinkingRef.current += chunk;
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') updated[updated.length - 1] = { ...last, thinking: streamingThinkingRef.current };
              return updated;
            });
          }
          break;
        }
        case 'tool_start': {
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') {
              const tools = [...(last.tools || [])];
              tools.push({ type: 'start', tool: data.name || data.tool, callId: data.callId || data.call_id, arguments: data.arguments });
              updated[updated.length - 1] = { ...last, tools };
            }
            return updated;
          });
          break;
        }
        case 'tool_result': {
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') {
              const tools = [...(last.tools || [])];
              const idx = tools.findLastIndex(t => t.callId === (data.callId || data.call_id));
              if (idx >= 0) tools[idx] = { ...tools[idx], type: 'result', result: data.result, resultStatus: data.status || 'COMPLETED' };
              updated[updated.length - 1] = { ...last, tools };
            }
            return updated;
          });
          break;
        }
        case 'tool_approval_request': {
          const info: AwaitInfo = {
            callId: data.callId || data.call_id,
            tool: data.name || data.tool,
            arguments: data.arguments || '',
          };
          setAwaitInfo(info);
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') updated[updated.length - 1] = { ...last, approval: info };
            return updated;
          });
          break;
        }
        case 'turn_complete': {
          setStatus('idle');
          setAwaitInfo(null);
          break;
        }
        case 'error': {
          const errMsg = data.message || data.error || 'Unknown error';
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') updated[updated.length - 1] = { ...last, content: `Error: ${errMsg}` };
            return updated;
          });
          setStatus('idle');
          break;
        }
      }
    } catch {
      // ignore parse errors
    }
  }, []);

  const ensureSession = async (): Promise<string> => {
    if (sessionId) return sessionId;
    const res = await sessionApi.create(selectedAgentId);
    const id = res.sessionId;
    setSessionId(id);

    // Connect SSE
    const controller = sessionApi.connectSSE(id, handleSSEEvent, (err) => {
      console.error('SSE error:', err);
    });
    sseControllerRef.current = controller;
    return id;
  };

  const handleSend = async () => {
    const text = input.trim();
    if (!text || status !== 'idle' || !selectedAgentId) return;

    setInput('');
    setMessages(prev => [...prev, { role: 'user', content: text }]);
    setStatus('running');
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    setMessages(prev => [...prev, { role: 'agent', content: '', thinking: '', tools: [] }]);

    try {
      const sid = await ensureSession();
      await sessionApi.sendMessage(sid, text);
    } catch (err) {
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent') updated[updated.length - 1] = { ...last, content: `Error: ${err}` };
        return updated;
      });
      setStatus('idle');
    }
  };

  const handleApproval = async (decision: 'APPROVE' | 'DENY') => {
    if (!sessionId || !awaitInfo) return;
    try {
      await sessionApi.approve(sessionId, awaitInfo.callId, decision);
      setAwaitInfo(null);
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent') updated[updated.length - 1] = { ...last, approval: undefined };
        return updated;
      });
    } catch (err) {
      console.error('approval failed:', err);
    }
  };

  const handleCancel = async () => {
    if (!sessionId) return;
    try { await sessionApi.cancel(sessionId); } catch { /* ignore */ }
  };

  const handleNewChat = () => {
    // Close existing SSE and session
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    if (sessionId) sessionApi.close(sessionId).catch(() => {});
    setSessionId(null);
    setMessages([]);
    setStatus('idle');
    setAwaitInfo(null);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const selectedAgent = agents.find(a => a.id === selectedAgentId);

  return (
    <div className="flex flex-col h-full">
      {/* Top bar: agent selector */}
      <div className="border-b px-6 py-3 flex items-center justify-between"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-3">
          <select value={selectedAgentId}
            onChange={e => { handleNewChat(); setSelectedAgentId(e.target.value); }}
            disabled={status === 'running'}
            className="px-3 py-2 rounded-lg border text-sm outline-none"
            style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
            {agents.length === 0 && <option value="">No published agents</option>}
            {agents.map(a => (
              <option key={a.id} value={a.id}>{a.name}</option>
            ))}
          </select>
          {selectedAgent && (
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {selectedAgent.model || 'default model'}
              {selectedAgent.description && ` · ${selectedAgent.description}`}
            </span>
          )}
        </div>
        <button onClick={handleNewChat}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm cursor-pointer"
          style={{ background: 'var(--color-primary)', color: 'white' }}>
          <Plus size={14} /> New Chat
        </button>
      </div>

      {/* Chat messages */}
      <div className="flex-1 overflow-auto p-6">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full gap-4"
            style={{ color: 'var(--color-text-secondary)' }}>
            <Bot size={48} strokeWidth={1.5} />
            <div className="text-lg font-medium">{selectedAgent?.name || 'AI Assistant'}</div>
            <div className="text-sm">Send a message to start</div>
          </div>
        )}
        <div className="max-w-4xl mx-auto flex flex-col gap-4">
          {messages.filter(msg => msg.role === 'user' || msg.content?.trim() || msg.thinking || (msg.tools && msg.tools.length > 0) || msg.approval).map((msg, i) => (
            <div key={i} className={`flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`}>
              {msg.role === 'agent' && (
                <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  <Bot size={18} />
                </div>
              )}
              <div className={`max-w-[80%] ${msg.role === 'user' ? 'order-first' : ''}`}>
                {msg.thinking && <ThinkingBlock thinking={msg.thinking} isStreaming={status === 'running' && i === messages.length - 1 && !msg.content} />}
                {msg.tools && msg.tools.length > 0 && <ToolsBlock tools={msg.tools} />}
                <div className="rounded-xl px-4 py-3 text-sm overflow-x-auto"
                  style={{
                    background: msg.role === 'user' ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                    color: msg.role === 'user' ? 'white' : 'var(--color-text)',
                    border: msg.role === 'agent' ? '1px solid var(--color-border)' : 'none',
                  }}>
                  <div className="whitespace-pre-wrap font-[inherit] m-0 [&_pre]:bg-[var(--color-bg-tertiary)] [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto [&_code]:text-[inherit]">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
                  </div>
                  {status === 'running' && msg.role === 'agent' && i === messages.length - 1 && !msg.content && (
                    <Loader2 size={16} className="animate-spin" style={{ color: 'var(--color-text-secondary)' }} />
                  )}
                </div>
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
                      <button onClick={() => handleApproval('APPROVE')}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
                        style={{ background: 'var(--color-success)', color: 'white' }}>
                        <Shield size={14} /> Approve
                      </button>
                      <button onClick={() => handleApproval('DENY')}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
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

      {/* Input area */}
      <div className="border-t p-4" style={{ borderColor: 'var(--color-border)' }}>
        <div className="max-w-4xl mx-auto flex gap-3 items-end">
          <textarea
            ref={inputRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={selectedAgentId ? 'Send a message...' : 'Select an agent first'}
            rows={1}
            className="flex-1 rounded-xl border px-4 py-3 text-sm resize-none focus:outline-none"
            style={{
              background: 'var(--color-bg-secondary)',
              borderColor: 'var(--color-border)',
              color: 'var(--color-text)',
            }}
            disabled={status !== 'idle' || !selectedAgentId}
          />
          {status === 'idle' ? (
            <button onClick={handleSend}
              disabled={!input.trim() || !selectedAgentId}
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
