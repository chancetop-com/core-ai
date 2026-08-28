import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft,
  Bot,
  Brain,
  Check,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  FlaskConical,
  GitBranch,
  Loader2,
  Play,
  Users,
  Wrench,
} from 'lucide-react';
import {
  api,
  type ReplayExperiment,
  type ReplayRun,
  type ReplayRunSummary,
  type ReplaySample,
  type Span,
} from '../../api/client';
import { usePermission } from '../../api/permissions';
import {
  buildSpanTree,
  extractAssistantContent,
  formatCostUsd,
  formatDuration,
  formatRelativeTime,
  formatTokenPair,
  type ExtractedAssistantOutput,
} from '../traces/traceViewModel';

interface SnapshotSpan {
  span_id?: string;
  parent_span_id?: string;
  name?: string;
  type?: string;
  model?: string;
  status?: string;
  error_message?: string;
  duration_ms?: number;
  started_at?: string;
  completed_at?: string;
  input_tokens?: number;
  output_tokens?: number;
  cost_usd?: number;
  attributes?: Record<string, string>;
}

const TERMINAL_RUN_STATUSES = new Set(['COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED']);
const SPAN_ICONS: Record<string, typeof Brain> = { LLM: Brain, AGENT: Bot, TOOL: Wrench, FLOW: GitBranch, GROUP: Users };
const SPAN_COLORS: Record<string, string> = { LLM: '#7c3aed', AGENT: '#4f46e5', TOOL: '#d97706', FLOW: '#0891b2', GROUP: '#db2777' };

interface RawMessage {
  role?: string;
  content?: unknown;
  tool_calls?: { id?: string; function?: { name?: string; arguments?: string } }[];
  tool_call_id?: string;
  name?: string;
}

interface RawTool {
  type?: string;
  function?: { name?: string; description?: string; parameters?: unknown; strict?: boolean };
}

type BlockSection = 'system' | 'messages' | 'tools';

