import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, ClipboardEvent, ReactNode } from 'react';
import { Check, ChevronLeft, CircleHelp, Paperclip, Send, ThumbsDown, X } from 'lucide-react';
import { sessionApi } from '../../api/session';
import type {
  SseEvent, SseTextChunkEvent, SseReasoningChunkEvent, SseToolStartEvent, SseToolResultEvent,
  SseToolApprovalRequestEvent, SseTurnCompleteEvent, SseErrorEvent, SseStatusChangeEvent,
  SseCompressionEvent, SseSandboxEvent, SseCustomEvent, SseEnvironmentOutputChunkEvent,
} from '../../api/session';
import { comparisonApi } from '../../api/comparison';
import type { ComparisonSessionContext, ComparisonVote } from '../../api/comparison';
import type { AwaitInfo, ChatMessage, ToolEvent, ToolsSegment } from './types';
import { appendCustomSegments, compressionSegment, customEventSegments, historyToChatMessages } from './utils';
import { ChatMessageRow } from './components/ChatMessagesPanel';
import AuthedImage from './components/AuthedImage';

type ArmKey = 'a' | 'b';
type VoteChoice = 'A' | 'B' | 'TIE' | 'NEITHER';

interface ArmState {
  sessionId: string;
  messages: ChatMessage[];
  status: 'idle' | 'running';
  isThinking: boolean;
}

interface ComposerAttachment {
  url: string;
  type: string;
  file_name?: string;
  content_type?: string;
  category?: string;
  container?: string;
  blob_name?: string;
  uploading?: boolean;
  name?: string;
}

interface TurnView {
  index: number;
  user?: ChatMessage;
  agentsA: ChatMessage[];
  agentsB: ChatMessage[];
}

export interface ComparisonChatProps {
  context: ComparisonSessionContext;
  agentName?: string;
  onExit: () => void;
}

function appendAgentBubble(prev: ChatMessage[]): ChatMessage[] {
  const updated = [...prev];
  const last = updated[updated.length - 1];
  if (!last || last.role !== 'agent') {
    updated.push({ role: 'agent', segments: [], timestamp: new Date().toISOString() });
  }
  return updated;
}

function appendTextChunk(prev: ChatMessage[], chunk: string): ChatMessage[] {
  const updated = appendAgentBubble(prev);
  const last = updated[updated.length - 1];
  const segments = [...(last.segments || [])];
  const lastSeg = segments[segments.length - 1];
  if (lastSeg?.type === 'text') {
    segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
  } else {
    const existingIdx = segments.findIndex(s => s.type === 'text');
    if (existingIdx >= 0) {
      const existing = segments[existingIdx];
      if (existing.type === 'text') {
        const next = { ...existing, content: existing.content + '\n\n' + chunk };
        segments.splice(existingIdx, 1);
        segments.push(next);
      }
    } else {
      segments.push({ type: 'text', content: chunk });
    }
  }
  updated[updated.length - 1] = { ...last, segments };
  return updated;
}

function appendReasoningChunk(prev: ChatMessage[], chunk: string): ChatMessage[] {
  const updated = appendAgentBubble(prev);
  const last = updated[updated.length - 1];
  const segments = [...(last.segments || [])];
  const lastSeg = segments[segments.length - 1];
  if (lastSeg?.type === 'thinking') {
    segments[segments.length - 1] = { ...lastSeg, content: lastSeg.content + chunk };
  } else {
    const existingIdx = segments.findIndex(s => s.type === 'thinking');
    if (existingIdx >= 0) {
      const existing = segments[existingIdx];
      if (existing.type === 'thinking') segments[existingIdx] = { ...existing, content: existing.content + chunk };
    } else {
      segments.push({ type: 'thinking', content: chunk });
    }
  }
  updated[updated.length - 1] = { ...last, segments };
  return updated;
}

