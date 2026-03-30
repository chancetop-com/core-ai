import { useState, useCallback, useRef } from 'react';
import type { A2AClient } from '../client.js';
import type { A2AEvent, HistoryItem, ApprovalRequest, StreamingState } from '../types.js';

let nextId = 1;
const genId = () => `item-${nextId++}`;

export interface StreamHookResult {
  streamingState: StreamingState;
  pendingItems: HistoryItem[];
  currentTaskId: string | null;
  pendingApproval: ApprovalRequest | null;
  thought: string;
  elapsedMs: number;
  lastAssistantText: string;
  totalTokens: number;
  inputTokens: number;
  outputTokens: number;
  turnCount: number;
  submitMessage: (text: string) => Promise<void>;
  cancelTurn: () => void;
  approveToolCall: (decision: 'approve' | 'deny') => void;
}

export function useStream(client: A2AClient, addToHistory: (items: HistoryItem[]) => void, skipPermissions: boolean): StreamHookResult {
  const [streamingState, setStreamingState] = useState<StreamingState>('idle');
  const [pendingItems, setPendingItems] = useState<HistoryItem[]>([]);
  const [currentTaskId, setCurrentTaskId] = useState<string | null>(null);
  const [pendingApproval, setPendingApproval] = useState<ApprovalRequest | null>(null);
  const [thought, setThought] = useState('');
  const [elapsedMs, setElapsedMs] = useState(0);
  const [lastAssistantText, setLastAssistantText] = useState('');
  const [totalTokens, setTotalTokens] = useState(0);
  const [inputTokens, setInputTokens] = useState(0);
  const [outputTokens, setOutputTokens] = useState(0);
  const [turnCount, setTurnCount] = useState(0);

  const textRef = useRef('');
  const thoughtRef = useRef('');
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startTimeRef = useRef(0);

  const startTimer = useCallback(() => {
    startTimeRef.current = Date.now();
    timerRef.current = setInterval(() => {
      setElapsedMs(Date.now() - startTimeRef.current);
    }, 100);
  }, []);

  const stopTimer = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const submitMessage = useCallback(async (text: string) => {
    textRef.current = '';
    thoughtRef.current = '';
    setStreamingState('responding');
    setPendingItems([]);
    setPendingApproval(null);
    setThought('');
    setElapsedMs(0);
    startTimer();

    const collectedItems: HistoryItem[] = [];

    try {
      await client.sendMessageStream(text, async (event: A2AEvent) => {
        if (event.taskId) setCurrentTaskId(event.taskId);

        if (event.type === 'status' && event.status) {
          const state = event.status.state;
          const meta = event.metadata;

          if (state === 'working') {
            if (meta?.event === 'reasoning') {
              thoughtRef.current += meta.chunk || '';
              setThought(thoughtRef.current);
            } else if (meta?.event === 'tool_start') {
              const item: HistoryItem = {
                id: genId(), type: 'tool_start', content: '',
                toolName: meta.tool, toolArgs: meta.arguments, callId: meta.call_id,
              };
              collectedItems.push(item);
              setPendingItems(prev => [...prev, item]);
            } else if (meta?.event === 'tool_result') {
              const item: HistoryItem = {
                id: genId(), type: 'tool_result', content: meta.result || '',
                toolName: meta.tool, callId: meta.call_id, resultStatus: meta.result_status,
              };
              collectedItems.push(item);
              setPendingItems(prev => [...prev, item]);
            } else if (event.status.message) {
              const chunk = event.status.message.parts
                ?.filter(p => p.type === 'text')
                .map(p => p.text)
                .join('') || '';
              if (chunk) {
                textRef.current += chunk;
                setPendingItems(prev => {
                  const existing = prev.find(i => i.type === 'assistant');
                  if (existing) {
                    return prev.map(i => i === existing ? { ...i, content: textRef.current, isStreaming: true } : i);
                  }
                  const item: HistoryItem = { id: genId(), type: 'assistant', content: textRef.current, isStreaming: true };
                  return [...prev, item];
                });
              }
            }
          }

          if (state === 'input-required' && meta) {
            const approval: ApprovalRequest = {
              taskId: event.taskId, callId: meta.call_id, tool: meta.tool, args: meta.arguments || '',
            };
            setPendingApproval(approval);
            setStreamingState('waiting_approval');

            if (skipPermissions) {
              await client.approve(event.taskId, 'approve', meta.call_id);
              setPendingApproval(null);
              setStreamingState('responding');
            }
          }

          if (state === 'completed' || state === 'canceled' || state === 'failed') {
            stopTimer();
            setStreamingState('idle');
            setCurrentTaskId(null);
            setPendingApproval(null);

            // commit thinking if any
            if (thoughtRef.current) {
              collectedItems.unshift({ id: genId(), type: 'thinking', content: thoughtRef.current });
            }
            // commit assistant text
            if (textRef.current) {
              const idx = collectedItems.findIndex(i => i.type === 'assistant');
              if (idx >= 0) {
                collectedItems[idx] = { ...collectedItems[idx], content: textRef.current, isStreaming: false };
              } else {
                collectedItems.push({ id: genId(), type: 'assistant', content: textRef.current, isStreaming: false });
              }
              setLastAssistantText(textRef.current);
            }

            if (state === 'failed') {
              const errText = event.status?.message?.parts?.[0]?.text || 'Unknown error';
              collectedItems.push({ id: genId(), type: 'error', content: errText });
            }

            addToHistory(collectedItems);
            setPendingItems([]);
            setTurnCount(c => c + 1);
          }
        }
      });
    } catch (err: any) {
      stopTimer();
      setStreamingState('idle');
      if (!err.message?.includes('abort') && !err.message?.includes('cancel')) {
        addToHistory([{ id: genId(), type: 'error', content: err.message }]);
      }
      setPendingItems([]);
    }
  }, [client, addToHistory, startTimer, stopTimer, skipPermissions]);

  const cancelTurn = useCallback(() => {
    if (currentTaskId) {
      client.cancel(currentTaskId).catch(() => {});
    }
  }, [client, currentTaskId]);

  const approveToolCall = useCallback((decision: 'approve' | 'deny') => {
    if (pendingApproval) {
      client.approve(pendingApproval.taskId, decision, pendingApproval.callId).catch(() => {});
      setPendingApproval(null);
      setStreamingState('responding');
    }
  }, [client, pendingApproval]);

  return {
    streamingState, pendingItems, currentTaskId, pendingApproval,
    thought, elapsedMs, lastAssistantText,
    totalTokens, inputTokens, outputTokens, turnCount,
    submitMessage, cancelTurn, approveToolCall,
  };
}