export default function ReplayWorkbench() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const canReplay = usePermission('experiment.replay');
  const [experiment, setExperiment] = useState<ReplayExperiment | null>(null);
  const [loadError, setLoadError] = useState('');
  const [draft, setDraft] = useState('');
  const [draftSaved, setDraftSaved] = useState(false);
  const [runDetails, setRunDetails] = useState<Record<string, ReplayRun>>({});
  const [runs, setRuns] = useState<ReplayRunSummary[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [diffsOn, setDiffsOn] = useState<Record<string, boolean>>({});
  const [note, setNote] = useState('');
  const [noteSaved, setNoteSaved] = useState(false);
  const [noteError, setNoteError] = useState('');
  const [replayError, setReplayError] = useState('');
  const [starting, setStarting] = useState(false);
  const [editorTab, setEditorTab] = useState<'blocks' | 'raw'>('blocks');
  // null = auto: land on System when the request has a system message
  const [blockSectionState, setBlockSectionState] = useState<BlockSection | null>(null);
  const [modifiedKeys, setModifiedKeys] = useState<Set<string>>(new Set());
  const [disabledTools, setDisabledTools] = useState<Set<number>>(new Set());
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  const [models, setModels] = useState<string[]>([]);
  const [model, setModel] = useState('');
  const [temperature, setTemperature] = useState('');
  const [reasoningEffort, setReasoningEffort] = useState('');
  const [sampleCount, setSampleCount] = useState(3);
  const [runLabel, setRunLabel] = useState('');
  const autosaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const isPlayground = experiment?.origin === 'BLANK';

  const loadExperiment = useCallback(async () => {
    try {
      const data = await api.replay.get(id);
      setExperiment(data);
      setRuns(data.runs ?? []);
      setNote(data.note ?? '');
      setDraft(data.draft_request ?? data.original_input ?? '');
      setModel(data.original_model ?? '');
      const params = parseParams(data.original_params);
      if (params.temperature != null) setTemperature(String(params.temperature));
      if (params.reasoning_effort) setReasoningEffort(params.reasoning_effort);
      setRunDetails({});
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'load failed');
    }
  }, [id]);

  useEffect(() => {
    loadExperiment();
  }, [loadExperiment]);

  useEffect(() => {
    api.gateway.routingModels()
      .then(res => setModels((res.data ?? []).map(m => m.id)))
      .catch(() => {});
  }, []);

  const pollRun = useCallback(async (expId: string, runId: string) => {
    let run: ReplayRun;
    do {
      run = await api.replay.getRun(expId, runId);
      setRunDetails(prev => ({ ...prev, [runId]: run }));
      if (!TERMINAL_RUN_STATUSES.has(run.status ?? '')) {
        await new Promise(resolve => setTimeout(resolve, 2000));
      }
    } while (!TERMINAL_RUN_STATUSES.has(run.status ?? ''));
  }, []);

  // resume polling for runs that were still running when the page loaded
  useEffect(() => {
    runs.forEach(run => {
      if (!TERMINAL_RUN_STATUSES.has(run.status ?? '') && !runDetails[run.id]) {
        pollRun(id, run.id).catch(() => {});
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runs]);

  const scheduleAutosave = useCallback((nextDraft: string) => {
    if (autosaveTimer.current) clearTimeout(autosaveTimer.current);
    autosaveTimer.current = setTimeout(() => {
      // update is a patch: sending only draft_request leaves the note untouched
      api.replay.update(id, { draft_request: nextDraft })
        .then(() => {
          setDraftSaved(true);
          setTimeout(() => setDraftSaved(false), 2000);
        })
        .catch(() => {});
    }, 800);
  }, [id]);

  const saveNote = async () => {
    try {
      await api.replay.update(id, { note });
      setNoteSaved(true);
      setNoteError('');
      setTimeout(() => setNoteSaved(false), 2000);
    } catch (e) {
      setNoteError(e instanceof Error ? e.message : 'save failed');
    }
  };

  const startReplay = async () => {
    if (starting || !requestValid) return;
    setStarting(true);
    setReplayError('');
    try {
      const response = await api.replay.createRun(id, {
        request: submittedDraft,
        model: model || undefined,
        temperature: temperature === '' ? undefined : Number(temperature),
        reasoning_effort: reasoningEffort || undefined,
        sample_count: sampleCount,
        label: runLabel || undefined,
      });
      // append locally instead of reloading the experiment: a reload would reset
      // the editor and the param bar to the original span's values
      setRuns(prev => [{
        id: response.run_id,
        status: response.status,
        label: runLabel || undefined,
        sample_count: sampleCount,
        created_at: new Date().toISOString(),
      }, ...prev]);
      setSelectedRunId(response.run_id);
      pollRun(id, response.run_id).catch(() => {});
    } catch (e) {
      setReplayError(e instanceof Error ? e.message : 'replay failed');
    } finally {
      setStarting(false);
    }
  };

  const cancelRun = async (runId: string) => {
    try {
      await api.replay.cancelRun(id, runId);
      pollRun(id, runId).catch(() => {});
    } catch (e) {
      console.error('cancel run failed', e);
    }
  };

  const deleteExperiment = async () => {
    if (!window.confirm('Delete this replay experiment and all its runs?')) return;
    try {
      await api.replay.delete(id);
      navigate(isPlayground ? '/experiments/playground' : '/experiments/replay');
    } catch (e) {
      console.error('delete failed', e);
    }
  };

  const requestRaw = useMemo(() => {
    if (!draft) return null;
    try {
      return JSON.parse(draft) as Record<string, unknown>;
    } catch {
      return null;
    }
  }, [draft]);

  const requestValid = requestRaw !== null && Array.isArray(requestRaw.messages);

  const traceSpans = useMemo(() => {
    if (!experiment?.trace_snapshot) return [];
    try {
      const parsed = JSON.parse(experiment.trace_snapshot) as { spans?: SnapshotSpan[] };
      return buildSpanTree((parsed.spans ?? []) as unknown as Span[]);
    } catch {
      return [];
    }
  }, [experiment]);

  const targetSpanId = experiment?.span_id;

  const commitDraft = (nextRaw: Record<string, unknown>, keys: string[]) => {
    const next = JSON.stringify(nextRaw, null, 2);
    setDraft(next);
    setModifiedKeys(prev => {
      const copy = new Set(prev);
      keys.forEach(key => copy.add(key));
      return copy;
    });
    scheduleAutosave(next);
  };

  // ── Block editing helpers ──

  const editMessageContent = (index: number, text: string) => {
    if (!requestRaw) return;
    const raw = structuredClone(requestRaw) as { messages?: RawMessage[] };
    const message = raw.messages?.[index];
    if (!message) return;
    if (typeof message.content === 'string') {
      message.content = text;
    } else if (Array.isArray(message.content)) {
      const textPart = message.content.find((part): part is { type: string; text?: string } =>
        typeof part === 'object' && part !== null && part.type === 'text');
      if (textPart) textPart.text = text;
      else message.content = text;
    } else {
      message.content = text;
    }
    commitDraft(raw as Record<string, unknown>, [`msg-${index}-content`]);
  };

  const editToolCallArgs = (messageIndex: number, callIndex: number, argsJson: string) => {
    if (!requestRaw) return;
    const raw = structuredClone(requestRaw) as { messages?: RawMessage[] };
    const call = raw.messages?.[messageIndex]?.tool_calls?.[callIndex];
    if (!call?.function) return;
    try {
      JSON.parse(argsJson); // validate before commit
    } catch {
      setValidationErrors(prev => ({ ...prev, [`msg-${messageIndex}-arg-${callIndex}`]: 'invalid JSON' }));
      return;
    }
    call.function.arguments = argsJson;
    setValidationErrors(prev => {
      const copy = { ...prev };
      delete copy[`msg-${messageIndex}-arg-${callIndex}`];
      return copy;
    });
    commitDraft(raw as Record<string, unknown>, [`msg-${messageIndex}-arg-${callIndex}`]);
  };

  const editToolDescription = (toolIndex: number, description: string) => {
    if (!requestRaw) return;
    const raw = structuredClone(requestRaw) as { tools?: RawTool[] };
    const fn = raw.tools?.[toolIndex]?.function;
    if (!fn) return;
    fn.description = description;
    commitDraft(raw as Record<string, unknown>, [`tool-${toolIndex}-description`]);
  };

  const editToolParameters = (toolIndex: number, parameters: unknown) => {
    if (!requestRaw) return;
    const raw = structuredClone(requestRaw) as { tools?: RawTool[] };
    const fn = raw.tools?.[toolIndex]?.function;
    if (!fn) return;
    fn.parameters = parameters;
    setValidationErrors(prev => {
      const copy = { ...prev };
      delete copy[`tool-${toolIndex}-parameters`];
      return copy;
    });
    commitDraft(raw as Record<string, unknown>, [`tool-${toolIndex}-parameters`]);
  };

  const toggleTool = (toolIndex: number) => {
    setDisabledTools(prev => {
      const copy = new Set(prev);
      if (copy.has(toolIndex)) copy.delete(toolIndex);
      else copy.add(toolIndex);
      return copy;
    });
  };

  const editableMessages = useMemo(() => {
    if (!requestRaw || !Array.isArray(requestRaw.messages)) return [];
    return requestRaw.messages as RawMessage[];
  }, [requestRaw]);

  const editableTools = useMemo(() => {
    if (!requestRaw || !Array.isArray(requestRaw.tools)) return [];
    return requestRaw.tools as RawTool[];
  }, [requestRaw]);

  // ── Block sections (System / Messages / Tools) ──
  // entries keep the original message index so edit handlers stay stable across sections
  const messageEntries = useMemo(() => editableMessages.map((message, index) => ({ message, index })), [editableMessages]);
  const systemEntries = useMemo(() => messageEntries.filter(entry => entry.message.role === 'system'), [messageEntries]);
  const turnEntries = useMemo(() => messageEntries.filter(entry => entry.message.role !== 'system'), [messageEntries]);
  const blockSection: BlockSection = blockSectionState ?? (systemEntries.length > 0 ? 'system' : 'messages');
  const sectionModified = useMemo(() => {
    const keys = [...modifiedKeys];
    const msgModified = (index: number) => keys.some(key => key.startsWith(`msg-${index}-`));
    return {
      system: systemEntries.some(entry => msgModified(entry.index)),
      messages: turnEntries.some(entry => msgModified(entry.index)),
      tools: keys.some(key => key.startsWith('tool-')),
    } satisfies Record<BlockSection, boolean>;
  }, [modifiedKeys, systemEntries, turnEntries]);

  const submittedDraft = useMemo(() => {
    // tools disabled via checkbox are removed only at submit time
    if (!requestRaw) return draft;
    const raw = structuredClone(requestRaw) as Record<string, unknown>;
    if (Array.isArray(raw.tools) && disabledTools.size > 0) {
      raw.tools = (raw.tools as unknown[]).filter((_, index) => !disabledTools.has(index));
    }
    return JSON.stringify(raw);
  }, [requestRaw, disabledTools, draft]);

  const messageText = (message: RawMessage): string => {
    if (typeof message.content === 'string') return message.content;
    if (Array.isArray(message.content)) {
      return message.content
        .filter((part): part is { type: string; text?: string } => typeof part === 'object' && part !== null)
        .map(part => (part.type === 'text' && typeof part.text === 'string' ? part.text : ''))
        .join('');
    }
    return '';
  };

  const hasNonTextParts = (message: RawMessage): boolean => {
    if (!Array.isArray(message.content)) return false;
    return message.content.some(part => typeof part === 'object' && part !== null && (part as { type?: string }).type !== 'text');
  };

  const toolName = (tool: RawTool): string => tool.function?.name ?? 'tool';

  const originalOutput = useMemo(
    () => extractAssistantContent(experiment?.original_output),
    [experiment],
  );

  // index is the message's position in the full messages array, shown in the header
  // so users can correlate a card with the raw JSON regardless of the active section
  const renderMessageBlock = (message: RawMessage, index: number) => (
    <div key={`msg-${index}`} className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--color-border)' }}>
      <div className="px-3 py-1.5 text-xs font-medium flex items-center gap-2"
        style={{ background: message.role === 'user' ? 'rgba(99,102,241,0.1)' : message.role === 'tool' ? 'rgba(217,119,6,0.1)' : 'rgba(34,197,94,0.1)', color: 'var(--color-text-secondary)' }}>
        {message.role || 'message'} {index}
        {modifiedKeys.has(`msg-${index}-content`) && <ModifiedBadge />}
        {message.tool_call_id && <span className="font-mono text-[10px] opacity-70">{message.tool_call_id}</span>}
      </div>
      <div className="p-2 space-y-2">
        <textarea
          className="w-full rounded-md p-2 text-xs font-mono resize-y"
          style={{ background: 'var(--color-bg-tertiary)', minHeight: '48px' }}
          value={messageText(message)}
          placeholder={hasNonTextParts(message) ? '(image/file parts shown as placeholders)' : ''}
          onChange={event => editMessageContent(index, event.target.value)}
        />
        {hasNonTextParts(message) && (
          <div className="text-[10px] px-1" style={{ color: 'var(--color-text-secondary)' }}>
            Contains image/file parts — passed through unchanged, only text is editable.
          </div>
        )}
        {(message.tool_calls ?? []).map((call, callIndex) => (
          <div key={`call-${callIndex}`} className="rounded-md overflow-hidden" style={{ border: '1px solid rgba(217,119,6,0.28)' }}>
            <div className="px-2 py-1 text-[11px] font-medium flex items-center gap-1" style={{ background: 'rgba(217,119,6,0.1)', color: '#d97706' }}>
              <Wrench size={10} /> {call.function?.name}
              {modifiedKeys.has(`msg-${index}-arg-${callIndex}`) && <ModifiedBadge />}
            </div>
            <JsonArgsField
              value={call.function?.arguments ?? ''}
              error={validationErrors[`msg-${index}-arg-${callIndex}`]}
              onChange={json => editToolCallArgs(index, callIndex, json)}
            />
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <div className="p-4 max-w-[1600px] mx-auto">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3 min-w-0">
          <button
            className="flex items-center gap-1 px-2.5 py-1.5 rounded-md text-xs"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
            onClick={() => navigate(isPlayground ? '/experiments/playground' : '/experiments/replay')}>
            <ArrowLeft size={13} /> Back
          </button>
          <div className="min-w-0">
            {isPlayground ? (
              <>
                <div className="text-sm font-semibold truncate flex items-center gap-2">
                  <FlaskConical size={15} style={{ color: 'var(--color-primary)' }} />
                  Playground
                </div>
                <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  Write any request from scratch and compare sample responses — no trace needed.
                  Saved under Replay Debug experiments · created {formatRelativeTime(experiment?.created_at)}.
                </div>
              </>
            ) : (
              <>
                <div className="text-sm font-semibold truncate flex items-center gap-2">
                  <FlaskConical size={15} style={{ color: 'var(--color-primary)' }} />
                  Replay: {experiment?.span_name || '...'}
                  {experiment?.original_model && (
                    <span className="font-mono text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {experiment.original_model}
                    </span>
                  )}
                </div>
                <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {experiment?.agent_name || experiment?.agent_id || 'agent'} · {formatRelativeTime(experiment?.created_at)}
                  {experiment?.trace_id && (
                    <Link className="ml-2 inline-flex items-center gap-0.5 underline" style={{ color: 'var(--color-primary)' }} to={`/traces/${experiment.trace_id}`}>
                      <ExternalLink size={10} /> original trace
                    </Link>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {experiment && (
            <button
              className="px-2.5 py-1.5 rounded-md text-xs"
              style={{ background: 'var(--color-bg-tertiary)', color: '#dc2626' }}
              onClick={deleteExperiment}>
              Delete
            </button>
          )}
        </div>
      </div>

      {loadError && (
        <div className="mb-3 p-3 rounded-md text-sm" style={{ background: '#fef2f2', color: '#b91c1c' }}>
          {loadError}
        </div>
      )}

      {experiment && (
        <div className="grid grid-cols-12 gap-3" style={{ gridTemplateColumns: isPlayground ? '1fr 1fr' : '280px 1fr 1fr' }}>
          {!isPlayground && (
            <>
              {/* ── Left: trace context tree ── */}
              <div className="rounded-lg border overflow-hidden min-w-0" style={{ borderColor: 'var(--color-border)' }}>
                <div className="px-3 py-2 text-xs font-medium border-b" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
                  Trace context
                </div>
                <div className="p-2 max-h-[calc(100vh-220px)] overflow-auto">
                  {traceSpans.length === 0 && (
                    <div className="text-xs p-2" style={{ color: 'var(--color-text-secondary)' }}>No trace snapshot</div>
                  )}
                  {traceSpans.map(node => <TraceNode key={node.spanId} node={node} targetSpanId={targetSpanId} />)}
                  <div className="mt-2 text-[10px] px-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Dimmed spans happened after the replayed call.
                  </div>
                </div>
              </div>
            </>
          )}

          {/* ── Middle: request editor ── */}
          <div className="rounded-lg border overflow-hidden min-w-0" style={{ borderColor: 'var(--color-border)' }}>
            <div className="px-3 py-2 border-b flex items-center justify-between" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <div className="flex items-center gap-1">
                {(['blocks', 'raw'] as const).map(tab => (
                  <button
                    key={tab}
                    className="px-2.5 py-1 rounded-md text-xs font-medium capitalize"
                    style={editorTab === tab ? { background: 'var(--color-primary)', color: '#fff' } : { color: 'var(--color-text-secondary)' }}
                    onClick={() => setEditorTab(tab)}>
                    {tab === 'blocks' ? 'Blocks' : 'Raw JSON'}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-2 text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                {modifiedKeys.size > 0 && <span>{modifiedKeys.size} modified</span>}
                {draftSaved && <span className="flex items-center gap-1" style={{ color: '#16a34a' }}><Check size={10} /> saved</span>}
                {!requestValid && <span style={{ color: '#dc2626' }}>invalid request JSON</span>}
              </div>
            </div>

            <div className="p-3 max-h-[calc(100vh-340px)] overflow-auto space-y-3">
              {editorTab === 'raw' ? (
                <RawEditor draft={draft} onCommit={next => {
                  try {
                    const parsed = JSON.parse(next) as Record<string, unknown>;
                    commitDraft(parsed, ['raw']);
                    setValidationErrors(prev => {
                      const copy = { ...prev };
                      delete copy['raw'];
                      return copy;
                    });
                  } catch {
                    setValidationErrors(prev => ({ ...prev, raw: 'invalid JSON' }));
                  }
                }} error={validationErrors.raw} />
              ) : (
                <>
                  <div className="flex items-center gap-1">
                    {([
                      ['system', `System (${systemEntries.length})`],
                      ['messages', `Messages (${turnEntries.length})`],
                      ['tools', `Tools (${editableTools.length})`],
                    ] as const).map(([key, label]) => (
                      <button
                        key={key}
                        className="flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium"
                        style={blockSection === key
                          ? { background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }
                          : { color: 'var(--color-text-secondary)' }}
                        onClick={() => setBlockSectionState(key)}>
                        {label}
                        {sectionModified[key] && <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#7c3aed' }} />}
                      </button>
                    ))}
                  </div>

                  {blockSection === 'system' && (
                    <>
                      {systemEntries.length === 0 && (
                        <div className="text-xs p-2" style={{ color: 'var(--color-text-secondary)' }}>No system message in this request</div>
                      )}
                      {systemEntries.map(({ message, index }) => renderMessageBlock(message, index))}
                    </>
                  )}

                  {blockSection === 'messages' && (
                    <>
                      {turnEntries.length === 0 && (
                        <div className="text-xs p-2" style={{ color: 'var(--color-text-secondary)' }}>No conversation messages in this request</div>
                      )}
                      {turnEntries.map(({ message, index }) => renderMessageBlock(message, index))}
                    </>
                  )}

                  {blockSection === 'tools' && editableTools.length === 0 && (
                    <div className="text-xs p-2" style={{ color: 'var(--color-text-secondary)' }}>No tool definitions in this request</div>
                  )}
                  {blockSection === 'tools' && editableTools.map((tool, index) => (
                    <div key={`tool-${index}`} className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--color-border)', opacity: disabledTools.has(index) ? 0.55 : 1 }}>
                      <div className="px-3 py-1.5 text-xs font-medium flex items-center gap-2"
                        style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                        <Wrench size={11} /> {toolName(tool)}
                        {modifiedKeys.has(`tool-${index}-description`) || modifiedKeys.has(`tool-${index}-parameters`) ? <ModifiedBadge /> : null}
                        <label className="ml-auto flex items-center gap-1 text-[10px] cursor-pointer">
                          <input type="checkbox" checked={!disabledTools.has(index)} onChange={() => toggleTool(index)} />
                          enabled
                        </label>
                      </div>
                      <div className="p-2 space-y-2">
                        <div>
                          <div className="text-[10px] mb-1" style={{ color: 'var(--color-text-secondary)' }}>description</div>
                          <textarea
                            className="w-full rounded-md p-2 text-xs resize-y"
                            style={{ background: 'var(--color-bg-tertiary)', minHeight: '44px' }}
                            value={tool.function?.description ?? ''}
                            onChange={event => editToolDescription(index, event.target.value)}
                          />
                        </div>
                        <div>
                          <div className="text-[10px] mb-1" style={{ color: 'var(--color-text-secondary)' }}>parameters (JSON)</div>
                          <JsonObjectField
                            value={tool.function?.parameters}
                            error={validationErrors[`tool-${index}-parameters`]}
                            onChange={value => editToolParameters(index, value)}
                          />
                        </div>
                      </div>
                    </div>
                  ))}

                  <div className="text-[10px] px-1 pt-1 leading-relaxed" style={{ color: 'var(--color-text-secondary)' }}>
                    Editing map: system prompt → messages[role=system] · user/history inputs → messages[role=user] ·
                    assistant replies/summaries → messages[role=assistant] · tool descriptions & schemas → tools[] ·
                    skill list text → use_skill tool description · loaded skill body → role=tool messages · sub-agent
                    description → the sub-agent tool's description.
                  </div>
                </>
              )}
            </div>

            {/* param bar */}
            <div className="p-3 border-t space-y-2" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <div className="flex flex-wrap items-end gap-3">
                <label className="text-[10px]">
                  Model
                  <select className="block mt-0.5 rounded-md px-2 py-1 text-xs font-mono max-w-[240px]"
                    style={{ background: 'var(--color-bg-tertiary)' }}
                    value={model}
                    onChange={event => setModel(event.target.value)}>
                    <option value="">(provider default)</option>
                    {models.map(m => <option key={m} value={m}>{m}</option>)}
                  </select>
                </label>
                <label className="text-[10px]">
                  Temperature
                  <input
                    className="block mt-0.5 rounded-md px-2 py-1 text-xs w-20"
                    style={{ background: 'var(--color-bg-tertiary)' }}
                    type="number" step="0.1" min="0" max="2"
                    value={temperature}
                    onChange={event => setTemperature(event.target.value)}
                  />
                </label>
                <label className="text-[10px]">
                  Reasoning effort
                  <select className="block mt-0.5 rounded-md px-2 py-1 text-xs"
                    style={{ background: 'var(--color-bg-tertiary)' }}
                    value={reasoningEffort}
                    onChange={event => setReasoningEffort(event.target.value)}>
                    <option value="">(unknown — historical spans don't record it)</option>
                    <option value="none">none</option>
                    <option value="low">low</option>
                    <option value="high">high</option>
                    <option value="max">max</option>
                  </select>
                </label>
                <label className="text-[10px]">
                  Samples
                  <select className="block mt-0.5 rounded-md px-2 py-1 text-xs"
                    style={{ background: 'var(--color-bg-tertiary)' }}
                    value={sampleCount}
                    onChange={event => setSampleCount(Number(event.target.value))}>
                    {[1, 2, 3, 4, 5].map(n => <option key={n} value={n}>{n}</option>)}
                  </select>
                </label>
                <label className="text-[10px]">
                  Run label
                  <input
                    className="block mt-0.5 rounded-md px-2 py-1 text-xs w-44"
                    style={{ background: 'var(--color-bg-tertiary)' }}
                    value={runLabel}
                    placeholder="e.g. shortened tool description"
                    onChange={event => setRunLabel(event.target.value)}
                  />
                </label>
                <button
                  className="flex items-center gap-1.5 px-4 py-1.5 rounded-md text-xs font-medium disabled:opacity-50"
                  style={{ background: 'var(--color-primary)', color: '#fff' }}
                  disabled={starting || !requestValid || !canReplay}
                  title={canReplay ? '' : 'Missing experiment.replay permission'}
                  onClick={startReplay}>
                  {starting ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />} Replay
                </button>
              </div>
              {replayError && <div className="text-xs" style={{ color: '#dc2626' }}>{replayError}</div>}
            </div>
          </div>

          {/* ── Right: results compare ── */}
          <div className="rounded-lg border overflow-hidden min-w-0" style={{ borderColor: 'var(--color-border)' }}>
            <div className="px-3 py-2 text-xs font-medium border-b" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Results
            </div>
            <div className="p-3 max-h-[calc(100vh-220px)] overflow-auto space-y-3">
              {originalOutput && (
                <OutputCard
                  title="Original (pinned)"
                  output={originalOutput}
                  meta={`${experiment.original_model ?? 'model'} · ${formatTokenPair(experiment.original_usage?.input_tokens, experiment.original_usage?.output_tokens)} · ${formatCostUsd(experiment.original_usage?.cost_usd)} · ${formatDuration(experiment.original_usage?.duration_ms)}`}
                  accent="rgba(100,116,139,0.3)"
                />
              )}

              {runs.length === 0 && (
                <div className="text-xs p-2" style={{ color: 'var(--color-text-secondary)' }}>
                  No runs yet — edit the request and click Replay. Tip: run a zero-modification baseline first to see the request's own variance.
                </div>
              )}

              {runs.map(run => {
                const detail = runDetails[run.id];
                const expanded = selectedRunId === run.id;
                const running = !TERMINAL_RUN_STATUSES.has(run.status ?? '');
                return (
                  <div key={run.id} className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--color-border)' }}>
                    <button
                      className="w-full px-3 py-2 flex items-center gap-2 text-left"
                      style={{ background: 'var(--color-bg-secondary)' }}
                      onClick={() => {
                        setSelectedRunId(expanded ? null : run.id);
                        if (!detail) pollRun(id, run.id).catch(() => {});
                      }}>
                      {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                      <span className="text-xs font-medium truncate">{run.label || `Run ${run.created_at ? formatRelativeTime(run.created_at) : run.id.slice(0, 8)}`}</span>
                      <RunStatusBadge status={run.status ?? ''} />
                      {running && <Loader2 size={12} className="animate-spin" style={{ color: 'var(--color-primary)' }} />}
                      {running && (
                        <span
                          className="ml-auto text-[10px] px-1.5 py-0.5 rounded cursor-pointer"
                          style={{ background: '#fef2cd', color: '#946800' }}
                          onClick={event => { event.stopPropagation(); cancelRun(run.id); }}>
                          Cancel
                        </span>
                      )}
                    </button>
                    {expanded && detail && (
                      <div className="p-2 space-y-2">
                        <div className="flex items-center gap-2 text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                          <span className="font-mono">{detail.model || 'default model'}</span>
                          {detail.temperature != null && <span>temp {detail.temperature}</span>}
                          {detail.reasoning_effort && <span>effort {detail.reasoning_effort}</span>}
                          <span>{detail.sample_count} samples</span>
                        </div>
                        {(detail.samples ?? []).map(sample => (
                          <SampleCard
                            key={sample.index}
                            sample={sample}
                            diffOn={diffsOn[`${run.id}-${sample.index}`] ?? false}
                            onToggleDiff={() => setDiffsOn(prev => ({ ...prev, [`${run.id}-${sample.index}`]: !prev[`${run.id}-${sample.index}`] }))}
                            originalContent={originalOutput?.content ?? ''}
                          />
                        ))}
                        {!detail.samples?.length && <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No samples yet</div>}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            {/* note */}
            <div className="p-3 border-t space-y-2" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <div className="text-[10px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>Conclusion note</div>
              <textarea
                className="w-full rounded-md p-2 text-xs resize-y"
                style={{ background: 'var(--color-bg-tertiary)', minHeight: '56px' }}
                value={note}
                placeholder="What did this variant change? Did it help?"
                onChange={event => setNote(event.target.value)}
              />
              <div className="flex items-center gap-2">
                <button
                  className="px-3 py-1 rounded-md text-xs font-medium"
                  style={{ background: 'var(--color-primary)', color: '#fff' }}
                  onClick={saveNote}>
                  Save note
                </button>
                {noteSaved && <span className="text-xs flex items-center gap-1" style={{ color: '#16a34a' }}><Check size={12} /> Saved</span>}
                {noteError && <span className="text-xs" style={{ color: '#dc2626' }}>{noteError}</span>}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Sub components ──

function TraceNode({ node, targetSpanId }: { node: ReturnType<typeof buildSpanTree>[number]; targetSpanId?: string }) {
  const Icon = SPAN_ICONS[node.type ?? ''] ?? Bot;
  const color = SPAN_COLORS[node.type ?? ''] ?? '#64748b';
  const isTarget = node.spanId === targetSpanId;
  const [open, setOpen] = useState(true);
  return (
    <div>
      <div
        className="flex items-center gap-1.5 px-1.5 py-1 rounded text-[11px] cursor-pointer hover:opacity-90"
        style={{
          paddingLeft: `${8 + node.depth * 14}px`,
          background: isTarget ? 'rgba(124,58,237,0.12)' : undefined,
          outline: isTarget ? '1px solid rgba(124,58,237,0.4)' : undefined,
        }}
        onClick={() => setOpen(prev => !prev)}>
        {node.children.length > 0 ? (open ? <ChevronDown size={10} /> : <ChevronRight size={10} />) : <span className="w-[10px]" />}
        <Icon size={11} style={{ color }} />
        <span className="truncate" style={{ fontWeight: isTarget ? 600 : 400 }}>{node.name || node.type}</span>
        {node.status === 'ERROR' && <span className="text-[9px]" style={{ color: '#dc2626' }}>!</span>}
      </div>
      {open && node.children.map(child => <TraceNode key={child.spanId} node={child} targetSpanId={targetSpanId} />)}
    </div>
  );
}

function ModifiedBadge() {
  return <span className="text-[9px] px-1 py-0.5 rounded" style={{ background: 'rgba(124,58,237,0.15)', color: '#7c3aed' }}>modified</span>;
}

function RunStatusBadge({ status }: { status: string }) {
  const color = status === 'COMPLETED' ? '#16a34a' : status === 'ERROR' ? '#dc2626' : status === 'CANCELLED' ? '#64748b' : status === 'PARTIAL' ? '#d97706' : '#7c3aed';
  return <span className="text-[9px] px-1.5 py-0.5 rounded font-medium" style={{ background: `${color}1a`, color }}>{status}</span>;
}

function RawEditor({ draft, onCommit, error }: { draft: string; onCommit: (json: string) => void; error?: string }) {
  const [text, setText] = useState(draft);
  useEffect(() => setText(draft), [draft]);
  return (
    <div>
      <textarea
        className="w-full rounded-md p-2 text-xs font-mono resize-y"
        style={{ background: 'var(--color-bg-tertiary)', minHeight: '320px' }}
        value={text}
        onChange={event => setText(event.target.value)}
        onBlur={() => onCommit(text)}
      />
      {error && <div className="text-[10px] mt-1" style={{ color: '#dc2626' }}>{error}</div>}
      <div className="text-[10px] mt-1" style={{ color: 'var(--color-text-secondary)' }}>Edits apply on blur. Full request JSON — model/temperature live in the param bar below.</div>
    </div>
  );
}

function JsonArgsField({ value, onChange, error }: { value: string; onChange: (json: string) => void; error?: string }) {
  const [text, setText] = useState(value);
  const [localError, setLocalError] = useState('');
  useEffect(() => setText(value), [value]);
  const commit = () => {
    try {
      JSON.parse(text);
      setLocalError('');
      onChange(text);
    } catch {
      setLocalError('invalid JSON');
    }
  };
  return (
    <div>
      <textarea
        className="w-full rounded-md p-2 text-[11px] font-mono resize-y"
        style={{ background: 'var(--color-bg-tertiary)', minHeight: '56px' }}
        value={text}
        onChange={event => setText(event.target.value)}
        onBlur={commit}
      />
      {(localError || error) && <div className="text-[10px] mt-0.5 px-1" style={{ color: '#dc2626' }}>{localError || error}</div>}
    </div>
  );
}

function JsonObjectField({ value, onChange, error }: { value: unknown; onChange: (value: unknown) => void; error?: string }) {
  const [text, setText] = useState(JSON.stringify(value, null, 2));
  const [localError, setLocalError] = useState('');
  useEffect(() => setText(JSON.stringify(value, null, 2)), [value]);
  const commit = () => {
    try {
      onChange(JSON.parse(text));
      setLocalError('');
    } catch {
      setLocalError('invalid JSON');
    }
  };
  return (
    <div>
      <textarea
        className="w-full rounded-md p-2 text-[11px] font-mono resize-y"
        style={{ background: 'var(--color-bg-tertiary)', minHeight: '80px' }}
        value={text}
        onChange={event => setText(event.target.value)}
        onBlur={commit}
      />
      {(localError || error) && <div className="text-[10px] mt-0.5 px-1" style={{ color: '#dc2626' }}>{localError || error}</div>}
    </div>
  );
}

function SampleCard({ sample, diffOn, onToggleDiff, originalContent }: {
  sample: ReplaySample;
  diffOn: boolean;
  onToggleDiff: () => void;
  originalContent: string;
}) {
  const output = extractAssistantContent(sample.output);
  const running = sample.status === 'RUNNING';
  return (
    <div className="rounded-lg overflow-hidden" style={{ border: '1px solid var(--color-border)' }}>
      <div className="px-2.5 py-1.5 flex items-center gap-2" style={{ background: 'var(--color-bg-secondary)' }}>
        <span className="text-[10px] font-medium" style={{ color: 'var(--color-text-secondary)' }}>sample {sample.index + 1}</span>
        <RunStatusBadge status={sample.status} />
        <span className="text-[10px] font-mono" style={{ color: 'var(--color-text-secondary)' }}>
          {formatTokenPair(sample.input_tokens, sample.output_tokens)} · {formatCostUsd(sample.cost_usd)} · {formatDuration(sample.duration_ms)}
        </span>
        {output?.content && (
          <button
            className="ml-auto text-[10px] px-1.5 py-0.5 rounded"
            style={{ background: diffOn ? 'rgba(124,58,237,0.15)' : 'var(--color-bg-tertiary)', color: diffOn ? '#7c3aed' : 'var(--color-text-secondary)' }}
            onClick={onToggleDiff}>
            Diff vs original
          </button>
        )}
        {sample.replay_trace_id && (
          <Link className="text-[10px] underline" style={{ color: 'var(--color-primary)' }} to={`/traces/${sample.replay_trace_id}`}>trace</Link>
        )}
      </div>
      <div className="p-2">
        {running && (
          <div className="flex items-center gap-2 text-xs py-2" style={{ color: 'var(--color-text-secondary)' }}>
            <Loader2 size={13} className="animate-spin" /> Running...
          </div>
        )}
        {sample.status === 'ERROR' && sample.error_message && (
          <div className="text-[11px] rounded p-2" style={{ background: '#fef2f2', color: '#b91c1c' }}>{sample.error_message}</div>
        )}
        {sample.status === 'CANCELLED' && <div className="text-xs py-1" style={{ color: 'var(--color-text-secondary)' }}>Cancelled</div>}
        {output && !running && (
          diffOn && originalContent ? (
            <DiffView original={originalContent} current={output.content} />
          ) : (
            <div className="space-y-1.5">
              {output.reasoning && <Collapsible label="Reasoning" text={output.reasoning} />}
              {output.content && <pre className="text-xs whitespace-pre-wrap">{output.content}</pre>}
              {output.tool_calls?.map((call, index) => (
                <div key={`${call.id}-${index}`} className="rounded-md" style={{ border: '1px solid rgba(217,119,6,0.28)' }}>
                  <div className="px-2 py-1 text-[10px] font-medium" style={{ background: 'rgba(217,119,6,0.1)', color: '#d97706' }}>{call.function.name}</div>
                  <pre className="px-2 py-1.5 text-[10px] whitespace-pre-wrap overflow-auto" style={{ background: 'var(--color-bg-tertiary)' }}>{call.function.arguments}</pre>
                </div>
              ))}
            </div>
          )
        )}
      </div>
    </div>
  );
}

function Collapsible({ label, text }: { label: string; text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-md overflow-hidden" style={{ border: '1px solid var(--color-border)' }}>
      <button className="w-full px-2 py-1 text-[10px] font-medium text-left" style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }} onClick={() => setOpen(prev => !prev)}>
        {open ? '▾' : '▸'} {label}
      </button>
      {open && <pre className="px-2 py-1.5 text-[10px] whitespace-pre-wrap max-h-40 overflow-auto" style={{ background: 'var(--color-bg-tertiary)' }}>{text}</pre>}
    </div>
  );
}

function OutputCard({ title, output, meta, accent }: { title: string; output: ExtractedAssistantOutput; meta: string; accent: string }) {
  return (
    <div className="rounded-lg overflow-hidden" style={{ border: `1px solid ${accent}` }}>
      <div className="px-3 py-1.5 text-xs font-medium flex items-center justify-between" style={{ background: `${accent}22`, color: 'var(--color-text-secondary)' }}>
        <span>{title}</span>
        <span className="text-[10px] font-mono">{meta}</span>
      </div>
      <div className="px-3 py-2 space-y-1.5">
        {output.reasoning && <Collapsible label="Reasoning" text={output.reasoning} />}
        {output.content && <pre className="text-xs whitespace-pre-wrap">{output.content}</pre>}
        {output.tool_calls?.map((call, index) => (
          <div key={`${call.id}-${index}`} className="rounded-md" style={{ border: '1px solid rgba(217,119,6,0.28)' }}>
            <div className="px-2 py-1 text-[10px] font-medium" style={{ background: 'rgba(217,119,6,0.1)', color: '#d97706' }}>{call.function.name}</div>
            <pre className="px-2 py-1.5 text-[10px] whitespace-pre-wrap overflow-auto" style={{ background: 'var(--color-bg-tertiary)' }}>{call.function.arguments}</pre>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Helpers ──

function parseParams(json?: string): { temperature?: number; reasoning_effort?: string } {
  if (!json) return {};
  try {
    const parsed = JSON.parse(json) as { temperature?: number; reasoning_effort?: string };
    return parsed ?? {};
  } catch {
    return {};
  }
}

// Minimal line-based LCS diff for comparing response content.
function lineDiff(original: string, current: string): { type: 'same' | 'del' | 'add'; text: string }[] {
  const a = original.split('\n');
  const b = current.split('\n');
  const n = a.length;
  const m = b.length;
  if (n * m > 250_000) {
    // fall back to a coarse side-by-side marker
    return b.map(line => ({ type: 'add' as const, text: line }));
  }
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const result: { type: 'same' | 'del' | 'add'; text: string }[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      result.push({ type: 'same', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      result.push({ type: 'del', text: a[i] });
      i++;
    } else {
      result.push({ type: 'add', text: b[j] });
      j++;
    }
  }
  while (i < n) result.push({ type: 'del', text: a[i++] });
  while (j < m) result.push({ type: 'add', text: b[j++] });
  return result;
}

function DiffView({ original, current }: { original: string; current: string }) {
  const lines = useMemo(() => lineDiff(original, current), [original, current]);
  return (
    <pre className="text-xs whitespace-pre-wrap max-h-72 overflow-auto">
      {lines.map((line, index) => (
        <div
          key={index}
          style={{
            background: line.type === 'del' ? 'rgba(220,38,38,0.12)' : line.type === 'add' ? 'rgba(22,163,74,0.12)' : undefined,
            color: line.type === 'del' ? '#b91c1c' : line.type === 'add' ? '#15803d' : undefined,
            textDecoration: line.type === 'del' ? 'line-through' : undefined,
          }}>
          {line.type === 'del' ? '-' : line.type === 'add' ? '+' : ' '}{line.text}
        </div>
      ))}
    </pre>
  );
}