function applyToolStart(prev: ChatMessage[], event: SseToolStartEvent): ChatMessage[] {
  const updated = appendAgentBubble(prev);
  const last = updated[updated.length - 1];
  const segments = [...(last.segments || [])];
  let toolsSegIdx = segments.findIndex(s => s.type === 'tools');
  if (toolsSegIdx < 0) {
    segments.push({ type: 'tools', tools: [] });
    toolsSegIdx = segments.length - 1;
  }
  const toolsSeg = segments[toolsSegIdx] as ToolsSegment;
  const newTool: ToolEvent = {
    type: 'start', tool: event.tool_name, callId: event.call_id,
    arguments: event.tool_args ? JSON.stringify(event.tool_args) : undefined,
    taskId: event.task_id, runInBackground: event.run_in_background, model: event.model,
  };
  segments[toolsSegIdx] = { ...toolsSeg, tools: [...toolsSeg.tools, newTool] };
  updated[updated.length - 1] = { ...last, segments };
  return updated;
}

function applyToolResult(prev: ChatMessage[], event: SseToolResultEvent): ChatMessage[] {
  const updated = [...prev];
  const last = updated[updated.length - 1];
  if (!last || last.role !== 'agent') return prev;
  const segments = [...(last.segments || [])];
  const patchTool = (tool: ToolEvent): ToolEvent => {
    const patched: ToolEvent = {
      ...tool, type: 'result', result: event.result,
      resultStatus: event.status || 'COMPLETED', toolType: event.tool_type,
    };
    if (tool.children) patched.children = tool.children.map(patchTool);
    return patched;
  };
  for (let si = 0; si < segments.length; si++) {
    const seg = segments[si];
    if (seg.type !== 'tools') continue;
    if (!seg.tools.some(t => t.callId === event.call_id || t.children?.some(c => c.callId === event.call_id))) continue;
    segments[si] = { ...seg, tools: seg.tools.map(tool => tool.callId === event.call_id || tool.children?.some(c => c.callId === event.call_id) ? patchTool(tool) : tool) };
    updated[updated.length - 1] = { ...last, segments };
    return updated;
  }
  return updated;
}

function applyEnvironmentOutput(prev: ChatMessage[], event: SseEnvironmentOutputChunkEvent): ChatMessage[] {
  const chunk = event.chunk || '';
  if (!chunk) return prev;
  const updated = appendAgentBubble(prev);
  const last = updated[updated.length - 1];
  const segments = [...(last.segments || [])];
  let toolsSegIdx = segments.findIndex(s => s.type === 'tools');
  if (toolsSegIdx < 0) {
    segments.push({ type: 'tools', tools: [] });
    toolsSegIdx = segments.length - 1;
  }
  const toolsSeg = segments[toolsSegIdx] as ToolsSegment;
  const appendOutput = (tool: ToolEvent): ToolEvent => {
    const next: ToolEvent = tool.callId === event.call_id ? { ...tool, output: (tool.output || '') + chunk } : tool;
    if (tool.children) next.children = tool.children.map(appendOutput);
    return next;
  };
  const hasCall = toolsSeg.tools.some(t => t.callId === event.call_id || t.children?.some(c => c.callId === event.call_id));
  const tools = hasCall
    ? toolsSeg.tools.map(appendOutput)
    : [...toolsSeg.tools, { type: 'start' as const, tool: event.source || 'environment', callId: event.call_id, output: chunk }];
  segments[toolsSegIdx] = { ...toolsSeg, tools };
  updated[updated.length - 1] = { ...last, segments };
  return updated;
}

