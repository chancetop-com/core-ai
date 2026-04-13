import { useState, useRef, useEffect, useCallback } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Send, Square, Shield, ShieldOff, Loader2, Bot, User, ChevronDown, ChevronRight, Brain, Wrench, Plus, ListTodo, Sparkles } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type { SseEvent } from '../../api/session';
import { api } from '../../api/client';
import type { AgentDefinition, ToolRegistryView, SkillDefinition, ToolRef } from '../../api/client';
import ResourcePicker from './ResourcePicker';

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

interface PlanTodo {
  content: string;
  status: string;
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
  const [expandedResults, setExpandedResults] = useState<Set<number>>(new Set());
  const toggleResult = (idx: number) => {
    setExpandedResults(prev => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx); else next.add(idx);
      return next;
    });
  };
  const formatJson = (s: string) => {
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
  };
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
            <div key={j} className="rounded-lg overflow-hidden"
              style={{ background: 'var(--color-bg-secondary)' }}>
              <div className="px-3 py-2 flex items-center gap-2"
                style={{ color: 'var(--color-text-secondary)' }}>
                <span className="font-mono font-medium" style={{ color: 'var(--color-primary)' }}>{t.tool}</span>
                {t.type === 'start' && t.arguments && (
                  <span className="opacity-70 truncate">{t.arguments.length > 80 ? t.arguments.slice(0, 80) + '...' : t.arguments}</span>
                )}
                {t.type === 'result' && (
                  <>
                    <span style={{ color: t.resultStatus === 'COMPLETED' ? 'var(--color-success)' : 'var(--color-error)' }}>
                      {t.resultStatus === 'COMPLETED' ? '✓ done' : t.resultStatus}
                    </span>
                    {t.result && (
                      <button onClick={() => toggleResult(j)}
                        className="ml-auto flex items-center gap-0.5 cursor-pointer opacity-60 hover:opacity-100"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        {expandedResults.has(j) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        <span>detail</span>
                      </button>
                    )}
                  </>
                )}
              </div>
              {t.type === 'result' && t.result && expandedResults.has(j) && (
                <div className="border-t px-3 py-2" style={{ borderColor: 'var(--color-border)' }}>
                  <pre className="whitespace-pre-wrap font-mono overflow-auto"
                    style={{ color: 'var(--color-text-secondary)', fontSize: '11px', maxHeight: '200px' }}>
                    {formatJson(t.result)}
                  </pre>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function PlanUpdateBlock({ todos }: { todos: PlanTodo[] }) {
  const [expanded, setExpanded] = useState(true);
  const statusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'var(--color-success)';
      case 'IN_PROGRESS': return 'var(--color-warning)';
      default: return 'var(--color-text-muted)';
    }
  };
  const statusLabel = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'Done';
      case 'IN_PROGRESS': return 'In Progress';
      default: return 'Pending';
    }
  };

  return (
    <div className="mb-3 rounded-xl border text-xs"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      <button onClick={() => setExpanded(e => !e)}
        className="flex items-center gap-1.5 w-full px-3 py-2 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <ListTodo size={14} />
        <span className="font-medium">Planning ({todos.filter(t => t.status === 'COMPLETED').length}/{todos.length})</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <table className="w-full text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            <thead>
              <tr style={{ color: 'var(--color-text-muted)', borderBottom: '1px solid var(--color-border)' }}>
                <th className="text-left py-1.5 pr-3 font-medium">Status</th>
                <th className="text-left py-1.5 font-medium">Task</th>
              </tr>
            </thead>
            <tbody>
              {todos.map((t, j) => (
                <tr key={j} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
                  <td className="py-1.5 pr-3 whitespace-nowrap">
                    <span style={{ color: statusColor(t.status) }}>
                      {t.status === 'COMPLETED' ? '\u2713 ' : t.status === 'IN_PROGRESS' ? '\u25B6 ' : '\u25CB '}{statusLabel(t.status)}
                    </span>
                  </td>
                  <td className="py-1.5" style={{ color: 'var(--color-text)' }}>{t.content}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function Chat() {
  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    try { const s = sessionStorage.getItem('chat_messages'); return s ? JSON.parse(s) : []; } catch { return []; }
  });
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<'idle' | 'running'>('idle');
  const [awaitInfo, setAwaitInfo] = useState<AwaitInfo | null>(null);
  const [planTodos, setPlanTodos] = useState<PlanTodo[] | null>(null);
  const [compressionInfo, setCompressionInfo] = useState<{ before: number; after: number } | null>(null);

  // Agent selection
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string>(() => sessionStorage.getItem('chat_agentId') || '');
  const [sessionId, setSessionId] = useState<string | null>(() => sessionStorage.getItem('chat_sessionId'));

  // Loaded tools/skills (confirmed on server)
  const [loadedToolIds, setLoadedToolIds] = useState<Set<string>>(new Set());
  const [loadedSkillIds, setLoadedSkillIds] = useState<Set<string>>(new Set());
  // Pre-session selections (shown as chips, will be sent on session create)
  const [preToolIds, setPreToolIds] = useState<Set<string>>(new Set());
  const [preSkillIds, setPreSkillIds] = useState<Set<string>>(new Set());
  const [availableTools, setAvailableTools] = useState<ToolRegistryView[]>([]);
  const [availableSkills, setAvailableSkills] = useState<SkillDefinition[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [skillsLoading, setSkillsLoading] = useState(false);
  const [showToolPicker, setShowToolPicker] = useState(false);
  const [showSkillPicker, setShowSkillPicker] = useState(false);
  const [selectedToolIds, setSelectedToolIds] = useState<Set<string>>(new Set());
  const [selectedSkillIds, setSelectedSkillIds] = useState<Set<string>>(new Set());
  const [toast, setToast] = useState<string | null>(null);

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

  // Persist chat state
  useEffect(() => {
    if (messages.length > 0) sessionStorage.setItem('chat_messages', JSON.stringify(messages));
    else sessionStorage.removeItem('chat_messages');
  }, [messages]);
  useEffect(() => {
    if (sessionId) sessionStorage.setItem('chat_sessionId', sessionId);
    else sessionStorage.removeItem('chat_sessionId');
  }, [sessionId]);
  useEffect(() => {
    if (selectedAgentId) sessionStorage.setItem('chat_agentId', selectedAgentId);
  }, [selectedAgentId]);

  // Load published agents
  useEffect(() => {
    api.agents.list().then(res => {
      // Include both AGENT type (normal) and local type (CLI mode)
      const published = (res.agents || []).filter(a => a.status === 'PUBLISHED' && (a.type === 'AGENT' || a.type === 'local'));
      setAgents(published);
      if (published.length > 0) {
        setSelectedAgentId(prev => prev || published[0].id);
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
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') updated[updated.length - 1] = { ...last, content: (last.content || '') + chunk };
              return updated;
            });
          }
          break;
        }
        case 'reasoning_chunk': {
          const chunk = data.text || data.chunk || '';
          if (chunk) {
            setMessages(prev => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last?.role === 'agent') updated[updated.length - 1] = { ...last, thinking: (last.thinking || '') + chunk };
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
        case 'plan_update': {
          if (data.todos && Array.isArray(data.todos)) {
            setPlanTodos(data.todos);
          }
          break;
        }
        case 'compression': {
          if (data.completed) {
            setCompressionInfo({ before: data.before_count, after: data.after_count });
            setTimeout(() => setCompressionInfo(null), 5000);
          }
          break;
        }
      }
    } catch {
      // ignore parse errors
    }
  }, []);

  // Connect SSE - single source of truth
  const doConnectSSE = useCallback((sid: string) => {
    if (sseControllerRef.current) {
      sseControllerRef.current.abort();
      sseControllerRef.current = null;
    }
    const controller = sessionApi.connectSSE(
      sid,
      handleSSEEvent,
      (err) => {
        console.error('SSE error:', err);
        sseControllerRef.current = null;
        const msg = err instanceof Error ? err.message : String(err);
        showToast(`Connection lost: ${msg}. Please retry.`);
        setStatus('idle');
        setMessages(prev => {
          if (prev.length === 0) return prev;
          const last = prev[prev.length - 1];
          if (last.role !== 'agent' || last.content || last.thinking) return prev;
          const updated = [...prev];
          updated[updated.length - 1] = { ...last, content: `Error: ${msg}` };
          return updated;
        });
      },
      () => {
        sseControllerRef.current = null;
      },
    );
    sseControllerRef.current = controller;
  }, [handleSSEEvent, showToast]);

  // Only useEffect manages SSE lifecycle - prevents double connections
  useEffect(() => {
    if (sessionId && !sseControllerRef.current) {
      doConnectSSE(sessionId);
    }
    return () => {
      sseControllerRef.current?.abort();
      sseControllerRef.current = null;
    };
  }, [sessionId, doConnectSSE]);

  const ensureSession = async (): Promise<string> => {
    if (sessionId) {
      // Reconnect SSE if connection was dropped (e.g. server closed after idle)
      if (!sseControllerRef.current) {
        doConnectSSE(sessionId);
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      return sessionId;
    }

    // Pass pre-selected tools/skills to session creation
    const preToolIdsArr = Array.from(preToolIds).map(id => ({ id } as ToolRef));
    const preSkillIdsArr = Array.from(preSkillIds);
    const res = await sessionApi.create(selectedAgentId, {
      tools: preToolIdsArr.length > 0 ? preToolIdsArr : undefined,
      skill_ids: preSkillIdsArr.length > 0 ? preSkillIdsArr : undefined,
    });
    const id = res.sessionId;
    setSessionId(id);

    // Update loaded state from server response
    if (res.loaded_tools && res.loaded_tools.length > 0) {
      setLoadedToolIds(new Set(res.loaded_tools));
      showToast(`Loaded ${res.loaded_tools.length} tool(s)`);
    }
    if (res.loaded_skills && res.loaded_skills.length > 0) {
      setLoadedSkillIds(new Set(res.loaded_skills));
      showToast(`Loaded ${res.loaded_skills.length} skill(s)`);
    }
    setPreToolIds(new Set());
    setPreSkillIds(new Set());

    // Wait for SSE to connect
    await new Promise(resolve => setTimeout(resolve, 500));
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
      const msg = err instanceof Error ? err.message : String(err);
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent') updated[updated.length - 1] = { ...last, content: `Error: ${msg}` };
        return updated;
      });
      setStatus('idle');
      showToast(`Send failed: ${msg}. Please retry.`);
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
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    if (sessionId) sessionApi.close(sessionId).catch(() => {});
    setSessionId(null);
    setMessages([]);
    setStatus('idle');
    setAwaitInfo(null);
    setPlanTodos(null);
    setLoadedToolIds(new Set());
    setLoadedSkillIds(new Set());
    setPreToolIds(new Set());
    setPreSkillIds(new Set());
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    sessionStorage.removeItem('chat_messages');
    sessionStorage.removeItem('chat_sessionId');
  };

  // Show a brief toast notification
  const showToast = useCallback((msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 2500);
  }, []);

  // Fetch available tools for picker
  const fetchTools = useCallback(async () => {
    setToolsLoading(true);
    try {
      const res = await api.tools.list();
      setAvailableTools((res.tools || []).filter(t => t.enabled));
    } catch (err) {
      console.error('Failed to fetch tools:', err);
    } finally {
      setToolsLoading(false);
    }
  }, []);

  // Derived value: selected agent
  const selectedAgent = agents.find(a => a.id === selectedAgentId);

  // Fetch available skills for picker
  const fetchSkills = useCallback(async () => {
    setSkillsLoading(true);
    try {
      const res = await api.skills.list();
      setAvailableSkills(res.skills || []);
    } catch (err) {
      console.error('Failed to fetch skills:', err);
    } finally {
      setSkillsLoading(false);
    }
  }, []);

  // Open tool picker
  const openToolPicker = useCallback(() => {
    // Start with agent's configured tools
    const agentToolIds = selectedAgent?.tools.map(t => t.id) || [];
    // Merge with session tools (loaded or pre-selected)
    const sessionToolIds = sessionId ? loadedToolIds : preToolIds;
    setSelectedToolIds(new Set([...agentToolIds, ...sessionToolIds]));
    fetchTools();
    setShowToolPicker(true);
  }, [sessionId, selectedAgent, loadedToolIds, preToolIds, fetchTools]);

  // Open skill picker
  const openSkillPicker = useCallback(() => {
    setSelectedSkillIds(sessionId ? loadedSkillIds : preSkillIds);
    fetchSkills();
    setShowSkillPicker(true);
  }, [sessionId, loadedSkillIds, preSkillIds, fetchSkills]);

  // Toggle tool selection
  const toggleTool = useCallback((id: string) => {
    setSelectedToolIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // Toggle skill selection
  const toggleSkill = useCallback((id: string) => {
    setSelectedSkillIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // Confirm tool selection — save as pre-session or call API directly
  const loadSelectedTools = useCallback(async () => {
    if (selectedToolIds.size === 0) {
      setShowToolPicker(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreToolIds(new Set(selectedToolIds));
      showToast(`Selected ${selectedToolIds.size} tool(s), will load on first message`);
      setShowToolPicker(false);
      setSelectedToolIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadTools(sessionId, Array.from(selectedToolIds).map(id => ({ id } as ToolRef)));
      if (res.loaded_tools && res.loaded_tools.length > 0) {
        setLoadedToolIds(prev => {
          const next = new Set(prev);
          for (const name of res.loaded_tools) next.add(name);
          return next;
        });
        showToast(`Loaded ${res.loaded_tools.length} tool(s)`);
      }
      setShowToolPicker(false);
      setSelectedToolIds(new Set());
    } catch (err) {
      console.error('Failed to load tools:', err);
      showToast('Failed to load tools');
    }
  }, [sessionId, selectedToolIds, showToast]);

  // Confirm skill selection — save as pre-session or call API directly
  const loadSelectedSkills = useCallback(async () => {
    if (selectedSkillIds.size === 0) {
      setShowSkillPicker(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreSkillIds(new Set(selectedSkillIds));
      showToast(`Selected ${selectedSkillIds.size} skill(s), will load on first message`);
      setShowSkillPicker(false);
      setSelectedSkillIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadSkills(sessionId, Array.from(selectedSkillIds));
      if (res.loaded_skills && res.loaded_skills.length > 0) {
        setLoadedSkillIds(prev => {
          const next = new Set(prev);
          for (const name of res.loaded_skills) next.add(name);
          return next;
        });
        showToast(`Loaded ${res.loaded_skills.length} skill(s)`);
      }
      setShowSkillPicker(false);
      setSelectedSkillIds(new Set());
    } catch (err) {
      console.error('Failed to load skills:', err);
      showToast('Failed to load skills');
    }
  }, [sessionId, selectedSkillIds, showToast]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

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
          {compressionInfo && (
            <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-xs animate-pulse"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-muted)', border: '1px solid var(--color-border)' }}>
              <Sparkles size={14} />
              <span>Context compressed: {compressionInfo.before} → {compressionInfo.after} messages</span>
            </div>
          )}
          {planTodos && planTodos.length > 0 && (
            <PlanUpdateBlock todos={planTodos} />
          )}
          {messages.filter((msg, idx) => msg.role === 'user' || msg.content?.trim() || msg.thinking || (msg.tools && msg.tools.length > 0) || msg.approval || (status === 'running' && msg.role === 'agent' && idx === messages.length - 1)).map((msg, i) => (
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
                  {status === 'running' && msg.role === 'agent' && i === messages.length - 1 && !msg.content && !msg.thinking && !(msg.tools && msg.tools.length > 0) && (
                    <div className="flex items-center gap-2 py-1">
                      <Loader2 size={16} className="animate-spin" style={{ color: 'var(--color-primary)' }} />
                      <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Thinking...</span>
                    </div>
                  )}
                  {status === 'running' && msg.role === 'agent' && i === messages.length - 1 && msg.content && (
                    <span className="inline-block w-2 h-4 ml-0.5 animate-pulse rounded-sm align-middle" style={{ background: 'var(--color-primary)' }} />
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
        <div className="max-w-4xl mx-auto">
          {/* Loaded / pre-selected tools/skills chips */}
          {(loadedToolIds.size > 0 || loadedSkillIds.size > 0 || preToolIds.size > 0 || preSkillIds.size > 0) && (
            <div className="flex flex-wrap gap-1.5 mb-2 min-h-[24px]">
              {Array.from(loadedToolIds).map(name => (
                <span key={`t-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: 'var(--color-primary)' + '18',
                    color: 'var(--color-primary)',
                    border: '1px solid var(--color-primary)' + '30',
                  }}>
                  <Wrench size={10} />
                  {name}
                </span>
              ))}
              {Array.from(preToolIds).map(name => (
                <span key={`pt-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: 'var(--color-primary)' + '08',
                    color: 'var(--color-text-muted)',
                    border: '1px dashed var(--color-border)',
                  }}>
                  <Wrench size={10} />
                  {name}
                  <span className="ml-0.5 opacity-60">(pending)</span>
                </span>
              ))}
              {Array.from(loadedSkillIds).map(name => (
                <span key={`s-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: 'var(--color-warning)' + '18',
                    color: 'var(--color-warning)',
                    border: '1px solid var(--color-warning)' + '30',
                  }}>
                  <Sparkles size={10} />
                  {name}
                </span>
              ))}
              {Array.from(preSkillIds).map(name => (
                <span key={`ps-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: 'var(--color-warning)' + '08',
                    color: 'var(--color-text-muted)',
                    border: '1px dashed var(--color-border)',
                  }}>
                  <Sparkles size={10} />
                  {name}
                  <span className="ml-0.5 opacity-60">(pending)</span>
                </span>
              ))}
            </div>
          )}

          <div className="flex gap-2 items-end">
            {/* Tool picker button — enabled when agent is selected */}
            <button
              onClick={openToolPicker}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: (loadedToolIds.size > 0 || preToolIds.size > 0) ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
                border: '1px solid var(--color-border)',
                color: (loadedToolIds.size > 0 || preToolIds.size > 0) ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              }}
              title="Load tools">
              <Wrench size={18} />
            </button>

            {/* Skill picker button — enabled when agent is selected */}
            <button
              onClick={openSkillPicker}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: (loadedSkillIds.size > 0 || preSkillIds.size > 0) ? 'var(--color-warning)' + '20' : 'var(--color-bg-tertiary)',
                border: '1px solid var(--color-border)',
                color: (loadedSkillIds.size > 0 || preSkillIds.size > 0) ? 'var(--color-warning)' : 'var(--color-text-secondary)',
              }}
              title="Load skills">
              <Sparkles size={18} />
            </button>

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

      {/* Toast notification */}
      {toast && (
        <div className="fixed bottom-24 left-1/2 -translate-x-1/2 z-50 px-4 py-2 rounded-lg text-sm shadow-lg"
          style={{ background: 'var(--color-bg)', color: 'var(--color-text)', border: '1px solid var(--color-border)' }}>
          {toast}
        </div>
      )}

      {/* Tool Picker Modal */}
      {showToolPicker && (
        <ResourcePicker
          title="Load Tools"
          items={availableTools.map(t => ({
            id: t.id,
            name: t.name,
            description: t.description,
            type: t.type,
            category: t.category,
          }))}
          loading={toolsLoading}
          loadedIds={loadedToolIds}
          pendingIds={preToolIds}
          selectedIds={selectedToolIds}
          onToggle={toggleTool}
          onLoad={loadSelectedTools}
          onClose={() => setShowToolPicker(false)}
        />
      )}

      {/* Skill Picker Modal */}
      {showSkillPicker && (
        <ResourcePicker
          title="Load Skills"
          items={availableSkills.map(s => ({
            id: s.id,
            name: s.qualified_name,
            description: s.description,
            type: s.source_type,
          }))}
          loading={skillsLoading}
          loadedIds={loadedSkillIds}
          pendingIds={preSkillIds}
          selectedIds={selectedSkillIds}
          onToggle={toggleSkill}
          onLoad={loadSelectedSkills}
          onClose={() => setShowSkillPicker(false)}
        />
      )}
    </div>
  );
}
