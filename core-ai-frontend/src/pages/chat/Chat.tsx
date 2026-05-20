import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import type { PluggableList } from 'unified';
import { chatSanitizeSchema } from './markdownSanitizeSchema';
import { Send, Square, Shield, ShieldOff, Loader2, Bot, User, ChevronDown, ChevronRight, Wrench, Sparkles, Users, Check, Search, Star, Mic, Maximize2, Minimize2, Paperclip, X } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { sessionApi } from '../../api/session';
import type { SseEvent, SseTextChunkEvent, SseReasoningChunkEvent, SseToolStartEvent, SseToolResultEvent, SseToolApprovalRequestEvent, SsePlanUpdateEvent, SseCompressionEvent, SseErrorEvent, SseStatusChangeEvent, ChatSessionSummary, SessionArtifact } from '../../api/session';
import { api } from '../../api/client';
import type { AgentDefinition, ToolRegistryView, SkillDefinition, ToolRef } from '../../api/client';
import ResourcePicker from './ResourcePicker';
import ToolPickerModal from './ToolPickerModal';
import ChatSessionsSidebar from './ChatSessionsSidebar';
import VoiceTranscriberSidebar from './components/VoiceTranscriberSidebar';
import ArtifactDrawer from './components/ArtifactDrawer';
import ArtifactCard from './components/ArtifactCard';
import AuthedImage from './components/AuthedImage';
import type { ArtifactSpec } from './components/artifactTypes';
import { useSpeechRecognition } from './hooks/useSpeechRecognition';
import type { AwaitInfo, ChatMessage, ToolEvent, PlanTodo, MessageSegment, ToolsSegment } from './types';
import { historyToChatMessages, getMessageText } from './utils';
import ToolsBlock from './components/ToolsBlock';
import CopyButton from './components/CopyButton';
import ThinkingBlock from './components/ThinkingBlock';
import PlanUpdateBlock from './components/PlanUpdateBlock';

function hasTextSegments(segments?: MessageSegment[]): boolean {
  return segments?.some(s => s.type === 'text' && s.content.trim()) ?? false;
}

function hasAnySegments(segments?: MessageSegment[]): boolean {
  return segments != null && segments.length > 0;
}

function getMessageArtifacts(msg: ChatMessage, all: { file_id: string; file_name: string; content_type?: string; size?: number; title?: string }[]): typeof all {
  const toolsSeg = msg.segments?.find(s => s.type === 'tools') as ToolsSegment | undefined;
  if (!toolsSeg) return [];
  const fileIds = new Set<string>();
  for (const t of toolsSeg.tools) {
    if (t.tool === 'submit_artifacts' && t.type === 'result' && t.result) {
      try {
        const parsed = JSON.parse(t.result);
        const submitted = Array.isArray(parsed?.submitted) ? parsed.submitted : [];
        for (const s of submitted) {
          if (s?.file_id) fileIds.add(s.file_id);
        }
      } catch {
        // ignore non-JSON tool result
      }
    }
  }
  if (fileIds.size === 0) return [];
  return all.filter(a => fileIds.has(a.file_id));
}

// Module-level stable references so React-Markdown doesn't tear down subtrees on re-render.
// rehype-raw lets inline HTML/SVG from the LLM through; rehype-sanitize gates it with a strict
// whitelist. Only enabled on finished agent messages (see isStreamingLast below) — running a full
// parse5 reparse per streaming token would tank performance on multi-KB SVG payloads.
const AGENT_REHYPE_PLUGINS: PluggableList = [rehypeRaw, [rehypeSanitize, chatSanitizeSchema]];