function applySseToMessages(prev: ChatMessage[], event: SseEvent): ChatMessage[] {
  switch (event.type?.toLowerCase()) {
    case 'text_chunk': {
      const chunk = (event as SseTextChunkEvent).content || '';
      return chunk ? appendTextChunk(prev, chunk) : prev;
    }
    case 'reasoning_chunk': {
      const chunkEvent = event as SseReasoningChunkEvent;
      if (chunkEvent.is_final_chunk || !chunkEvent.content) return prev;
      return appendReasoningChunk(prev, chunkEvent.content);
    }
    case 'tool_start':
      return applyToolStart(prev, event as SseToolStartEvent);
    case 'tool_result':
      return applyToolResult(prev, event as SseToolResultEvent);
    case 'tool_approval_request': {
      const approvalEvent = event as SseToolApprovalRequestEvent;
      const info: AwaitInfo = { callId: approvalEvent.call_id, tool: approvalEvent.tool_name, arguments: approvalEvent.arguments || '' };
      const updated = appendAgentBubble(prev);
      const last = updated[updated.length - 1];
      updated[updated.length - 1] = { ...last, approval: info };
      return updated;
    }
    case 'turn_complete': {
      const completeEvent = event as SseTurnCompleteEvent;
      if (prev.length === 0) return prev;
      const last = prev[prev.length - 1];
      if (last.role !== 'agent') return prev;
      if (completeEvent.cancelled && !last.segments?.some(s => s.type !== 'tasks')) return prev.slice(0, -1);
      const updated = [...prev];
      if (!completeEvent.cancelled && !last.segments?.length && completeEvent.output) {
        updated[updated.length - 1] = { ...last, timestamp: new Date().toISOString(), segments: [{ type: 'text', content: completeEvent.output }] };
      } else {
        updated[updated.length - 1] = { ...last, timestamp: new Date().toISOString() };
      }
      return updated;
    }
    case 'error': {
      const errorEvent = event as SseErrorEvent;
      const updated = appendAgentBubble(prev);
      const last = updated[updated.length - 1];
      const segments = [...(last.segments || [])];
      segments.push({ type: 'text', content: `Error: ${errorEvent.message || 'Unknown error'}` });
      updated[updated.length - 1] = { ...last, segments };
      return updated;
    }
    case 'compression': {
      const compressionEvent = event as SseCompressionEvent;
      if (!compressionEvent.completed) return prev;
      const segment = compressionSegment({
        before: compressionEvent.before_count,
        after: compressionEvent.after_count,
        contextTokens: compressionEvent.context_tokens,
        maxContextTokens: compressionEvent.max_context_tokens,
        triggerThreshold: compressionEvent.trigger_threshold,
      });
      const updated = appendAgentBubble(prev);
      const last = updated[updated.length - 1];
      const segments = [...(last.segments || [])];
      const existingIdx = segments.findIndex(s => s.type === 'compression');
      if (existingIdx >= 0) segments[existingIdx] = segment;
      else segments.push(segment);
      updated[updated.length - 1] = { ...last, segments };
      return updated;
    }
    case 'sandbox': {
      const sandboxEvent = event as SseSandboxEvent;
      const segment = {
        type: 'sandbox' as const,
        sandboxType: sandboxEvent.sandbox_type || '',
        sandboxId: sandboxEvent.sandbox_id || '',
        message: sandboxEvent.message || '',
        hostname: sandboxEvent.hostname,
        ip: sandboxEvent.ip,
        image: sandboxEvent.image,
        durationMs: sandboxEvent.duration_ms,
      };
      const updated = appendAgentBubble(prev);
      const last = updated[updated.length - 1];
      updated[updated.length - 1] = { ...last, segments: [...(last.segments || []), segment] };
      return updated;
    }
    case 'custom': {
      const customEvent = event as SseCustomEvent;
      const incoming = customEventSegments(customEvent);
      if (incoming.length === 0) return prev;
      const updated = appendAgentBubble(prev);
      const last = updated[updated.length - 1];
      updated[updated.length - 1] = { ...last, segments: appendCustomSegments(last.segments || [], incoming) };
      return updated;
    }
    case 'environment_output_chunk':
      return applyEnvironmentOutput(prev, event as SseEnvironmentOutputChunkEvent);
    default:
      return prev;
  }
}

/** Splits a message list into turns: a new turn starts at each user message, agent rows attach to it. */
function splitTurns(messages: ChatMessage[]): { user?: ChatMessage; agents: ChatMessage[] }[] {
  const turns: { user?: ChatMessage; agents: ChatMessage[] }[] = [];
  for (const msg of messages) {
    if (msg.role === 'user') {
      turns.push({ user: msg, agents: [] });
    } else {
      if (turns.length === 0) turns.push({ agents: [] });
      turns[turns.length - 1].agents.push(msg);
    }
  }
  return turns;
}

