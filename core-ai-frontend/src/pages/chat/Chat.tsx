import { lazy, Suspense, useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { sessionApi } from '../../api/session';
import type { SseEvent, SseTextChunkEvent, SseReasoningChunkEvent, SseToolStartEvent, SseToolResultEvent, SseToolApprovalRequestEvent, SsePlanUpdateEvent, SseCompressionEvent, SseErrorEvent, SseStatusChangeEvent, SseSandboxEvent, ChatSessionSummary, SessionArtifact } from '../../api/session';
import { api } from '../../api/client';
import type { AgentDefinition, ToolRegistryView, SkillDefinition, ToolRef } from '../../api/client';
import type { IdName } from '../../api/session';
import ResourcePicker from './ResourcePicker';
import ToolPickerModal from './ToolPickerModal';
import ChatConfigModal, { type DatasetConfigDraft } from './ChatConfigModal';
import ChatSessionsSidebar from './ChatSessionsSidebar';
import type { ArtifactSpec } from './components/artifactTypes';
import ChatMessagesPanel, { INITIAL_VISIBLE_MESSAGES, MESSAGE_RENDER_BATCH } from './components/ChatMessagesPanel';
import ChatComposer from './components/ChatComposer';
import type { ChatComposerHandle, ComposerAttachment } from './components/ChatComposer';
import AgentSelector from './components/AgentSelector';
import { useSpeechRecognition } from './hooks/useSpeechRecognition';
import type { AwaitInfo, ChatMessage, ToolEvent, PlanTodo, MessageSegment, ToolsSegment, SandboxSegment, SandboxTerminalSpec } from './types';
import { historyToChatMessages } from './utils';
import SandboxTerminalPanel from './components/SandboxTerminalPanel';

const VoiceTranscriberSidebar = lazy(() => import('./components/VoiceTranscriberSidebar'));
const ArtifactDrawer = lazy(() => import('./components/ArtifactDrawer'));

function hasAnySegments(segments?: MessageSegment[]): boolean {
  return segments != null && segments.length > 0;
}

export default function Chat() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    try { const s = sessionStorage.getItem('chat_messages'); return s ? JSON.parse(s) : []; } catch { return []; }
  });
  const [visibleMessageLimit, setVisibleMessageLimit] = useState(INITIAL_VISIBLE_MESSAGES);
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

  // Loaded tools/skills (confirmed on server)
  const [loadedToolIds, setLoadedToolIds] = useState<Set<string>>(new Set());
  const [loadedSkillIds, setLoadedSkillIds] = useState<Set<string>>(new Set());
  const [loadedSubAgentIds, setLoadedSubAgentIds] = useState<Set<string>>(new Set());
  // Pre-session selections (shown as chips, will be sent on session create)
  const [preToolIds, setPreToolIds] = useState<Set<string>>(new Set());
  const [preSkillIds, setPreSkillIds] = useState<Set<string>>(new Set());
  const [preSubAgentIds, setPreSubAgentIds] = useState<Set<string>>(new Set());
  // Human-readable names for loaded entities (id -> name)
  const [loadedNames, setLoadedNames] = useState<Map<string, string>>(new Map());
  const [availableTools, setAvailableTools] = useState<ToolRegistryView[]>([]);
  const [availableSkills, setAvailableSkills] = useState<SkillDefinition[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [skillsLoading, setSkillsLoading] = useState(false);
  const [showToolPicker, setShowToolPicker] = useState(false);
  const [showSkillPicker, setShowSkillPicker] = useState(false);
  const [showAgentPicker, setShowAgentPicker] = useState(false);
  const [showConfigModal, setShowConfigModal] = useState(false);
  const [selectedToolIds, setSelectedToolIds] = useState<Set<string>>(new Set());
  const [selectedSkillIds, setSelectedSkillIds] = useState<Set<string>>(new Set());
  const [selectedAgentIds, setSelectedAgentIds] = useState<Set<string>>(new Set());
  // Dataset config draft for session-level override
  const [draftDatasetConfigs, setDraftDatasetConfigs] = useState<DatasetConfigDraft[]>([]);
  const [datasets, setDatasets] = useState<{ id: string; name: string }[]>([]);
  const [toast, setToast] = useState<string | null>(null);
  const [showVoiceSidebar, setShowVoiceSidebar] = useState(false);
  const [activeArtifact, setActiveArtifact] = useState<ArtifactSpec | null>(null);
  const [activeSandboxTerminal, setActiveSandboxTerminal] = useState<SandboxTerminalSpec | null>(null);
  const [sessionArtifacts, setSessionArtifacts] = useState<SessionArtifact[]>(() => {
    try { const s = sessionStorage.getItem('chat_artifacts'); return s ? JSON.parse(s) : []; } catch { return []; }
  });
  const openArtifact = useCallback((spec: ArtifactSpec) => {
    setShowVoiceSidebar(false);
    setActiveSandboxTerminal(null);
    setActiveArtifact(spec);
  }, []);
  const openVoiceSidebar = useCallback(() => {
    setActiveArtifact(null);
    setActiveSandboxTerminal(null);
    setShowVoiceSidebar(true);
  }, []);
  const openSandboxTerminal = useCallback((spec: SandboxTerminalSpec) => {
    setShowVoiceSidebar(false);
    setActiveArtifact(null);
    setActiveSandboxTerminal(spec);
  }, []);

  const [voiceLanguage, setVoiceLanguage] = useState('zh-CN');
  const {
    isListening: voiceIsListening,
    segments: voiceSegments,
    error: voiceError,
    startListening,
    stopListening,
    clearSegments,
  } = useSpeechRecognition();
  const [variablesExpanded, setVariablesExpanded] = useState(false);

  const bottomRef = useRef<HTMLDivElement>(null);
  const composerRef = useRef<ChatComposerHandle>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  // true while the user is parked near the bottom of the messages list.
  // Streaming chunks only auto-scroll when this is true — otherwise the user
  // is reading history and must not be yanked back down.
  const stickToBottomRef = useRef(true);
  const [showJumpToBottom, setShowJumpToBottom] = useState(false);
  const variablesPanelRef = useRef<HTMLDivElement>(null);
  const sseControllerRef = useRef<AbortController | null>(null);
  const hydrateRequestSeqRef = useRef(0);
  const streamingContentRef = useRef('');
  const streamingThinkingRef = useRef('');

  // Show a brief toast notification
  const showToast = useCallback((msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 2500);
  }, []);

  // All published agents for chat (my + others, filtered by status)
  const agents = useMemo(() =>
    [...myAgents, ...otherAgents].filter(a => a.status === 'PUBLISHED' || a.type === 'local'),
    [myAgents, otherAgents]
  );

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    bottomRef.current?.scrollIntoView({ behavior });
    stickToBottomRef.current = true;
    setShowJumpToBottom(false);
  }, []);

  // Fetch dataset names for chip display
  useEffect(() => {
    api.datasets.list().then(res => setDatasets((res.datasets || []).map(d => ({ id: d.id, name: d.name })))).catch(() => {});
  }, []);

  // Track whether the user is parked at the bottom. Recompute on every scroll;
  // the threshold of 80px tolerates streaming-induced flicker and slow scrollbars.
  const handleMessagesScroll = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    stickToBottomRef.current = atBottom;
    setShowJumpToBottom(!atBottom);
  }, []);

  const handleShowEarlierMessages = useCallback(() => {
    setVisibleMessageLimit(limit => limit + MESSAGE_RENDER_BATCH);
  }, []);

  const handleToggleVariables = useCallback(() => {
    setVariablesExpanded(prev => !prev);
  }, []);

  const handleVariableChange = useCallback((key: string, value: string) => {
    setVariableValues(prev => ({ ...prev, [key]: value }));
  }, []);

  // Auto-scroll on new messages only when the user is still at the bottom.
  useEffect(() => {
    if (stickToBottomRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'auto' });
    }
  }, [messages]);
  useEffect(() => { if (status === 'idle') composerRef.current?.focus(); }, [status]);

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

  // Sync skills/subagents from agent definition
  useEffect(() => {
    const agent = agents.find(a => a.id === selectedAgentId);
    if (!agent) return;

    const skills = agent.skills;
    if (skills && skills.length > 0) {
      setLoadedSkillIds(new Set(skills.map(s => s.id)));
      setLoadedNames(prev => { const m = new Map(prev); for (const s of skills) m.set(s.id, s.name); return m; });
    }
    const subAgents = agent.sub_agents;
    if (subAgents && subAgents.length > 0) {
      setLoadedSubAgentIds(new Set(subAgents.map(a => a.id)));
      setLoadedNames(prev => { const m = new Map(prev); for (const a of subAgents) m.set(a.id, a.name); return m; });
    }

    // Initialize dataset draft from agent definition
    const dsConfig = agent.dataset_config;
    if (dsConfig && dsConfig.length > 0) {
      setDraftDatasetConfigs(dsConfig.map(c => ({
        ...c,
        _key: crypto.randomUUID(),
      })));
    } else {
      setDraftDatasetConfigs([]);
    }
  }, [agents, selectedAgentId]);

  const [sidebarRefreshKey, setSidebarRefreshKey] = useState(0);

  const hydrateSession = useCallback(async (session: ChatSessionSummary) => {
    if (!session.id) return;
    const hydrateSeq = ++hydrateRequestSeqRef.current;
    const isCurrentHydration = () => hydrateSeq === hydrateRequestSeqRef.current;
    // Reset all session-scoped state synchronously before fetching new history.
    // This guarantees any pending SSE callbacks from the previous session can't
    // bleed into the next one, and any open drawer is closed.
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    setSessionId(session.id);
    setMessages([]);
    setSessionArtifacts([]);
    setActiveArtifact(null);
    setActiveSandboxTerminal(null);
    setAwaitInfo(null);
    setPlanTodos(null);
    setCompressionInfo(null);
    setIsThinking(false);
    setStatus('idle');
    setVisibleMessageLimit(INITIAL_VISIBLE_MESSAGES);
    try {
      const [res, sessionStatus] = await Promise.all([
        sessionApi.history(session.id),
        sessionApi.status(session.id).catch(() => null),
      ]);
      if (!isCurrentHydration()) return;
      const hydrated = historyToChatMessages(res.messages || []);
      // A trailing user message means the agent reply may not be persisted yet. Keep
      // a hidden placeholder so future/replayed SSE chunks have a slot to fill, but
      // only show running when the backend says the turn is currently active.
      const lastMsg = hydrated[hydrated.length - 1];
      const mayHavePendingReply = lastMsg?.role === 'user';
      const turnInProgress = sessionStatus?.status === 'running';
      if ((turnInProgress || mayHavePendingReply) && lastMsg?.role !== 'agent') {
        hydrated.push({ role: 'agent', segments: [], timestamp: new Date().toISOString() });
      }
      setMessages(hydrated);
      setSessionArtifacts(res.artifacts || []);
      if (session.agent_id && agents.some(a => a.id === session.agent_id)) {
        setSelectedAgentId(session.agent_id);
      }
      if (turnInProgress) {
        setStatus('running');
      }
      if (turnInProgress || mayHavePendingReply) {
        // Reconnect SSE so the backend replays any buffered in-progress events
        // (text chunks, tool calls, etc.) and resumes live streaming. If a prior
        // turn was cancelled, no event will arrive and the placeholder remains hidden.
        doConnectSSE(session.id);
      }
    } catch (e) {
      if (!isCurrentHydration()) return;
      console.warn('failed to hydrate session history', e);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      if (allAgents.length > 0) {
        setSelectedAgentId(prev => prev || allAgents[0].id);
      }
    }).catch(console.error);
  }, []);

  // Handle ?agent=...&auto=help query params for auto-creating agents
  useEffect(() => {
    const agentParam = searchParams.get('agent');
    const autoParam = searchParams.get('auto');
    if (!agentParam || !autoParam) return;

    const allAgents = [...myAgents, ...otherAgents]
      .filter(a => a.status === 'PUBLISHED' || a.type === 'local');
    const targetAgent = allAgents.find(a => a.id === agentParam);

    if (targetAgent && autoParam === 'help') {
      handleNewChat();
      setSelectedAgentId(agentParam);

      // Auto-send "help" after state settles
      // Clear URL params via history API to avoid triggering effect re-run
      window.history.replaceState({}, '', window.location.pathname);
      const targetId = agentParam;
      const timer = setTimeout(async () => {
        setMessages([{ role: 'user' as const, segments: [{ type: 'text' as const, content: 'help' }], timestamp: new Date().toISOString() }]);
        setStatus('running');
        streamingContentRef.current = '';
        streamingThinkingRef.current = '';
        setPlanTodos(null);
        setMessages(prev => [...prev, { role: 'agent' as const, segments: [], timestamp: new Date().toISOString() }]);

        try {
          const res = await sessionApi.create(targetId, {});
          const sid = res.sessionId;
          setSessionId(sid);
          doConnectSSE(sid);
          const lt = res.loaded_tools;
          if (lt && lt.length > 0) {
            setLoadedToolIds(new Set(lt.map(t => t.id)));
            setLoadedNames(prev => { const m = new Map(prev); for (const t of lt) m.set(t.id, t.name); return m; });
          }
          const ls = res.loaded_skills;
          if (ls && ls.length > 0) {
            setLoadedSkillIds(new Set(ls.map(s => s.id)));
            setLoadedNames(prev => { const m = new Map(prev); for (const s of ls) m.set(s.id, s.name); return m; });
          }
          const la = res.loaded_sub_agents;
          if (la && la.length > 0) {
            setLoadedSubAgentIds(new Set(la.map(a => a.id)));
            setLoadedNames(prev => { const m = new Map(prev); for (const a of la) m.set(a.id, a.name); return m; });
          }
          setPreToolIds(new Set());
          setPreSkillIds(new Set());
          setPreSubAgentIds(new Set());
          await new Promise(resolve => setTimeout(resolve, 500));
          await sessionApi.sendMessage(sid, 'help', {});
          setSidebarRefreshKey(k => k + 1);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          setMessages(prev => {
            const updated = [...prev];
            const last = updated[updated.length - 1];
            if (last?.role === 'agent') {
              updated[updated.length - 1] = { ...last, segments: [{ type: 'text' as const, content: `Error: ${msg}` }] };
            }
            return updated;
          });
          setStatus('idle');
        }
      }, 300);

      return () => clearTimeout(timer);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myAgents, otherAgents, searchParams]);

  const handleSSEEvent = useCallback((event: SseEvent) => {
    switch (event.type) {
      case 'TEXT_CHUNK':
      case 'text_chunk': {
        const chunkEvent = event as SseTextChunkEvent;
        const chunk = chunkEvent.content || '';
        if (chunk) {
          setStatus('running');
          setMessages(prev => {
            const updated = [...prev];
            let last = updated[updated.length - 1];
            // chunks may arrive after SSE-replay before any agent slot exists
            // (e.g., user returns to a session whose turn was in progress).
            if (!last || last.role !== 'agent') {
              last = { role: 'agent', segments: [], timestamp: new Date().toISOString() };
              updated.push(last);
            }
            const segments = [...(last.segments || [])];
            const lastSeg = segments[segments.length - 1];
            if (lastSeg?.type === 'text') {
              segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
            } else {
              const existingIdx = segments.findIndex(s => s.type === 'text');
              if (existingIdx >= 0) {
                const existing = segments[existingIdx] as MessageSegment & { type: 'text' };
                segments[existingIdx] = { ...existing, content: existing.content + chunk };
              } else {
                segments.push({ type: 'text', content: chunk });
              }
            }
            updated[updated.length - 1] = { ...last, segments };
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

        if (isFinalChunk) {
          setIsThinking(false);
          break;
        }
        if (!isFinalChunk && chunk) {
          setStatus('running');
          setIsThinking(true);
          setMessages(prev => {
            const updated = [...prev];
            let last = updated[updated.length - 1];
            if (!last || last.role !== 'agent') {
              last = { role: 'agent', segments: [], timestamp: new Date().toISOString() };
              updated.push(last);
            }
            const segments = [...(last.segments || [])];
            const lastSeg = segments[segments.length - 1];
            if (lastSeg?.type === 'thinking') {
              segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
            } else {
              const existingIdx = segments.findIndex(s => s.type === 'thinking');
              if (existingIdx >= 0) {
                const existing = segments[existingIdx] as MessageSegment & { type: 'thinking' };
                segments[existingIdx] = { ...existing, content: existing.content + chunk };
              } else {
                segments.push({ type: 'thinking', content: chunk });
              }
            }
            updated[updated.length - 1] = { ...last, segments };
            return updated;
          });
        }
        break;
      }
      case 'TOOL_START':
      case 'tool_start': {
        setStatus('running');
        setIsThinking(false);
        const toolEvent = event as SseToolStartEvent;
        setMessages(prev => {
          const updated = [...prev];
          let last = updated[updated.length - 1];
          if (!last || last.role !== 'agent') {
            last = { role: 'agent', segments: [], timestamp: new Date().toISOString() };
            updated.push(last);
          }
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
            model: toolEvent.model,
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
        setIsThinking(false);
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
        setMessages(prev => {
          if (prev.length === 0) return prev;
          const last = prev[prev.length - 1];
          if (last.role !== 'agent') return prev;
          const updated = [...prev];
          updated[updated.length - 1] = { ...last, timestamp: new Date().toISOString() };
          return updated;
        });
        break;
      }
      case 'ERROR':
      case 'error': {
        setIsThinking(false);
        const errorEvent = event as SseErrorEvent;
        const errMsg = errorEvent.message || 'Unknown error';
        setMessages(prev => {
          const updated = [...prev];
          let last = updated[updated.length - 1];
          if (!last || last.role !== 'agent') {
            last = { role: 'agent', segments: [], timestamp: new Date().toISOString() };
            updated.push(last);
          }
          const segments = [...(last.segments || [])];
          segments.push({ type: 'text', content: `Error: ${errMsg}` });
          updated[updated.length - 1] = { ...last, segments };
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
      case 'SANDBOX':
      case 'sandbox': {
        const sandboxEvent = event as SseSandboxEvent;
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.role === 'agent') {
            const segments = [...(last.segments || [])];
            const sandboxSeg: SandboxSegment = {
              type: 'sandbox',
              sandboxType: sandboxEvent.sandbox_type || '',
              sandboxId: sandboxEvent.sandbox_id || '',
              message: sandboxEvent.message || '',
              hostname: sandboxEvent.hostname,
              ip: sandboxEvent.ip,
              image: sandboxEvent.image,
              durationMs: sandboxEvent.duration_ms,
            };
            // Replace existing sandbox segment or append
            const existingIdx = segments.findIndex(s => s.type === 'sandbox');
            if (existingIdx >= 0) {
              segments[existingIdx] = sandboxSeg;
            } else {
              segments.push(sandboxSeg);
            }
            updated[updated.length - 1] = { ...last, segments };
          }
          return updated;
        });
        break;
      }
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

  const ensureSession = useCallback(async (): Promise<string> => {
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
      dataset_configs: draftDatasetConfigs.length > 0
        ? draftDatasetConfigs.map(c => ({ dataset_id: c.dataset_id, permission: c.permission, is_output: c.is_output }))
        : undefined,
    });
    const id = res.sessionId;
    setSessionId(id);
    doConnectSSE(id);

    // Update loaded state from server response
    const loadedTools = res.loaded_tools;
    if (loadedTools && loadedTools.length > 0) {
      const { ids } = mergeIdNames(loadedTools);
      setLoadedToolIds(ids);
      setLoadedNames(prev => { const m = new Map(prev); for (const t of loadedTools) m.set(t.id, t.name); return m; });
      showToast(`Loaded ${loadedTools.length} tool(s)`);
    }
    const loadedSkills = res.loaded_skills;
    if (loadedSkills && loadedSkills.length > 0) {
      setLoadedSkillIds(new Set(loadedSkills.map(s => s.id)));
      setLoadedNames(prev => { const m = new Map(prev); for (const s of loadedSkills) m.set(s.id, s.name); return m; });
      showToast(`Loaded ${loadedSkills.length} skill(s)`);
    }
    const loadedSubAgents = res.loaded_sub_agents;
    if (loadedSubAgents && loadedSubAgents.length > 0) {
      setLoadedSubAgentIds(new Set(loadedSubAgents.map(a => a.id)));
      setLoadedNames(prev => { const m = new Map(prev); for (const a of loadedSubAgents) m.set(a.id, a.name); return m; });
      showToast(`Loaded ${loadedSubAgents.length} sub-agent(s)`);
    }
    setPreToolIds(new Set());
    setPreSkillIds(new Set());
    setPreSubAgentIds(new Set());

    // Wait for SSE to connect
    await new Promise(resolve => setTimeout(resolve, 500));
    return id;
  }, [doConnectSSE, preSkillIds, preSubAgentIds, preToolIds, selectedAgentId, sessionId, showToast]);

  const handleSend = useCallback(async (text: string, attachments: ComposerAttachment[]) => {
    if ((!text && attachments.length === 0) || status !== 'idle' || !selectedAgentId) return;

    hydrateRequestSeqRef.current += 1;
    setMessages(prev => [...prev, {
      role: 'user',
      segments: [{ type: 'text', content: text }],
      attachments: attachments.length > 0 ? attachments : undefined,
      timestamp: new Date().toISOString(),
    }]);
    setStatus('running');
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    setPlanTodos(null);
    setMessages(prev => [...prev, { role: 'agent', segments: [], timestamp: new Date().toISOString() }]);

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
  }, [ensureSession, selectedAgentId, showToast, status, variableValues]);

  const handleApproval = useCallback(async (decision: 'APPROVE' | 'DENY') => {
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
  }, [sessionId, awaitInfo]);

  const handleCancel = useCallback(() => {
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    setStatus('idle');
    setIsThinking(false);
    setAwaitInfo(null);
    setPlanTodos(null);
    if (sessionId) sessionApi.cancel(sessionId).catch(() => {});
  }, [sessionId]);

  const handleNewChat = useCallback(() => {
    hydrateRequestSeqRef.current += 1;
    sseControllerRef.current?.abort();
    sseControllerRef.current = null;
    composerRef.current?.reset();
    // Intentionally NOT calling sessionApi.close(sessionId): the previous session
    // may still be streaming an agent reply. Closing it forcibly cancels the turn
    // and loses the partial reply. The session stays alive on the backend, runs to
    // TURN_COMPLETE, persists, and is reclaimable from the sidebar. Idle cleanup
    // is the backend's responsibility (see task: idle session TTL sweeper).
    setSessionId(null);
    setMessages([]);
    setSessionArtifacts([]);
    setActiveArtifact(null);
    setActiveSandboxTerminal(null);
    setStatus('idle');
    setVisibleMessageLimit(INITIAL_VISIBLE_MESSAGES);
    setAwaitInfo(null);
    setPlanTodos(null);
    setCompressionInfo(null);
    setLoadedToolIds(new Set());
    setLoadedSkillIds(new Set());
    setLoadedSubAgentIds(new Set());
    setPreToolIds(new Set());
    setPreSkillIds(new Set());
    setPreSubAgentIds(new Set());
    setLoadedNames(new Map());
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    sessionStorage.removeItem('chat_messages');
    sessionStorage.removeItem('chat_sessionId');
    sessionStorage.removeItem('chat_artifacts');
  }, []);

  const handleSelectAgent = useCallback((id: string) => {
    handleNewChat();
    setSelectedAgentId(id);
  }, [handleNewChat]);

  const skillNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const s of availableSkills) map.set(s.id, s.name);
    return map;
  }, [availableSkills]);

  const agentNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of agents) map.set(a.id, a.name);
    return map;
  }, [agents]);

  const getSkillChipName = useCallback((id: string) => loadedNames.get(id) || skillNameMap.get(id) || id, [loadedNames, skillNameMap]);
  const getAgentChipName = useCallback((id: string) => loadedNames.get(id) || agentNameMap.get(id) || id, [agentNameMap, loadedNames]);

  // Merge IdName pairs from API into id set and name map
  const mergeIdNames = (idNames: IdName[]): { ids: Set<string>; names: Map<string, string> } => {
    const ids = new Set<string>();
    const names = new Map<string, string>();
    for (const item of idNames) {
      ids.add(item.id);
      names.set(item.id, item.name);
    }
    return { ids, names };
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
  const selectedAgent = useMemo(() => agents.find(a => a.id === selectedAgentId), [agents, selectedAgentId]);
  const agentVariableEntries = useMemo(() => Object.entries(selectedAgent?.variables || {}), [selectedAgent]);

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
  // Open config modal — pre-populate selections from agent config and session state
  const openConfigModal = useCallback(() => {
    // Tools: start with agent's configured tools + session tools
    const agentToolIds = selectedAgent?.tools?.map(t => t.id) || [];
    const sessionToolIds = sessionId ? loadedToolIds : preToolIds;
    setSelectedToolIds(new Set([...agentToolIds, ...sessionToolIds]));
    fetchTools();

    // Skills
    const agentSkillIds = selectedAgent?.skill_ids || [];
    const sessionSkillIds = sessionId ? loadedSkillIds : preSkillIds;
    setSelectedSkillIds(new Set([...agentSkillIds, ...sessionSkillIds]));
    fetchSkills();

    // Subagents
    const agentSubAgentIds = selectedAgent?.subagent_ids || [];
    const sessionSubAgentIds = sessionId ? loadedSubAgentIds : preSubAgentIds;
    setSelectedAgentIds(new Set([...agentSubAgentIds, ...sessionSubAgentIds]));

    setShowConfigModal(true);
  }, [sessionId, selectedAgent, loadedToolIds, preToolIds, loadedSkillIds, preSkillIds, loadedSubAgentIds, preSubAgentIds, fetchTools, fetchSkills]);

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
      setShowConfigModal(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreSubAgentIds(new Set(selectedAgentIds));
      showToast(`Selected ${selectedAgentIds.size} agent(s), will load as subagent on first message`);
      setShowAgentPicker(false);
      setShowConfigModal(false);
      setSelectedAgentIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadSubAgents(sessionId, Array.from(selectedAgentIds));
      if (res.loaded_sub_agents && res.loaded_sub_agents.length > 0) {
        setLoadedSubAgentIds(prev => {
          const next = new Set(prev);
          for (const a of res.loaded_sub_agents) next.add(a.id);
          return next;
        });
        setLoadedNames(prev => {
          const next = new Map(prev);
          for (const a of res.loaded_sub_agents) next.set(a.id, a.name);
          return next;
        });
        showToast(`Loaded ${res.loaded_sub_agents.length} agent(s) as subagent(s)`);
      }
      setShowAgentPicker(false);
      setShowConfigModal(false);
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
      setShowConfigModal(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreToolIds(new Set(selectedToolIds));
      showToast(`Selected ${selectedToolIds.size} tool(s), will load on first message`);
      setShowToolPicker(false);
      setShowConfigModal(false);
      setSelectedToolIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadTools(sessionId, Array.from(selectedToolIds).map(id => ({ id } as ToolRef)));
      if (res.loaded_tools && res.loaded_tools.length > 0) {
        setLoadedToolIds(prev => {
          const next = new Set(prev);
          for (const t of res.loaded_tools) next.add(t.id);
          return next;
        });
        setLoadedNames(prev => {
          const next = new Map(prev);
          for (const t of res.loaded_tools) next.set(t.id, t.name);
          return next;
        });
        showToast(`Loaded ${res.loaded_tools.length} tool(s)`);
      }
      setShowToolPicker(false);
      setShowConfigModal(false);
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
      setShowConfigModal(false);
      return;
    }

    if (!sessionId) {
      // No session yet — save as pre-session selections
      setPreSkillIds(new Set(selectedSkillIds));
      showToast(`Selected ${selectedSkillIds.size} skill(s), will load on first message`);
      setShowSkillPicker(false);
      setShowConfigModal(false);
      setSelectedSkillIds(new Set());
      return;
    }

    try {
      const res = await sessionApi.loadSkills(sessionId, Array.from(selectedSkillIds));
      if (res.loaded_skills && res.loaded_skills.length > 0) {
        setLoadedSkillIds(prev => {
          const next = new Set(prev);
          for (const s of res.loaded_skills) next.add(s.id);
          return next;
        });
        setLoadedNames(prev => {
          const next = new Map(prev);
          for (const s of res.loaded_skills) next.set(s.id, s.name);
          return next;
        });
        showToast(`Loaded ${res.loaded_skills.length} skill(s)`);
      }
      setShowSkillPicker(false);
      setShowConfigModal(false);
      setSelectedSkillIds(new Set());
    } catch (err) {
      console.error('Failed to load skills:', err);
      showToast('Failed to load skills');
    }
  }, [sessionId, selectedSkillIds, showToast]);

  const handleVoiceLanguageChange = useCallback((lang: string) => {
    setVoiceLanguage(lang);
    if (voiceIsListening) {
      stopListening();
      startListening(lang);
    }
  }, [startListening, stopListening, voiceIsListening]);

  const handleToggleVoiceSidebar = useCallback(() => {
    if (showVoiceSidebar) {
      stopListening();
      setShowVoiceSidebar(false);
    } else {
      openVoiceSidebar();
    }
  }, [openVoiceSidebar, showVoiceSidebar, stopListening]);

  const handleSendToChat = useCallback((text: string) => {
    composerRef.current?.setDraft(text);
  }, []);

  const handleSendAllToChat = useCallback(() => {
    const allText = voiceSegments.map(s => s.text).join('\n');
    composerRef.current?.setDraft(allText);
  }, [voiceSegments]);

  return (
    <div className="flex h-full">
      <ChatSessionsSidebar
        currentSessionId={sessionId}
        refreshKey={sidebarRefreshKey}
        onOpen={hydrateSession}
        onNewChat={handleNewChat}
      />
      <div className="flex flex-col h-full flex-1 min-w-0 overflow-hidden">
      <AgentSelector
        status={status}
        myAgents={myAgents}
        otherAgents={otherAgents}
        selectedAgentId={selectedAgentId}
        selectedAgent={selectedAgent}
        onSelectAgent={handleSelectAgent}
      />

      {/* Chat messages */}
      <ChatMessagesPanel
        messages={messages}
        status={status}
        isThinking={isThinking}
        planTodos={planTodos}
        compressionInfo={compressionInfo}
        sessionArtifacts={sessionArtifacts}
        selectedAgentName={selectedAgent?.name}
        agentVariableEntries={agentVariableEntries}
        variableValues={variableValues}
        variablesExpanded={variablesExpanded}
        variablesPanelRef={variablesPanelRef}
        messagesContainerRef={messagesContainerRef}
        bottomRef={bottomRef}
        showJumpToBottom={showJumpToBottom}
        visibleMessageLimit={visibleMessageLimit}
        onMessagesScroll={handleMessagesScroll}
        onToggleVariables={handleToggleVariables}
        onVariableChange={handleVariableChange}
        onShowEarlierMessages={handleShowEarlierMessages}
        onScrollToBottom={scrollToBottom}
        onOpenArtifact={openArtifact}
        onOpenSandboxTerminal={openSandboxTerminal}
        onApproval={handleApproval}
      />

      <ChatComposer
        ref={composerRef}
        status={status}
        selectedAgentId={selectedAgentId}
        messagesContainerRef={messagesContainerRef}
        loadedToolIds={loadedToolIds}
        loadedSkillIds={loadedSkillIds}
        loadedSubAgentIds={loadedSubAgentIds}
        preToolIds={preToolIds}
        preSkillIds={preSkillIds}
        preSubAgentIds={preSubAgentIds}
        datasetConfigs={draftDatasetConfigs.map(c => {
          const ds = datasets.find(d => d.id === c.dataset_id);
          return { dataset_id: c.dataset_id, permission: c.permission, is_output: c.is_output, name: ds?.name };
        })}
        showVoiceSidebar={showVoiceSidebar}
        getSkillChipName={getSkillChipName}
        getAgentChipName={getAgentChipName}
        onOpenConfig={openConfigModal}
        onToggleVoiceSidebar={handleToggleVoiceSidebar}
        onSend={handleSend}
        onCancel={handleCancel}
        onToast={showToast}
      />

      {/* Toast notification */}
      {toast && (
        <div className="fixed bottom-24 left-1/2 -translate-x-1/2 z-50 px-4 py-2 rounded-lg text-sm shadow-lg"
          style={{ background: 'var(--color-bg)', color: 'var(--color-text)', border: '1px solid var(--color-border)' }}>
          {toast}
        </div>
      )}

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

      {/* Unified Config Modal (Tools, Skills, Subagents, Dataset) */}
      {showConfigModal && (
        <ChatConfigModal
          availableTools={availableTools}
          toolsLoading={toolsLoading}
          loadedToolIds={loadedToolIds}
          preToolIds={preToolIds}
          selectedToolIds={selectedToolIds}
          onToggleTool={toggleTool}
          onLoadTools={loadSelectedTools}
          fetchTools={fetchTools}
          availableSkills={availableSkills}
          skillsLoading={skillsLoading}
          loadedSkillIds={loadedSkillIds}
          preSkillIds={preSkillIds}
          selectedSkillIds={selectedSkillIds}
          onToggleSkill={toggleSkill}
          onLoadSkills={loadSelectedSkills}
          agents={agents}
          selectedAgentId={selectedAgentId}
          loadedSubAgentIds={loadedSubAgentIds}
          preSubAgentIds={preSubAgentIds}
          selectedAgentIds={selectedAgentIds}
          onToggleAgent={toggleAgent}
          onLoadAgents={loadSelectedAgents}
          draftDatasetConfigs={draftDatasetConfigs}
          onUpdateDatasetConfigs={setDraftDatasetConfigs}
          onClose={() => setShowConfigModal(false)}
        />
      )}
      </div>
      {showVoiceSidebar && (
        <Suspense fallback={<div className="w-80 shrink-0 border-l" style={{ borderColor: 'var(--color-border)' }} />}>
          <VoiceTranscriberSidebar
            segments={voiceSegments}
            isListening={voiceIsListening}
            error={voiceError}
            language={voiceLanguage}
            onLanguageChange={handleVoiceLanguageChange}
            onStop={stopListening}
            onClose={() => { stopListening(); setShowVoiceSidebar(false); }}
            onStartListening={() => startListening(voiceLanguage)}
            onClear={clearSegments}
            onSendToChat={handleSendToChat}
            onSendAllToChat={handleSendAllToChat}
          />
        </Suspense>
      )}
      {activeArtifact && (
        <Suspense fallback={null}>
          <ArtifactDrawer artifact={activeArtifact} onClose={() => setActiveArtifact(null)} />
        </Suspense>
      )}
      {activeSandboxTerminal && (
        <SandboxTerminalPanel sandbox={activeSandboxTerminal} onClose={() => setActiveSandboxTerminal(null)} />
      )}
    </div>
  );
}