export default function Chat() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    try { const s = sessionStorage.getItem('chat_messages'); return s ? JSON.parse(s) : []; } catch { return []; }
  });
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<'idle' | 'running'>('idle');
  const [isThinking, setIsThinking] = useState(false);
  const [awaitInfo, setAwaitInfo] = useState<AwaitInfo | null>(null);
  const [planTodos, setPlanTodos] = useState<PlanTodo[] | null>(null);
  const [compressionInfo, setCompressionInfo] = useState<{ before: number; after: number } | null>(null);

  // Agent selection
  const [myAgents, setMyAgents] = useState<AgentDefinition[]>([]);
  const [otherAgents, setOtherAgents] = useState<AgentDefinition[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string>(() => sessionStorage.getItem('chat_agentId') || '');
  const [sessionId, setSessionId] = useState<string | null>(() => sessionStorage.getItem('chat_sessionId'));
  const [variableValues, setVariableValues] = useState<Record<string, string>>({});
  const [agentDropdownOpen, setAgentDropdownOpen] = useState(false);
  const [agentSearchQuery, setAgentSearchQuery] = useState('');
  const agentDropdownRef = useRef<HTMLDivElement>(null);

  // Loaded tools/skills (confirmed on server)
  const [loadedToolIds, setLoadedToolIds] = useState<Set<string>>(new Set());
  const [loadedSkillIds, setLoadedSkillIds] = useState<Set<string>>(new Set());
  const [loadedSubAgentIds, setLoadedSubAgentIds] = useState<Set<string>>(new Set());
  // Pre-session selections (shown as chips, will be sent on session create)
  const [preToolIds, setPreToolIds] = useState<Set<string>>(new Set());
  const [preSkillIds, setPreSkillIds] = useState<Set<string>>(new Set());
  const [preSubAgentIds, setPreSubAgentIds] = useState<Set<string>>(new Set());
  const [availableTools, setAvailableTools] = useState<ToolRegistryView[]>([]);
  const [availableSkills, setAvailableSkills] = useState<SkillDefinition[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [skillsLoading, setSkillsLoading] = useState(false);
  const [showToolPicker, setShowToolPicker] = useState(false);
  const [showSkillPicker, setShowSkillPicker] = useState(false);
  const [showAgentPicker, setShowAgentPicker] = useState(false);
  const [selectedToolIds, setSelectedToolIds] = useState<Set<string>>(new Set());
  const [selectedSkillIds, setSelectedSkillIds] = useState<Set<string>>(new Set());
  const [selectedAgentIds, setSelectedAgentIds] = useState<Set<string>>(new Set());
  const [toast, setToast] = useState<string | null>(null);
  const [showVoiceSidebar, setShowVoiceSidebar] = useState(false);
  const [activeArtifact, setActiveArtifact] = useState<ArtifactSpec | null>(null);
  const [sessionArtifacts, setSessionArtifacts] = useState<SessionArtifact[]>(() => {
    try { const s = sessionStorage.getItem('chat_artifacts'); return s ? JSON.parse(s) : []; } catch { return []; }
  });
  const openArtifact = useCallback((spec: ArtifactSpec) => {
    setShowVoiceSidebar(false);
    setActiveArtifact(spec);
  }, []);
  const openVoiceSidebar = useCallback(() => {
    setActiveArtifact(null);
    setShowVoiceSidebar(true);
  }, []);

  // Stable ReactMarkdown component overrides — inline arrow functions would change identity on every
  // Chat render and force React to unmount/remount AuthedImage (which would abort its in-flight fetch
  // and re-trigger loading). useMemo keyed on openArtifact (itself stable via useCallback) keeps the
  // object reference stable so message-level markdown subtrees are not recreated.
  const agentMarkdownComponents = useMemo(() => ({
    code({ inline, className, children, ...props }: { inline?: boolean; className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLElement>) {
      const match = /language-(\w+)/.exec(className || '');
      if (!inline && match) {
        const lang = match[1].toLowerCase();
        const codeText = String(children ?? '').replace(/\n$/, '');
        if (lang === 'html' || lang === 'svg') {
          return (
            <ArtifactCard
              artifact={{ kind: lang, language: lang, title: lang === 'html' ? 'HTML page' : 'SVG image', content: codeText }}
              onOpen={openArtifact}
            />
          );
        }
        return (
          <ArtifactCard
            artifact={{ kind: 'code', language: lang, title: `${lang} snippet`, content: codeText }}
            onOpen={openArtifact}
          />
        );
      }
      return <code className={className} {...props}>{children}</code>;
    },
    img({ src, alt }: { src?: string; alt?: string }) {
      const isAbsolute = !!src && (/^(https?:|data:|blob:|\/api\/)/.test(src) || src.startsWith('/'));
      if (isAbsolute) {
        return <AuthedImage src={src} alt={alt} />;
      }
      return (
        <span className="my-2 inline-flex items-center gap-2 px-3 py-2 rounded-lg border text-xs"
          style={{ borderColor: 'var(--color-warning)', background: 'var(--color-warning)' + '12', color: 'var(--color-text-secondary)' }}
          title={src}>
          <span style={{ color: 'var(--color-warning)' }}>⚠</span>
          <span>Image <code style={{ color: 'var(--color-text)' }}>{src || alt || '?'}</code> not available — agent must call <code>submit_artifacts</code> first.</span>
        </span>
      );
    },
  }), [openArtifact]);
  const [voiceLanguage, setVoiceLanguage] = useState('zh-CN');
  const voice = useSpeechRecognition();
  const [variablesExpanded, setVariablesExpanded] = useState(false);
  const [chipsExpanded, setChipsExpanded] = useState(false);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const variablesPanelRef = useRef<HTMLDivElement>(null);
  const [isInputExpanded, setIsInputExpanded] = useState(false);
  const [needsExpand, setNeedsExpand] = useState(false);
  const sseControllerRef = useRef<AbortController | null>(null);
  const streamingContentRef = useRef('');
  const streamingThinkingRef = useRef('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // File upload state
  interface PendingAttachment {
    id: string;
    name: string;
    url: string;
    contentType: string;
    uploading: boolean;
  }
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);

  const handleFileSelect = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    for (const file of Array.from(files)) {
      const validTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml', 'application/pdf'];
      if (!validTypes.includes(file.type)) {
        showToast(`Unsupported file type: ${file.type}. Only images and PDFs are allowed.`);
        continue;
      }
      if (file.size > 50 * 1024 * 1024) {
        showToast(`File too large: ${file.name}. Maximum size is 50MB.`);
        continue;
      }

      const id = crypto.randomUUID();
      setPendingAttachments(prev => [...prev, {
        id, name: file.name, url: '', contentType: file.type, uploading: true,
      }]);

      try {
        const credRes = await fetch(`/api/blob/upload-credential?content_type=${encodeURIComponent(file.type)}`, {
          headers: { 'Authorization': `Bearer ${localStorage.getItem('apiKey')}` },
        });
        if (!credRes.ok) throw new Error(`Credential request failed: ${credRes.status}`);
        const cred = await credRes.json();

        const uploadRes = await fetch(cred.upload_url, {
          method: 'PUT',
          headers: {
            'x-ms-blob-type': 'BlockBlob',
            'x-ms-blob-content-type': file.type,
          },
          body: file,
        });
        if (!uploadRes.ok) throw new Error(`Upload failed: ${uploadRes.status}`);

        setPendingAttachments(prev => prev.map(a =>
          a.id === id ? { ...a, url: cred.blob_url, contentType: file.type, uploading: false } : a
        ));
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        showToast(`Upload failed for ${file.name}: ${msg}`);
        setPendingAttachments(prev => prev.filter(a => a.id !== id));
      }
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  const removeAttachment = useCallback((id: string) => {
    setPendingAttachments(prev => prev.filter(a => a.id !== id));
  }, []);

  // All published agents for chat (my + others, filtered by status)
  const agents = useMemo(() =>
    [...myAgents, ...otherAgents].filter(a => a.status === 'PUBLISHED' || a.type === 'local'),
    [myAgents, otherAgents]
  );

  const scrollToBottom = useCallback(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => { scrollToBottom(); }, [messages, scrollToBottom]);
  useEffect(() => { if (status === 'idle') inputRef.current?.focus(); }, [status]);

  // Auto-resize textarea when expanded, auto-grow in normal mode;
  // only show expand button when content exceeds ~4 lines
  useEffect(() => {
    const textarea = inputRef.current;
    if (!textarea) return;

    if (isInputExpanded) {
      textarea.style.height = 'auto';
      const maxHeight = (messagesContainerRef.current?.clientHeight ?? 600) / 3;
      const newHeight = Math.min(textarea.scrollHeight, maxHeight);
      textarea.style.height = `${newHeight}px`;
      textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
    } else {
      textarea.style.height = 'auto';
      textarea.style.overflowY = 'hidden';
      const lineHeight = parseFloat(getComputedStyle(textarea).lineHeight) || 20;
      const threshold = lineHeight * 4;
      const exceedsThreshold = textarea.scrollHeight > threshold;
      setNeedsExpand(exceedsThreshold);
      if (exceedsThreshold) {
        // Cap height at threshold and let user expand via button
        textarea.style.height = `${threshold}px`;
      } else {
        // Auto-grow naturally, no expand button needed
        textarea.style.height = `${Math.max(lineHeight, textarea.scrollHeight)}px`;
      }
    }
  }, [input, isInputExpanded]);

  // Persist chat state
  useEffect(() => {
    if (messages.length > 0) sessionStorage.setItem('chat_messages', JSON.stringify(messages));
    else sessionStorage.removeItem('chat_messages');
  }, [messages]);
  useEffect(() => {
    if (sessionArtifacts.length > 0) sessionStorage.setItem('chat_artifacts', JSON.stringify(sessionArtifacts));
    else sessionStorage.removeItem('chat_artifacts');
  }, [sessionArtifacts]);
  useEffect(() => {
    if (sessionId) sessionStorage.setItem('chat_sessionId', sessionId);
    else sessionStorage.removeItem('chat_sessionId');
  }, [sessionId]);
  useEffect(() => {
    if (selectedAgentId) sessionStorage.setItem('chat_agentId', selectedAgentId);
  }, [selectedAgentId]);

  useEffect(() => {
    setVariablesExpanded(false);
  }, [selectedAgentId]);

  useEffect(() => {
    if (!variablesExpanded) return;

    const handlePointerDown = (event: PointerEvent) => {
      const panel = variablesPanelRef.current;
      const target = event.target as Node | null;
      if (!panel || !target) return;
      if (!panel.contains(target)) {
        setVariablesExpanded(false);
      }
    };

    document.addEventListener('pointerdown', handlePointerDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [variablesExpanded]);

  // Close agent dropdown when clicking outside
  useEffect(() => {
    if (!agentDropdownOpen) return;

    const handlePointerDown = (event: PointerEvent) => {
      const dropdown = agentDropdownRef.current;
      const target = event.target as Node | null;
      if (!dropdown || !target) return;
      if (!dropdown.contains(target)) {
        setAgentDropdownOpen(false);
        setAgentSearchQuery('');
      }
    };

    document.addEventListener('pointerdown', handlePointerDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [agentDropdownOpen]);

  useEffect(() => {
    const agent = agents.find(a => a.id === selectedAgentId);
    const defaults = agent?.variables || {};
    const keys = Object.keys(defaults);
    if (keys.length === 0) {
      setVariableValues({});
      return;
    }
    setVariableValues(prev => {
      const next: Record<string, string> = {};
      for (const key of keys) {
        next[key] = prev[key] ?? String(defaults[key] ?? '');
      }
      return next;
    });
  }, [agents, selectedAgentId]);

  const [sidebarRefreshKey, setSidebarRefreshKey] = useState(0);

  const hydrateSession = useCallback(async (session: ChatSessionSummary) => {
    if (!session.id) return;
    // Reset all session-scoped state synchronously before fetching new history.
    // This guarantees any pending SSE callbacks from the previous session can't
    // bleed into the next one, and any open drawer is closed.
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    setSessionId(session.id);
    setMessages([]);
    setSessionArtifacts([]);
    setActiveArtifact(null);
    setAwaitInfo(null);
    setPlanTodos(null);
    setCompressionInfo(null);
    setIsThinking(false);
    setStatus('idle');
    try {
      const res = await sessionApi.history(session.id);
      setMessages(historyToChatMessages(res.messages || []));
      setSessionArtifacts(res.artifacts || []);
      if (session.agent_id && agents.some(a => a.id === session.agent_id)) {
        setSelectedAgentId(session.agent_id);
      }
    } catch (e) {
      console.warn('failed to hydrate session history', e);
    }
  }, [agents]);

  // Hydrate from URL ?sessionId=... — user returning from Sessions page
  useEffect(() => {
    const urlSessionId = searchParams.get('sessionId');
    if (!urlSessionId) return;
    if (sessionId === urlSessionId && messages.length > 0) return;
    let cancelled = false;
    (async () => {
      const session = await sessionApi.getSession(urlSessionId);
      await hydrateSession(session);
      if (!cancelled) setSearchParams({}, { replace: true });
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // Load agents (my agents + others for chat)
  useEffect(() => {
    Promise.all([
      api.agents.list(true),
      api.agents.list(false),
    ]).then(([myRes, otherRes]) => {
      setMyAgents(myRes.agents || []);
      setOtherAgents(otherRes.agents || []);
      // Auto-select first published agent if none selected
      const allAgents = [...(myRes.agents || []), ...(otherRes.agents || [])]
        .filter(a => a.status === 'PUBLISHED' || a.type === 'local');
      if (allAgents.length > 0 && !selectedAgentId) {
        setSelectedAgentId(allAgents[0].id);
      }
    }).catch(console.error);
  }, []);

  const handleSSEEvent = useCallback((event: SseEvent) => {
    switch (event.type) {
      case 'TEXT_CHUNK':
      case 'text_chunk': {
        const chunkEvent = event as SseTextChunkEvent;
        const chunk = chunkEvent.content || '';
        if (chunk) {
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') {
              const segments = [...(last.segments || [])];
              const lastSeg = segments[segments.length - 1];
              if (lastSeg?.type === 'text') {
                // Consecutive text: just append
                segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
              } else {
                // Non-consecutive: merge with any existing text segment
                const existingIdx = segments.findIndex(s => s.type === 'text');
                if (existingIdx >= 0) {
                  const existing = segments[existingIdx] as MessageSegment & { type: 'text' };
                  segments[existingIdx] = { ...existing, content: existing.content + chunk };
                } else {
                  segments.push({ type: 'text', content: chunk });
                }
              }
              updated[updated.length - 1] = { ...last, segments };
            }
            return updated;
          });
        }
        break;
      }
      case 'REASONING_CHUNK':
      case 'reasoning_chunk': {
        const chunkEvent = event as SseReasoningChunkEvent;
        const chunk = chunkEvent.content || '';
        const isFinalChunk = chunkEvent.is_final_chunk;

        if (!isFinalChunk && chunk) {
          setIsThinking(true);
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') {
              const segments = [...(last.segments || [])];
              const lastSeg = segments[segments.length - 1];
              if (lastSeg?.type === 'thinking') {
                // Consecutive thinking: just append
                segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
              } else {
                // Non-consecutive: merge with any existing thinking segment
                const existingIdx = segments.findIndex(s => s.type === 'thinking');
                if (existingIdx >= 0) {
                  const existing = segments[existingIdx] as MessageSegment & { type: 'thinking' };
                  segments[existingIdx] = { ...existing, content: existing.content + chunk };
                } else {
                  segments.push({ type: 'thinking', content: chunk });
                }
              }
              updated[updated.length - 1] = { ...last, segments };
            }
            return updated;
          });
        }
        break;
      }
      case 'TOOL_START':
      case 'tool_start': {
        setIsThinking(false);
        const toolEvent = event as SseToolStartEvent;
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'agent') {
            const segments = [...(last.segments || [])];
            // Find or reuse any existing tools segment
            let toolsSeg: ToolsSegment;
            let toolsSegIdx: number;
            const existingToolsIdx = segments.findIndex(s => s.type === 'tools');
            if (existingToolsIdx >= 0) {
              toolsSeg = segments[existingToolsIdx] as ToolsSegment;
              toolsSegIdx = existingToolsIdx;
            } else {
              toolsSeg = { type: 'tools', tools: [] };
              toolsSegIdx = segments.length;
              segments.push(toolsSeg);
            }

            const tools = [...toolsSeg.tools];
            const newTool: ToolEvent = {
              type: 'start', tool: toolEvent.tool_name, callId: toolEvent.call_id,
              arguments: toolEvent.tool_args ? JSON.stringify(toolEvent.tool_args) : undefined,
              taskId: toolEvent.task_id, runInBackground: toolEvent.run_in_background,
            };
            if (toolEvent.task_id) {
              // Try to find a parent with the same taskId (not callId)
              const parentIdx = tools.findIndex(t => t.taskId === toolEvent.task_id);
              if (parentIdx >= 0) {
                // Nest under existing parent
                const parent = { ...tools[parentIdx] };
                parent.children = [...(parent.children || []), newTool];
                tools[parentIdx] = parent;
                segments[toolsSegIdx] = { ...toolsSeg, tools };
                updated[updated.length - 1] = { ...last, segments };
                return updated;
              }
              // No parent yet — check if any existing top-level tools have the same taskId
              const orphanIdx = tools.findIndex(t => t.taskId === toolEvent.task_id && t.callId !== toolEvent.call_id);
              if (orphanIdx >= 0) {
                // Move the orphans under this new parent
                const orphans = tools.filter(t => t.taskId === toolEvent.task_id && t.callId !== toolEvent.call_id);
                const remaining = tools.filter(t => t.taskId !== toolEvent.task_id || t.callId === toolEvent.call_id);
                newTool.children = orphans;
                remaining.push(newTool);
                segments[toolsSegIdx] = { ...toolsSeg, tools: remaining };
                updated[updated.length - 1] = { ...last, segments };
                return updated;
              }
            }
            // Otherwise add at top level
            tools.push(newTool);
            segments[toolsSegIdx] = { ...toolsSeg, tools };
            updated[updated.length - 1] = { ...last, segments };
          }
          return updated;
        });
        break;
      }
      case 'TOOL_RESULT':
      case 'tool_result': {
        const resultEvent = event as SseToolResultEvent;
        if (resultEvent.tool_name === 'submit_artifacts' && resultEvent.result) {
          try {
            const parsed = JSON.parse(resultEvent.result);
            const submitted = Array.isArray(parsed?.submitted) ? parsed.submitted : [];
            if (submitted.length > 0) {
              setSessionArtifacts(prev => {
                const seen = new Set(prev.map(a => a.file_id));
                const additions: SessionArtifact[] = [];
                for (const s of submitted) {
                  if (s?.file_id && !seen.has(s.file_id)) {
                    additions.push({
                      file_id: s.file_id,
                      file_name: s.file_name || s.path || s.file_id,
                    });
                  }
                }
                return additions.length > 0 ? [...prev, ...additions] : prev;
              });
            }
          } catch {
            // tool result is not parseable JSON — ignore
          }
        }
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'agent') {
            const segments = [...(last.segments || [])];
            // Search all tools segments for this callId
            for (let si = 0; si < segments.length; si++) {
              const seg = segments[si];
              if (seg.type === 'tools') {
                const tools = [...seg.tools];
                // Try to find at top level
                const idx = tools.findIndex(t => t.callId === resultEvent.call_id);
                if (idx >= 0) {
                  tools[idx] = { ...tools[idx], type: 'result', result: resultEvent.result, resultStatus: resultEvent.status || 'COMPLETED', toolType: resultEvent.tool_type };
                  segments[si] = { ...seg, tools };
                  updated[updated.length - 1] = { ...last, segments };
                  return updated;
                }
                // Try to find in children
                for (let i = 0; i < tools.length; i++) {
                  const parent = tools[i];
                  if (parent.children) {
                    const childIdx = parent.children.findIndex(t => t.callId === resultEvent.call_id);
                    if (childIdx >= 0) {
                      const children = [...parent.children];
                      children[childIdx] = { ...children[childIdx], type: 'result', result: resultEvent.result, resultStatus: resultEvent.status || 'COMPLETED', toolType: resultEvent.tool_type };
                      tools[i] = { ...parent, children };
                      segments[si] = { ...seg, tools };
                      updated[updated.length - 1] = { ...last, segments };
                      return updated;
                    }
                  }
                }
              }
            }
          }
          return updated;
        });
        break;
      }
      case 'TOOL_APPROVAL_REQUEST':
      case 'tool_approval_request': {
        const approvalEvent = event as SseToolApprovalRequestEvent;
        const info: AwaitInfo = {
          callId: approvalEvent.call_id,
          tool: approvalEvent.tool_name,
          arguments: approvalEvent.arguments || '',
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
      case 'TURN_COMPLETE':
      case 'turn_complete': {
        setIsThinking(false);
        setStatus('idle');
        setAwaitInfo(null);
        setSidebarRefreshKey(k => k + 1);
        break;
      }
      case 'ERROR':
      case 'error': {
        setIsThinking(false);
        const errorEvent = event as SseErrorEvent;
        const errMsg = errorEvent.message || 'Unknown error';
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'agent') {
            const segments = [...(last.segments || [])];
            segments.push({ type: 'text', content: `Error: ${errMsg}` });
            updated[updated.length - 1] = { ...last, segments };
          }
          return updated;
        });
        setStatus('idle');
        break;
      }
      case 'STATUS_CHANGE':
      case 'status_change': {
        const statusEvent = event as SseStatusChangeEvent;
        if (statusEvent.status === 'idle' || statusEvent.status === 'waiting') {
          setStatus('idle');
        } else if (statusEvent.status === 'running') {
          setStatus('running');
        }
        break;
      }
      case 'PLAN_UPDATE':
      case 'plan_update': {
        const planEvent = event as SsePlanUpdateEvent;
        if (planEvent.todos && Array.isArray(planEvent.todos)) {
          setPlanTodos(planEvent.todos);
        }
        break;
      }
      case 'COMPRESSION':
      case 'compression': {
        const compressionEvent = event as SseCompressionEvent;
        if (compressionEvent.completed) {
          setCompressionInfo({ before: compressionEvent.before_count, after: compressionEvent.after_count });
          setTimeout(() => setCompressionInfo(null), 5000);
        }
        break;
      }
    }
  }, []);

  // Show a brief toast notification
  const showToast = useCallback((msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 2500);
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
        if (sseControllerRef.current === controller) {
          sseControllerRef.current = null;
        }
        const msg = err instanceof Error ? err.message : String(err);
        showToast(`Connection lost: ${msg}. Please retry.`);
        setStatus('idle');
        setMessages(prev => {
          if (prev.length === 0) return prev;
          const last = prev[prev.length - 1];
          if (last.role !== 'agent' || hasAnySegments(last.segments)) return prev;
          const updated = [...prev];
          updated[updated.length - 1] = { ...last, segments: [{ type: 'text', content: `Error: ${msg}` }] };
          return updated;
        });
      },
      () => {
        // Only clear if this controller is still the active one,
        // preventing a stale onClose from wiping a newer connection.
        if (sseControllerRef.current === controller) {
          sseControllerRef.current = null;
        }
      },
    );
    sseControllerRef.current = controller;
  }, [handleSSEEvent, showToast]);

  // Cleanup SSE on unmount. Session changes are handled explicitly by
  // doConnectSSE() and handleNewChat(); aborting from this effect can cancel
  // the first connection created immediately after a new sessionId is set.
  useEffect(() => {
    return () => {
      sseControllerRef.current?.abort();
      sseControllerRef.current = null;
    };
  }, []);

  const ensureSession = async (): Promise<string> => {
    if (sessionId) {
      // Always reconnect SSE before sending a new message to ensure a fresh stream.
      // The old SSE connection may appear alive (sseControllerRef still set) even
      // after TURN_COMPLETE if the server keeps the connection open. Aborting and
      // reconnecting guarantees each message gets a dedicated SSE stream.
      doConnectSSE(sessionId);
      await new Promise(resolve => setTimeout(resolve, 300));
      return sessionId;
    }

    // Pass pre-selected tools/skills to session creation
    const preToolIdsArr = Array.from(preToolIds).map(id => ({ id } as ToolRef));
    const preSkillIdsArr = Array.from(preSkillIds);
    const preSubAgentIdsArr = Array.from(preSubAgentIds);
    const res = await sessionApi.create(selectedAgentId, {
      tools: preToolIdsArr.length > 0 ? preToolIdsArr : undefined,
      skill_ids: preSkillIdsArr.length > 0 ? preSkillIdsArr : undefined,
      sub_agent_ids: preSubAgentIdsArr.length > 0 ? preSubAgentIdsArr : undefined,
    });
    const id = res.sessionId;
    setSessionId(id);
    doConnectSSE(id);

    // Update loaded state from server response
    if (res.loaded_tools && res.loaded_tools.length > 0) {
      setLoadedToolIds(new Set(res.loaded_tools));
      showToast(`Loaded ${res.loaded_tools.length} tool(s)`);
    }
    if (res.loaded_skills && res.loaded_skills.length > 0) {
      setLoadedSkillIds(new Set(res.loaded_skills));
      showToast(`Loaded ${res.loaded_skills.length} skill(s)`);
    }
    if (res.loaded_sub_agents && res.loaded_sub_agents.length > 0) {
      setLoadedSubAgentIds(new Set(res.loaded_sub_agents));
      showToast(`Loaded ${res.loaded_sub_agents.length} sub-agent(s)`);
    }
    setPreToolIds(new Set());
    setPreSkillIds(new Set());
    setPreSubAgentIds(new Set());

    // Wait for SSE to connect
    await new Promise(resolve => setTimeout(resolve, 500));
    return id;
  };

  const handleSend = async () => {
    const text = input.trim();
    const readyAttachments = pendingAttachments.filter(a => !a.uploading);
    const hasAttachments = readyAttachments.length > 0;
    if ((!text && !hasAttachments) || status !== 'idle' || !selectedAgentId) return;

    setInput('');
    setIsInputExpanded(false);

    const attachments = readyAttachments.map(a => ({
      url: a.url,
      type: a.contentType === 'application/pdf' ? 'PDF' as const : 'IMAGE' as const,
    }));
    setPendingAttachments([]);

    setMessages(prev => [...prev, {
      role: 'user',
      segments: [{ type: 'text', content: text }],
      attachments: attachments.length > 0 ? attachments : undefined,
    }]);
    setStatus('running');
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    setPlanTodos(null);
    setMessages(prev => [...prev, { role: 'agent', segments: [] }]);

    try {
      const sid = await ensureSession();
      await sessionApi.sendMessage(sid, text, variableValues,
        attachments.length > 0 ? attachments.map(a => ({ url: a.url, type: a.type })) : undefined);
      setSidebarRefreshKey(k => k + 1);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setMessages(prev => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last?.role === 'agent') {
          updated[updated.length - 1] = { ...last, segments: [{ type: 'text', content: `Error: ${msg}` }] };
        }
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

  const handleCancel = () => {
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    setStatus('idle');
    if (sessionId) sessionApi.cancel(sessionId).catch(() => {});
  };

  const handleNewChat = () => {
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    if (sessionId) sessionApi.close(sessionId).catch(() => {});
    setSessionId(null);
    setMessages([]);
    setSessionArtifacts([]);
    setActiveArtifact(null);
    setStatus('idle');
    setAwaitInfo(null);
    setPlanTodos(null);
    setLoadedToolIds(new Set());
    setLoadedSkillIds(new Set());
    setPreToolIds(new Set());
    setPreSkillIds(new Set());
    setPendingAttachments([]);
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    sessionStorage.removeItem('chat_messages');
    sessionStorage.removeItem('chat_sessionId');
    sessionStorage.removeItem('chat_artifacts');
  };

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
  const agentVariableEntries = Object.entries(selectedAgent?.variables || {});

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
    const agentToolIds = selectedAgent?.tools?.map(t => t.id) || [];
    // Merge with session tools (loaded or pre-selected)
    const sessionToolIds = sessionId ? loadedToolIds : preToolIds;
    setSelectedToolIds(new Set([...agentToolIds, ...sessionToolIds]));
    fetchTools();
    setShowToolPicker(true);
  }, [sessionId, selectedAgent, loadedToolIds, preToolIds, fetchTools]);

  // Open skill picker
  const openSkillPicker = useCallback(() => {
    // Start with agent's configured skills
    const agentSkillIds = (selectedAgent as unknown as Record<string, unknown>)?.skill_ids as string[] || [];
    // Merge with session skills (loaded or pre-selected)
    const sessionSkillIds = sessionId ? loadedSkillIds : preSkillIds;
    setSelectedSkillIds(new Set([...agentSkillIds, ...sessionSkillIds]));
    fetchSkills();
    setShowSkillPicker(true);
  }, [sessionId, selectedAgent, loadedSkillIds, preSkillIds, fetchSkills]);

  // Open agent picker
  const openAgentPicker = useCallback(() => {
    // Start with agent's configured subagents
    const agentSubAgentIds = (selectedAgent as unknown as Record<string, unknown>)?.subagent_ids as string[] || [];
    // Merge with session subagents (loaded or pre-selected)
    const sessionSubAgentIds = sessionId ? loadedSubAgentIds : preSubAgentIds;
    setSelectedAgentIds(new Set([...agentSubAgentIds, ...sessionSubAgentIds]));
    setShowAgentPicker(true);
  }, [sessionId, selectedAgent, loadedSubAgentIds, preSubAgentIds]);

  // Toggle agent selection
  const toggleAgent = useCallback((id: string) => {
    setSelectedAgentIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  // Confirm agent selection — save as pre-session or call API directly
  const loadSelectedAgents = useCallback(async () => {
    if (selectedAgentIds.size === 0) {
      setShowAgentPicker(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreSubAgentIds(new Set(selectedAgentIds));
      showToast(`Selected ${selectedAgentIds.size} agent(s), will load as subagent on first message`);
      setShowAgentPicker(false);
      setSelectedAgentIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadSubAgents(sessionId, Array.from(selectedAgentIds));
      if (res.loaded_sub_agents && res.loaded_sub_agents.length > 0) {
        setLoadedSubAgentIds(prev => {
          const next = new Set(prev);
          for (const name of res.loaded_sub_agents) next.add(name);
          return next;
        });
        showToast(`Loaded ${res.loaded_sub_agents.length} agent(s) as subagent(s)`);
      }
      setShowAgentPicker(false);
      setSelectedAgentIds(new Set());
    } catch (err) {
      console.error('Failed to load subagents:', err);
      showToast('Failed to load subagents');
    }
  }, [sessionId, selectedAgentIds, showToast]);

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

  const handleVoiceLanguageChange = (lang: string) => {
    setVoiceLanguage(lang);
    if (voice.isListening) {
      voice.stopListening();
      voice.startListening(lang);
    }
  };

  const handleSendToChat = (text: string) => {
    setInput(text);
    inputRef.current?.focus();
  };

  const handleSendAllToChat = () => {
    const allText = voice.segments.map(s => s.text).join('\n');
    setInput(allText);
    inputRef.current?.focus();
  };

  return (
    <div className="flex h-full">
      <ChatSessionsSidebar
        currentSessionId={sessionId}
        refreshKey={sidebarRefreshKey}
        onOpen={hydrateSession}
        onNewChat={handleNewChat}
      />
      <div className="flex flex-col h-full flex-1 min-w-0 overflow-hidden">
      {/* Top bar: agent selector */}
      <div className="border-b px-6 py-3 flex items-center justify-between"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-3 min-w-0">
          <div className="relative" ref={agentDropdownRef}>
            <button
              onClick={() => setAgentDropdownOpen(prev => !prev)}
              disabled={status === 'running'}
              className="flex w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
              <Bot size={14} style={{ color: 'var(--color-primary)' }} />
              <span className="truncate max-w-[160px]">{selectedAgent?.name || 'Select Agent'}</span>
              <ChevronDown size={14} className={agentDropdownOpen ? 'rotate-180' : ''} />
            </button>
            {agentDropdownOpen && (
              <div className="absolute left-0 top-11 z-50 w-[380px] rounded-xl border shadow-lg"
                style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
                {/* Default Agents Section */}
                {myAgents.filter(a => a.system_default && (a.status === 'PUBLISHED' || a.type === 'local')).length > 0 && (
                  <div className="p-2">
                    <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium"
                      style={{ color: 'var(--color-text-secondary)' }}>
                      <Bot size={12} /> Default
                    </div>
                    <div className="max-h-[200px] overflow-auto">
                      {myAgents.filter(a => a.system_default && (a.status === 'PUBLISHED' || a.type === 'local')).map(a => (
                        <button key={a.id}
                          onClick={() => { handleNewChat(); setSelectedAgentId(a.id); setAgentDropdownOpen(false); setAgentSearchQuery(''); }}
                          className="w-full flex items-center gap-2 px-2 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors"
                          style={{
                            background: selectedAgentId === a.id ? 'var(--color-bg-tertiary)' : 'transparent',
                            color: 'var(--color-text)',
                          }}
                          onMouseEnter={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}
                          onMouseLeave={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'transparent'; }}>
                          <Bot size={14} style={{ color: 'var(--color-primary)' }} />
                          <span className="flex-1 truncate">{a.name}</span>
                          {selectedAgentId === a.id && <Check size={14} style={{ color: 'var(--color-primary)' }} />}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
                {/* My Agents Section */}
                {myAgents.filter(a => !a.system_default && (a.status === 'PUBLISHED' || a.type === 'local')).length > 0 && (
                  <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
                    <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium"
                      style={{ color: 'var(--color-text-secondary)' }}>
                      <Bot size={12} /> My Agents
                    </div>
                    <div className="max-h-[200px] overflow-auto">
                      {myAgents.filter(a => !a.system_default && (a.status === 'PUBLISHED' || a.type === 'local')).map(a => (
                        <button key={a.id}
                          onClick={() => { handleNewChat(); setSelectedAgentId(a.id); setAgentDropdownOpen(false); setAgentSearchQuery(''); }}
                          className="w-full flex items-center gap-2 px-2 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors"
                          style={{
                            background: selectedAgentId === a.id ? 'var(--color-bg-tertiary)' : 'transparent',
                            color: 'var(--color-text)',
                          }}
                          onMouseEnter={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}
                          onMouseLeave={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'transparent'; }}>
                          <Bot size={14} style={{ color: 'var(--color-primary)' }} />
                          <span className="flex-1 truncate">{a.name}</span>
                          {selectedAgentId === a.id && <Check size={14} style={{ color: 'var(--color-primary)' }} />}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
                {/* Search Others Section */}
                <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium mb-1"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    <Star size={12} /> Shared Agents
                  </div>
                  <div className="relative mb-1">
                    <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2"
                      style={{ color: 'var(--color-text-secondary)' }} />
                    <input
                      type="text"
                      value={agentSearchQuery}
                      onChange={e => setAgentSearchQuery(e.target.value)}
                      placeholder="Search shared agents..."
                      className="w-full pl-7 pr-2 py-1.5 rounded-lg border text-xs outline-none"
                      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
                    />
                  </div>
                  {agentSearchQuery.length > 0 && (
                    <div className="max-h-[200px] overflow-auto">
                      {otherAgents.filter(a =>
                        (a.status === 'PUBLISHED' || a.type === 'local') &&
                        a.name.toLowerCase().includes(agentSearchQuery.toLowerCase())
                      ).length === 0 ? (
                        <div className="text-center py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                          No agents found
                        </div>
                      ) : (
                        otherAgents.filter(a =>
                          (a.status === 'PUBLISHED' || a.type === 'local') &&
                          a.name.toLowerCase().includes(agentSearchQuery.toLowerCase())
                        ).map(a => (
                          <button key={a.id}
                            onClick={() => { handleNewChat(); setSelectedAgentId(a.id); setAgentDropdownOpen(false); setAgentSearchQuery(''); }}
                            className="w-full flex items-center gap-2 px-2 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors"
                            style={{
                              background: selectedAgentId === a.id ? 'var(--color-bg-tertiary)' : 'transparent',
                              color: 'var(--color-text)',
                            }}
                            onMouseEnter={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}
                            onMouseLeave={e => { if (selectedAgentId !== a.id) e.currentTarget.style.background = 'transparent'; }}>
                            <Star size={14} style={{ color: 'var(--color-text-secondary)' }} />
                            <span className="flex-1 truncate">{a.name}</span>
                            <span className="text-xs truncate" style={{ color: 'var(--color-text-muted)' }}>{a.created_by}</span>
                            {selectedAgentId === a.id && <Check size={14} style={{ color: 'var(--color-primary)' }} />}
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
          {selectedAgent && (
            <span className="flex-1 min-w-0 text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>
              {selectedAgent.model || 'default model'}
              {selectedAgent.description && ` · ${selectedAgent.description}`}
            </span>
          )}
        </div>
      </div>

      {/* Chat messages */}
      <div ref={messagesContainerRef} className="flex-1 overflow-auto p-6 relative">
        {agentVariableEntries.length > 0 && (
          <div className="absolute top-5 right-6 z-20">
            <div className="relative" ref={variablesPanelRef}>
              <button
                onClick={() => setVariablesExpanded(prev => !prev)}
                disabled={status === 'running'}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-xs cursor-pointer disabled:opacity-40"
                style={{
                  borderColor: 'var(--color-border)',
                  background: variablesExpanded ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)',
                  color: 'var(--color-text-secondary)',
                }}
                title="Configure variables"
              >
                Variables ({agentVariableEntries.length})
                {variablesExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
              {variablesExpanded && (
                <div className="absolute right-0 top-11 z-20 w-[420px] rounded-xl border p-3 shadow-lg"
                  style={{
                    borderColor: 'var(--color-border)',
                    background: 'var(--color-bg)',
                  }}>
                  <div className="text-xs mb-2 font-medium" style={{ color: 'var(--color-text-secondary)' }}>
                    Variables
                  </div>
                  <div className="grid grid-cols-1 gap-2">
                    {agentVariableEntries.map(([key]) => (
                      <div key={key} className="flex items-center gap-2">
                        <label className="text-xs min-w-[110px]" style={{ color: 'var(--color-text-secondary)' }}>{key}</label>
                        <input
                          value={variableValues[key] ?? ''}
                          onChange={e => setVariableValues(prev => ({ ...prev, [key]: e.target.value }))}
                          className="flex-1 rounded-lg border px-2 py-1.5 text-xs outline-none"
                          style={{
                            background: 'var(--color-bg-tertiary)',
                            borderColor: 'var(--color-border)',
                            color: 'var(--color-text)',
                          }}
                          placeholder={`Value for ${key}`}
                          disabled={status === 'running'}
                        />
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
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
          {messages.filter((msg, idx) => msg.role === 'user' || hasAnySegments(msg.segments) || msg.approval || (status === 'running' && msg.role === 'agent' && idx === messages.length - 1)).map((msg, i) => (
            <div key={i} className={`group flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`}>
              {msg.role === 'agent' && (
                <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  <Bot size={18} />
                </div>
              )}
              <div className={`max-w-[80%] ${msg.role === 'user' ? 'order-first' : ''}`}>
                {/* Render segments in fixed order: thinking → tools → text */}
                {(() => {
                  const thinkingSeg = msg.segments?.find(s => s.type === 'thinking');
                  const toolsSeg = msg.segments?.find(s => s.type === 'tools') as ToolsSegment | undefined;
                  const textSeg = msg.segments?.find(s => s.type === 'text');
                  return (
                    <>
                      {thinkingSeg && (
                        <div className="mb-3">
                          <ThinkingBlock thinking={thinkingSeg.content} isStreaming={isThinking} />
                        </div>
                      )}
                      {toolsSeg && toolsSeg.tools.length > 0 && (
                        <div className="mb-3">
                          <ToolsBlock tools={toolsSeg.tools} />
                        </div>
                      )}
                      {textSeg && (
                        <div className="mb-3">
                          {/* Attachments as thumbnails (IMAGES) or links (PDFs) */}
                          {msg.attachments && msg.attachments.length > 0 && (
                            <div className="flex gap-2 flex-wrap mb-2" style={{ justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start' }}>
                              {msg.attachments.map((att, idx) => (
                                att.type === 'IMAGE' ? (
                                  <a key={idx} href={att.url} target="_blank" rel="noopener noreferrer"
                                    className="block rounded-lg overflow-hidden border"
                                    style={{ borderColor: 'var(--color-border)', maxWidth: '160px' }}>
                                    <img src={att.url} alt={`attachment-${idx}`}
                                      className="w-full h-24 object-cover"
                                      loading="lazy" />
                                  </a>
                                ) : (
                                  <a key={idx} href={att.url} target="_blank" rel="noopener noreferrer"
                                    className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium"
                                    style={{
                                      background: 'var(--color-bg-tertiary)',
                                      border: '1px solid var(--color-border)',
                                      color: 'var(--color-text-secondary)',
                                    }}>
                                    <Paperclip size={12} />
                                    <span className="max-w-[120px] truncate">PDF</span>
                                  </a>
                                )
                              ))}
                            </div>
                          )}
                          <div className="rounded-xl px-4 py-3 text-sm overflow-x-auto"
                            style={{
                              background: msg.role === 'user' ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                              color: msg.role === 'user' ? 'white' : 'var(--color-text)',
                              border: msg.role === 'agent' ? '1px solid var(--color-border)' : 'none',
                            }}>
                            <div className="font-[inherit] m-0 [&_pre]:bg-[var(--color-bg-tertiary)] [&_pre]:p-2 [&_pre]:rounded [&_pre]:overflow-x-auto [&_code]:text-[inherit] [&_table]:border-collapse [&_table]:my-2 [&_table]:w-auto [&_th]:border [&_th]:border-[var(--color-border)] [&_th]:px-2 [&_th]:py-1 [&_th]:bg-[var(--color-bg-tertiary)] [&_td]:border [&_td]:border-[var(--color-border)] [&_td]:px-2 [&_td]:py-1 [&_svg]:block [&_svg]:max-w-full [&_svg]:h-auto">
                              <ReactMarkdown
                                remarkPlugins={[remarkGfm]}
                                rehypePlugins={msg.role === 'agent' && !(status === 'running' && i === messages.length - 1) ? AGENT_REHYPE_PLUGINS : undefined}
                                components={msg.role === 'agent' ? agentMarkdownComponents : undefined}>
                                {textSeg.content}
                              </ReactMarkdown>
                            </div>
                            {/* Cursor: show after text content when streaming */}
                            {status === 'running' && i === messages.length - 1 && textSeg.content && (
                              <span className="inline-block w-2 h-4 ml-0.5 animate-pulse rounded-sm align-middle" style={{ background: 'var(--color-primary)' }} />
                            )}
                          </div>
                        </div>
                      )}
                    </>
                  );
                })()}
                {/* Empty state (no segments yet) for streaming agent message */}
                {status === 'running' && msg.role === 'agent' && i === messages.length - 1 && !hasAnySegments(msg.segments) && (
                  <div className="flex items-center gap-2 py-2 px-1">
                    <Loader2 size={16} className="animate-spin" style={{ color: 'var(--color-primary)' }} />
                    <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Thinking...</span>
                  </div>
                )}
                {/* Plan update block */}
                {planTodos && planTodos.length > 0 && msg.role === 'agent' && i === messages.length - 1 && <PlanUpdateBlock todos={planTodos} />}
                {/* Per-message artifact cards: submitted by this message's submit_artifacts tool calls */}
                {msg.role === 'agent' && (() => {
                  const msgArtifacts = getMessageArtifacts(msg, sessionArtifacts);
                  if (msgArtifacts.length === 0) return null;
                  return (
                    <div className="mt-2 flex flex-col items-start gap-1">
                      {msgArtifacts.map(a => (
                        <ArtifactCard key={a.file_id}
                          artifact={{
                            kind: 'file',
                            title: a.title || a.file_name,
                            fileId: a.file_id,
                            fileName: a.file_name,
                            contentType: a.content_type,
                            size: a.size,
                          }}
                          onOpen={openArtifact}
                        />
                      ))}
                    </div>
                  );
                })()}
                {/* Copy button: shown if any text segments exist */}
                {hasTextSegments(msg.segments) && (
                  <div className={`flex mt-1 opacity-0 group-hover:opacity-100 transition-opacity ${msg.role === 'user' ? 'justify-end' : ''}`}>
                    <CopyButton text={getMessageText(msg)} />
                  </div>
                )}
                {/* Approval block */}
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
          {(() => {
            const totalChips = loadedToolIds.size + loadedSkillIds.size + loadedSubAgentIds.size + preToolIds.size + preSkillIds.size + preSubAgentIds.size;
            if (totalChips === 0) return null;
            const COLLAPSE_THRESHOLD = 8;
            const collapsible = totalChips > COLLAPSE_THRESHOLD;
            const collapsed = collapsible && !chipsExpanded;
            return (
            <div className="mb-2">
              {collapsible && (
                <button
                  onClick={() => setChipsExpanded(v => !v)}
                  className="inline-flex items-center gap-1 text-xs mb-1.5 cursor-pointer hover:opacity-80"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  {collapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
                  <span>
                    {loadedToolIds.size + preToolIds.size > 0 && `${loadedToolIds.size + preToolIds.size} tools`}
                    {loadedSkillIds.size + preSkillIds.size > 0 && `${loadedToolIds.size + preToolIds.size > 0 ? ', ' : ''}${loadedSkillIds.size + preSkillIds.size} skills`}
                    {loadedSubAgentIds.size + preSubAgentIds.size > 0 && `${(loadedToolIds.size + preToolIds.size > 0 || loadedSkillIds.size + preSkillIds.size > 0) ? ', ' : ''}${loadedSubAgentIds.size + preSubAgentIds.size} agents`}
                    {' loaded'}
                  </span>
                </button>
              )}
              {!collapsed && (
            <div className="flex flex-wrap gap-1.5 min-h-[24px]">
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
              {Array.from(loadedSubAgentIds).map(name => (
                <span key={`lsa-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: '#8b5cf6' + '18',
                    color: '#8b5cf6',
                    border: '1px solid #8b5cf6' + '30',
                  }}>
                  <Users size={10} />
                  {name}
                </span>
              ))}
              {Array.from(preSubAgentIds).map(name => (
                <span key={`psa-${name}`}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium"
                  style={{
                    background: '#8b5cf6' + '08',
                    color: 'var(--color-text-muted)',
                    border: '1px dashed var(--color-border)',
                  }}>
                  <Users size={10} />
                  {name}
                  <span className="ml-0.5 opacity-60">(pending)</span>
                </span>
              ))}
            </div>
              )}
            </div>
            );
          })()}

          {/* Attachment previews */}
          {pendingAttachments.length > 0 && (
            <div className="flex gap-2 flex-wrap mb-2">
              {pendingAttachments.map((att) => (
                <span key={att.id}
                  className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium"
                  style={{
                    background: att.uploading ? 'var(--color-bg-tertiary)' : 'var(--color-primary)' + '12',
                    border: att.uploading ? '1px dashed var(--color-border)' : '1px solid var(--color-primary)' + '20',
                    color: att.uploading ? 'var(--color-text-muted)' : 'var(--color-primary)',
                  }}>
                  {att.uploading ? (
                    <Loader2 size={12} className="animate-spin" />
                  ) : att.contentType === 'application/pdf' ? (
                    <Paperclip size={12} />
                  ) : (
                    <Paperclip size={12} />
                  )}
                  <span className="max-w-[120px] truncate">{att.name}</span>
                  {!att.uploading && (
                    <button onClick={() => removeAttachment(att.id)}
                      className="ml-0.5 hover:opacity-70"
                      style={{ color: 'var(--color-text-muted)' }}>
                      <X size={12} />
                    </button>
                  )}
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

            {/* Agent picker button — enabled when agent is selected */}
            <button
              onClick={openAgentPicker}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: (loadedSubAgentIds.size > 0 || preSubAgentIds.size > 0) ? '#8b5cf620' : 'var(--color-bg-tertiary)',
                border: '1px solid var(--color-border)',
                color: (loadedSubAgentIds.size > 0 || preSubAgentIds.size > 0) ? '#8b5cf6' : 'var(--color-text-secondary)',
              }}
              title="Load agents as subagents">
              <Users size={18} />
            </button>

            {/* File upload button */}
            <button
              onClick={handleFileSelect}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: pendingAttachments.length > 0 ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
                border: '1px solid var(--color-border)',
                color: pendingAttachments.length > 0 ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              }}
              title="Upload image or PDF">
              <Paperclip size={18} />
            </button>

            <span className="relative flex-1 self-stretch flex items-end">
              <textarea
                ref={inputRef}
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={selectedAgentId ? 'Send a message...' : 'Select an agent first'}
                rows={isInputExpanded ? undefined : 1}
                className={`rounded-xl border px-4 py-3 text-sm resize-none focus:outline-none w-full ${isInputExpanded ? 'absolute bottom-0 left-0 right-0 z-10' : ''}`}
                style={{
                  background: 'var(--color-bg-secondary)',
                  borderColor: 'var(--color-border)',
                  color: 'var(--color-text)',
                  ...(isInputExpanded ? { minHeight: '80px' } : {}),
                }}
                disabled={status !== 'idle' || !selectedAgentId}
              />
            </span>
            {/* Expand/collapse button — show when text exceeds 1 line */}
            {needsExpand && (
              <button
                onClick={() => setIsInputExpanded(v => !v)}
                disabled={!selectedAgentId || status === 'running'}
                className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
                style={{
                  background: 'var(--color-bg-tertiary)',
                  border: '1px solid var(--color-border)',
                  color: 'var(--color-text-secondary)',
                }}
                title={isInputExpanded ? 'Collapse input' : 'Expand input'}>
                {isInputExpanded ? <Minimize2 size={18} /> : <Maximize2 size={18} />}
              </button>
            )}
            {/* Mic button — between input and send */}
            <button
              onClick={() => {
                if (showVoiceSidebar) {
                  voice.stopListening();
                  setShowVoiceSidebar(false);
                } else {
                  openVoiceSidebar();
                }
              }}
              disabled={!selectedAgentId || status === 'running'}
              className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
              style={{
                background: showVoiceSidebar ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
                border: '1px solid ' + (showVoiceSidebar ? 'var(--color-primary)' : 'var(--color-border)'),
                color: showVoiceSidebar ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              }}
              title={showVoiceSidebar ? 'Close voice sidebar' : 'Open voice input'}>
              <Mic size={18} />
            </button>
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

      {/* Hidden file input for image/pdf upload */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/gif,image/webp,image/svg+xml,application/pdf"
        multiple
        onChange={handleFileChange}
        className="hidden"
      />

      {/* Tool Picker Modal */}
      {showToolPicker && (
        <ToolPickerModal
          availableTools={availableTools}
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

      {/* Agent Picker Modal */}
      {showAgentPicker && (
        <ResourcePicker
          title="Load Agents as Subagents"
          items={agents.filter(a => a.id !== selectedAgentId).map(a => ({
            id: a.id,
            name: a.name,
            description: a.description || '',
            type: a.type,
          }))}
          loading={false}
          loadedIds={loadedSubAgentIds}
          pendingIds={preSubAgentIds}
          selectedIds={selectedAgentIds}
          onToggle={toggleAgent}
          onLoad={loadSelectedAgents}
          onClose={() => setShowAgentPicker(false)}
        />
      )}
      </div>
      {showVoiceSidebar && (
        <VoiceTranscriberSidebar
          segments={voice.segments}
          isListening={voice.isListening}
          error={voice.error}
          language={voiceLanguage}
          onLanguageChange={handleVoiceLanguageChange}
          onStop={voice.stopListening}
          onClose={() => { voice.stopListening(); setShowVoiceSidebar(false); }}
          onStartListening={() => voice.startListening(voiceLanguage)}
          onClear={voice.clearSegments}
          onSendToChat={handleSendToChat}
          onSendAllToChat={handleSendAllToChat}
        />
      )}
      {activeArtifact && (
        <ArtifactDrawer artifact={activeArtifact} onClose={() => setActiveArtifact(null)} />
      )}
    </div>
  );
}