export default function ComparisonChat({ context, agentName, onExit }: ComparisonChatProps) {
  const primarySessionId = context.primary_session_id;
  const secondarySessionId = context.secondary_session_id;
  const comparisonId = context.comparison_id;
  const [arms, setArms] = useState<{ a: ArmState; b: ArmState } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [actionError, setActionError] = useState<string | null>(null);
  const [votes, setVotes] = useState<Record<number, ComparisonVote>>({});
  const [commentDrafts, setCommentDrafts] = useState<Record<number, string>>({});
  const controllers = useRef<Record<ArmKey, AbortController | null>>({ a: null, b: null });
  const reconnects = useRef<Record<ArmKey, number>>({ a: 0, b: 0 });
  const sendingRef = useRef(false);
  const armsRef = useRef<{ a: ArmState; b: ArmState } | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  armsRef.current = arms;

  useEffect(() => {
    if (!primarySessionId || !secondarySessionId) {
      setLoadError('comparison session pair is incomplete');
      return;
    }
    let alive = true;
    Promise.all([sessionApi.history(primarySessionId), sessionApi.history(secondarySessionId)])
      .then(([historyA, historyB]) => {
        if (!alive) return;
        setArms({
          a: { sessionId: primarySessionId, messages: historyToChatMessages(historyA.messages), status: 'idle', isThinking: false },
          b: { sessionId: secondarySessionId, messages: historyToChatMessages(historyB.messages), status: 'idle', isThinking: false },
        });
      })
      .catch(err => {
        if (alive) setLoadError(err instanceof Error ? err.message : String(err));
      });
    const controllerRef = controllers.current;
    return () => {
      alive = false;
      controllerRef.a?.abort();
      controllerRef.b?.abort();
    };
  }, [primarySessionId, secondarySessionId]);

  useEffect(() => {
    setVotes(Object.fromEntries((context.votes || []).map(vote => [vote.turn, vote])));
  }, [context.votes]);

  const updateArm = useCallback((armKey: ArmKey, update: (arm: ArmState) => ArmState) => {
    setArms(prev => (prev ? { ...prev, [armKey]: update(prev[armKey]) } : prev));
  }, []);

  const handleArmEvent = useCallback((armKey: ArmKey, event: SseEvent) => {
    const type = event.type?.toLowerCase();
    if (type === 'status_change') {
      const statusEvent = event as SseStatusChangeEvent;
      if (statusEvent.status === 'running') {
        updateArm(armKey, arm => ({ ...arm, status: 'running' }));
      } else if (statusEvent.status === 'idle' || statusEvent.status === 'waiting' || statusEvent.status === 'error') {
        updateArm(armKey, arm => ({ ...arm, status: 'idle', isThinking: false }));
      }
      return;
    }
    if (type === 'turn_complete') {
      reconnects.current[armKey] = 0;
      updateArm(armKey, arm => ({
        ...arm, status: 'idle', isThinking: false,
        messages: applySseToMessages(arm.messages, event),
      }));
      return;
    }
    if (type === 'error') {
      updateArm(armKey, arm => ({
        ...arm, status: 'idle', isThinking: false,
        messages: applySseToMessages(arm.messages, event),
      }));
      return;
    }
    if (type === 'reasoning_chunk') {
      const chunkEvent = event as SseReasoningChunkEvent;
      updateArm(armKey, arm => ({
        ...arm,
        isThinking: chunkEvent.is_final_chunk ? false : arm.isThinking || !!chunkEvent.content,
        messages: applySseToMessages(arm.messages, event),
      }));
      return;
    }
    if (type === 'text_chunk' || type === 'tool_start') {
      updateArm(armKey, arm => ({ ...arm, status: 'running', isThinking: false, messages: applySseToMessages(arm.messages, event) }));
      return;
    }
    updateArm(armKey, arm => ({ ...arm, messages: applySseToMessages(arm.messages, event) }));
  }, [updateArm]);

  /**
   * A stream ended while the arm still shows running (no terminal event reached us): ask the backend
   * what actually happened. Finished turns are restored from history, still-running turns re-attach
   * the event stream so the spinner cannot outlive the turn.
   */
  const reconcileArm = useCallback(async (armKey: ArmKey) => {
    const state = armsRef.current?.[armKey];
    if (!state || state.status !== 'running') return;
    try {
      const status = await sessionApi.status(state.sessionId);
      if (status.status === 'running') {
        if (reconnects.current[armKey] >= 2) return; // give up; the tester can skip this arm
        reconnects.current[armKey] += 1;
        controllers.current[armKey]?.abort();
        controllers.current[armKey] = sessionApi.connectSSE(
          state.sessionId,
          event => handleArmEvent(armKey, event),
          () => undefined,
          () => { void reconcileArm(armKey); },
        );
        return;
      }
      const history = await sessionApi.history(state.sessionId);
      updateArm(armKey, arm => ({ ...arm, status: 'idle', isThinking: false, messages: historyToChatMessages(history.messages) }));
    } catch {
      // the backend did not answer; keep the arm as-is, the next close or user action retries
    }
  }, [handleArmEvent, updateArm]);

  const startStream = useCallback((armKey: ArmKey, text: string, outgoing: ComposerAttachment[]) => {
    const state = armsRef.current?.[armKey];
    if (!state) return;
    reconnects.current[armKey] = 0;
    updateArm(armKey, arm => ({ ...arm, status: 'running', isThinking: true }));
    const controller = sessionApi.sendMessageStream(
      state.sessionId, text, undefined,
      outgoing.length > 0
        ? outgoing.map(a => ({ url: a.url, type: a.type, file_name: a.file_name, content_type: a.content_type, category: a.category, container: a.container, blob_name: a.blob_name }))
        : undefined,
      event => handleArmEvent(armKey, event),
      err => {
        const message = err instanceof Error ? err.message : String(err);
        updateArm(armKey, arm => ({ ...arm, status: 'idle', isThinking: false, messages: appendTextChunk(appendAgentBubble(arm.messages), `Error: ${message}`) }));
      },
      // the stream can end without a terminal event (aborted/replaced connection): reconcile the arm
      // against the backend so a stuck "running" arm does not keep its spinner and skip button forever
      () => { void reconcileArm(armKey); },
    );
    controllers.current[armKey]?.abort();
    controllers.current[armKey] = controller;
  }, [handleArmEvent, reconcileArm, updateArm]);

  const anyRunning = arms != null && (arms.a.status === 'running' || arms.b.status === 'running');

  const sendTurn = useCallback((text: string) => {
    const trimmed = text.trim();
    const ready = attachments.filter(a => !a.uploading);
    if ((!trimmed && ready.length === 0) || !armsRef.current) return;
    // a second dispatch in the same beat (Enter + click) would double-send the turn: state has not
    // re-rendered yet, so the running guard alone cannot see the first send
    if (sendingRef.current) return;
    if (armsRef.current.a.status === 'running' || armsRef.current.b.status === 'running') return;
    sendingRef.current = true;
    window.setTimeout(() => { sendingRef.current = false; }, 1500);
    const userMessage: ChatMessage = {
      role: 'user',
      segments: trimmed ? [{ type: 'text', content: trimmed }] : [],
      attachments: ready.map(a => ({ url: a.url, type: a.type === 'IMAGE' ? 'IMAGE' : a.type === 'PDF' ? 'PDF' : 'FILE', file_name: a.file_name })),
      timestamp: new Date().toISOString(),
    };
    const placeholder: ChatMessage = { role: 'agent', segments: [], timestamp: new Date().toISOString() };
    setArms(prev => prev ? {
      a: { ...prev.a, messages: [...prev.a.messages, userMessage, { ...placeholder }] },
      b: { ...prev.b, messages: [...prev.b.messages, { ...userMessage }, { ...placeholder }] },
    } : prev);
    setInput('');
    setAttachments([]);
    startStream('a', trimmed, ready);
    startStream('b', trimmed, ready);
  }, [attachments, startStream]);

  const skipArm = useCallback(async (armKey: ArmKey) => {
    const state = armsRef.current?.[armKey];
    if (!state || state.status !== 'running') return;
    controllers.current[armKey]?.abort();
    try {
      await sessionApi.cancel(state.sessionId);
    } catch {
      // cancel is best-effort; the aborted stream still unlocks the arm
    }
    updateArm(armKey, arm => ({ ...arm, status: 'idle', isThinking: false }));
  }, [updateArm]);

  const handleApproval = useCallback(async (armKey: ArmKey, msg: ChatMessage, decision: 'APPROVE' | 'DENY') => {
    const state = armsRef.current?.[armKey];
    if (!state || !msg.approval) return;
    try {
      await sessionApi.approve(state.sessionId, msg.approval.callId, decision);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
      return;
    }
    updateArm(armKey, arm => ({
      ...arm,
      messages: arm.messages.map(m => (m === msg ? { ...m, approval: undefined } : m)),
    }));
  }, [updateArm]);

  const submitVote = useCallback(async (turn: number, chosen: VoteChoice) => {
    if (!comparisonId || !primarySessionId) return;
    try {
      const vote = await comparisonApi.vote(comparisonId, {
        session_a: primarySessionId,
        turn,
        chosen,
        // fall back to the already stored comment so a re-vote does not silently clear it
        comment: (commentDrafts[turn] ?? votes[turn]?.comment)?.trim() || undefined,
      });
      setVotes(prev => ({ ...prev, [turn]: vote }));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
    }
  }, [commentDrafts, comparisonId, primarySessionId, votes]);

  const uploadFile = useCallback(async (file: File) => {
    const contentType = file.type || 'application/octet-stream';
    const isMultimodal = contentType.startsWith('image/') || contentType === 'application/pdf';
    const category = isMultimodal ? 'multimodal' : 'sandbox';
    const entry: ComposerAttachment = { url: '', type: contentType.startsWith('image/') ? 'IMAGE' : contentType === 'application/pdf' ? 'PDF' : 'FILE', file_name: file.name, content_type: contentType, category, uploading: true, name: file.name };
    setAttachments(prev => [...prev, entry]);
    try {
      const credentialResponse = await fetch(`/api/blob/upload-credential?content_type=${encodeURIComponent(contentType)}&category=${category}`, {
        headers: { Authorization: `Bearer ${localStorage.getItem('apiKey')}` },
      });
      if (!credentialResponse.ok) throw new Error(`credential request failed: ${credentialResponse.status}`);
      const credential = await credentialResponse.json();
      const uploadResponse = await fetch(credential.upload_url, {
        method: 'PUT',
        headers: { 'x-ms-blob-type': 'BlockBlob', 'x-ms-blob-content-type': contentType },
        body: file,
      });
      if (!uploadResponse.ok) throw new Error(`upload failed: ${uploadResponse.status}`);
      setAttachments(prev => prev.map(a => (a === entry || (a.uploading && a.name === file.name)
        ? { ...a, url: credential.blob_url, container: credential.container, blob_name: credential.blob_name, uploading: false }
        : a)));
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
      setAttachments(prev => prev.filter(a => a !== entry));
    }
  }, []);

  const handleFileChange = useCallback((event: ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files;
    if (files) {
      for (const file of Array.from(files)) void uploadFile(file);
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, [uploadFile]);

  const handlePaste = useCallback((event: ClipboardEvent<HTMLTextAreaElement>) => {
    const items = event.clipboardData?.items;
    if (!items) return;
    const imageFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      if (items[i].type.startsWith('image/')) {
        const file = items[i].getAsFile();
        if (file) imageFiles.push(file);
      }
    }
    if (imageFiles.length > 0) {
      event.preventDefault();
      for (const file of imageFiles) void uploadFile(file);
    }
  }, [uploadFile]);

  const turns = useMemo<TurnView[]>(() => {
    const turnsA = splitTurns(arms?.a.messages || []);
    const turnsB = splitTurns(arms?.b.messages || []);
    const count = Math.max(turnsA.length, turnsB.length);
    const result: TurnView[] = [];
    for (let i = 0; i < count; i++) {
      result.push({
        index: i,
        user: turnsA[i]?.user ?? turnsB[i]?.user,
        agentsA: turnsA[i]?.agents ?? [],
        agentsB: turnsB[i]?.agents ?? [],
      });
    }
    return result;
  }, [arms]);

  const voteCounts = useMemo(() => {
    let a = 0, b = 0, tie = 0, neither = 0;
    for (const vote of Object.values(votes)) {
      if (vote.chosen === 'A') a++;
      else if (vote.chosen === 'B') b++;
      else if (vote.chosen === 'TIE') tie++;
      else if (vote.chosen === 'NEITHER') neither++;
    }
    return { a, b, tie, neither, total: Object.keys(votes).length };
  }, [votes]);

  const markdownComponents = useMemo(() => ({
    img({ src, alt }: { src?: string; alt?: string }) {
      const isAbsolute = !!src && (/^(https?:|data:|blob:|\/api\/)/.test(src) || src.startsWith('/'));
      if (isAbsolute) return <AuthedImage src={src!} alt={alt} />;
      return <span className="my-2 inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-xs" style={{ borderColor: 'var(--color-warning)', color: 'var(--color-text-secondary)' }}>image not available</span>;
    },
  }), []);

  const ended = context.status === 'ENDED';

  const renderArm = (turn: TurnView, armKey: ArmKey) => {
    const armMessages = armKey === 'a' ? turn.agentsA : turn.agentsB;
    const arm = arms?.[armKey];
    return (
      <div className="flex min-w-0 flex-col gap-3">
        {armMessages.length === 0 && (
          <div className="rounded-xl border px-4 py-3 text-xs" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}>
            {arm?.status === 'running' ? '生成中…' : '该版本本轮没有输出'}
          </div>
        )}
        {armMessages.map((msg, idx) => (
          <div key={`${armKey}-${turn.index}-${idx}`} className="min-w-0 overflow-x-auto">
            <ChatMessageRow
              msg={msg}
              msgIndex={idx}
              messagesLength={armMessages.length}
              isStreamingLast={arm?.status === 'running' && idx === armMessages.length - 1}
              isThinking={arm?.isThinking === true}
              planTodos={null}
              sessionArtifacts={[]}
              agentMarkdownComponents={markdownComponents}
              onOpenArtifact={() => { /* comparison view renders images inline */ }}
              terminalEnabled={false}
              onOpenSandboxTerminal={() => { /* sandbox terminals stay disabled inside the comparison view */ }}
              onApproval={decision => void handleApproval(armKey, msg, decision)}
              showFeedback={false}
              onQuickReply={value => sendTurn(value)}
              interactiveDisabled={arm?.status === 'running'}
            />
          </div>
        ))}
        {arm?.status === 'running' && (
          <button
            type="button"
            onClick={() => void skipArm(armKey)}
            className="inline-flex w-fit items-center gap-1 rounded-lg border px-2.5 py-1 text-[11px] cursor-pointer hover:opacity-80"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)', background: 'var(--color-bg-secondary)' }}
          >
            跳过该版本本轮
          </button>
        )}
      </div>
    );
  };

  const renderVoteBar = (turn: TurnView) => {
    const settled = (turn.agentsA.length > 0 || turn.agentsB.length > 0)
      && !(anyRunning && turn.index === turns.length - 1 && (turn.agentsA.length === 0 || turn.agentsB.length === 0));
    if (!settled || !comparisonId) return null;
    const turnNumber = turn.index + 1;
    const vote = votes[turnNumber];
    const option = (choice: VoteChoice, label: string, icon: ReactNode) => (
      <button
        key={choice}
        type="button"
        onClick={() => void submitVote(turnNumber, choice)}
        className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium cursor-pointer hover:opacity-80"
        style={{
          borderColor: vote?.chosen === choice ? 'var(--color-primary)' : 'var(--color-border)',
          background: vote?.chosen === choice ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
          color: vote?.chosen === choice ? 'white' : 'var(--color-text-secondary)',
        }}
      >
        {icon}
        {label}
        {vote?.chosen === choice && <Check size={12} />}
      </button>
    );
    return (
      <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>第 {turnNumber} 轮投票</span>
        {option('A', '版本 1', <ChevronLeft size={13} />)}
        {option('B', '版本 2', <Send size={12} />)}
        {option('TIE', '都差不多', <CircleHelp size={13} />)}
        {option('NEITHER', '都不好', <ThumbsDown size={13} />)}
        <input
          value={commentDrafts[turnNumber] ?? vote?.comment ?? ''}
          onChange={e => setCommentDrafts(prev => ({ ...prev, [turnNumber]: e.target.value }))}
          onBlur={() => { if (vote) void submitVote(turnNumber, vote.chosen as VoteChoice); }}
          placeholder="可选评论"
          className="min-w-[160px] flex-1 rounded-lg border px-2 py-1 text-xs outline-none"
          style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
        />
      </div>
    );
  };

  return (
    <div className="flex h-full min-w-0 flex-col">
      <div className="border-b px-6 py-3" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-sm font-semibold">版本对比测试{agentName ? ` · ${agentName}` : ''}</span>
          <span className="rounded-full px-2 py-0.5 text-[11px]" style={{ background: ended ? 'var(--color-bg-tertiary)' : 'var(--color-primary)', color: ended ? 'var(--color-text-secondary)' : 'white' }}>
            {ended ? '测试已结束（仍可继续对话）' : '测试进行中'}
          </span>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            每轮同时运行两个版本，请选择更满意的 · 已投 {voteCounts.total} 票（1: {voteCounts.a} / 2: {voteCounts.b} / 平 {voteCounts.tie} / 差 {voteCounts.neither}）
          </span>
          {context.budget_usd != null && (
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>预算 ${context.budget_usd}</span>
          )}
          <button type="button" onClick={onExit}
            className="ml-auto inline-flex items-center gap-1 rounded-lg border px-2.5 py-1 text-xs cursor-pointer hover:opacity-80"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            <X size={12} /> 退出对比视图
          </button>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto flex max-w-6xl flex-col gap-8">
          {loadError && (
            <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{loadError}</div>
          )}
          {actionError && (
            <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{actionError}</div>
          )}
          {!arms && !loadError && (
            <div className="py-12 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading comparison sessions…</div>
          )}
          {turns.map(turn => (
            <div key={turn.index} className="flex flex-col gap-3">
              {turn.user && (
                <ChatMessageRow
                  msg={turn.user}
                  msgIndex={0}
                  messagesLength={1}
                  isStreamingLast={false}
                  isThinking={false}
                  planTodos={null}
                  sessionArtifacts={[]}
                  agentMarkdownComponents={markdownComponents}
                  onOpenArtifact={() => {}}
                  terminalEnabled={false}
                  onOpenSandboxTerminal={() => {}}
                  onApproval={() => {}}
                  showFeedback={false}
                  onQuickReply={value => sendTurn(value)}
                  interactiveDisabled={anyRunning}
                />
              )}
              <div className="rounded-2xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
                <div className="grid grid-cols-2 gap-4">
                  <div className="min-w-0">
                    <div className="mb-2 text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>版本 1</div>
                    {renderArm(turn, 'a')}
                  </div>
                  <div className="min-w-0">
                    <div className="mb-2 text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>版本 2</div>
                    {renderArm(turn, 'b')}
                  </div>
                </div>
                {renderVoteBar(turn)}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="border-t px-6 py-3" style={{ borderColor: 'var(--color-border)' }}>
        {attachments.length > 0 && (
          <div className="mb-2 flex flex-wrap gap-2">
            {attachments.map((attachment, idx) => (
              <span key={idx} className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-1 text-xs" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                <Paperclip size={11} />
                {attachment.name || attachment.file_name}
                {attachment.uploading ? ' (uploading…)' : ''}
                <button type="button" className="cursor-pointer" onClick={() => setAttachments(prev => prev.filter(a => a !== attachment))}><X size={11} /></button>
              </span>
            ))}
          </div>
        )}
        <div className="flex items-end gap-2">
          <button type="button" onClick={() => fileInputRef.current?.click()} disabled={anyRunning}
            className="rounded-lg border p-2 cursor-pointer disabled:opacity-40" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }} title="Add attachment">
            <Paperclip size={16} />
          </button>
          <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleFileChange} />
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onPaste={handlePaste}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendTurn(input); } }}
            placeholder={anyRunning ? '两个版本运行中，完成后可发送下一轮…' : '输入消息，两个版本都会运行这一轮'}
            rows={2}
            disabled={anyRunning}
            className="min-h-[44px] flex-1 resize-y rounded-xl border px-3 py-2 text-sm outline-none disabled:opacity-60"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
          />
          <button type="button" onClick={() => sendTurn(input)} disabled={anyRunning || (!input.trim() && attachments.filter(a => !a.uploading).length === 0)}
            className="inline-flex items-center gap-1.5 rounded-xl px-4 py-2.5 text-sm font-medium cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)', color: 'white' }}>
            <Send size={14} /> 发送
          </button>
        </div>
      </div>
    </div>
  );
}
