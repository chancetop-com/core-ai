import { memo, useMemo } from 'react';
import type { ComponentProps, CSSProperties, HTMLAttributes, ReactNode, RefObject } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import type { PluggableList } from 'unified';
import { Bot, ChevronDown, ChevronRight, Loader2, MessageSquareHeart, Paperclip, Shield, ShieldOff, Sparkles, User } from 'lucide-react';
import type { SessionArtifact } from '../../../api/session';
import type { ChatMessage, MessageSegment, PlanTodo, SandboxSegment, SandboxTerminalSpec, ToolsSegment } from '../types';
import { formatMessageTime, formatMessageTimeFull, getMessageText } from '../utils';
import { chatSanitizeSchema } from '../markdownSanitizeSchema';
import type { ArtifactSpec } from './artifactTypes';
import ArtifactCard from './ArtifactCard';
import AuthedImage from './AuthedImage';
import CopyButton from './CopyButton';
import PlanUpdateBlock from './PlanUpdateBlock';
import SandboxBlock from './SandboxBlock';
import ThinkingBlock from './ThinkingBlock';
import ToolsBlock from './ToolsBlock';

export const INITIAL_VISIBLE_MESSAGES = 40;
export const MESSAGE_RENDER_BATCH = 40;

type ChatStatus = 'idle' | 'running';
type MarkdownComponents = ComponentProps<typeof ReactMarkdown>['components'];

interface VisibleMessage {
  msg: ChatMessage;
  index: number;
}

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

const AGENT_REHYPE_PLUGINS: PluggableList = [rehypeRaw, [rehypeSanitize, chatSanitizeSchema]];
const REMARK_PLUGINS: PluggableList = [remarkGfm];
const OFFSCREEN_MESSAGE_STYLE: CSSProperties = {
  contentVisibility: 'auto',
  containIntrinsicSize: '1px 160px',
};

function shouldRenderMessage(msg: ChatMessage, idx: number, messagesLength: number, status: ChatStatus): boolean {
  return msg.role === 'user'
    || hasAnySegments(msg.segments)
    || !!msg.approval
    || (status === 'running' && msg.role === 'agent' && idx === messagesLength - 1);
}

function messageKey(msg: ChatMessage, idx: number): string {
  return `${msg.role}-${msg.timestamp || 'pending'}-${idx}`;
}

interface ChatMessageRowProps {
  msg: ChatMessage;
  msgIndex: number;
  messagesLength: number;
  isStreamingLast: boolean;
  isThinking: boolean;
  planTodos: PlanTodo[] | null;
  sessionArtifacts: SessionArtifact[];
  agentMarkdownComponents: MarkdownComponents;
  onOpenArtifact: (spec: ArtifactSpec) => void;
  onOpenSandboxTerminal: (spec: SandboxTerminalSpec) => void;
  onApproval: (decision: 'APPROVE' | 'DENY') => void;
  showFeedback: boolean;
  onFeedbackClick?: () => void;
}

const ChatMessageRow = memo(function ChatMessageRow({
  msg,
  msgIndex,
  messagesLength,
  isStreamingLast,
  isThinking,
  planTodos,
  sessionArtifacts,
  agentMarkdownComponents,
  onOpenArtifact,
  onOpenSandboxTerminal,
  onApproval,
  showFeedback,
  onFeedbackClick,
}: ChatMessageRowProps) {
  const sandboxSeg = msg.segments?.find(s => s.type === 'sandbox') as SandboxSegment | undefined;
  const thinkingSeg = msg.segments?.find(s => s.type === 'thinking');
  const toolsSeg = msg.segments?.find(s => s.type === 'tools') as ToolsSegment | undefined;
  const textSeg = msg.segments?.find(s => s.type === 'text');
  const msgArtifacts = msg.role === 'agent' ? getMessageArtifacts(msg, sessionArtifacts) : [];

  // content-visibility: auto skips paint for off-screen elements, but the browser
  // heuristic can incorrectly flag a streaming message as off-screen when its
  // content grows rapidly (e.g. TEXT_CHUNK arrives while scrollIntoView is in
  // flight).  That causes the symptom "SSE has events but UI never paints them".
  // Keep the optimization only for completed (non-streaming) messages.
  const messageStyle = isStreamingLast ? undefined : OFFSCREEN_MESSAGE_STYLE;

  return (
    <div className={`group flex gap-3 ${msg.role === 'user' ? 'justify-end' : ''}`} style={messageStyle}>
      {msg.role === 'agent' && (
        <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0"
          style={{ background: 'var(--color-primary)', color: 'white' }}>
          <Bot size={18} />
        </div>
      )}
      <div className={`max-w-[80%] ${msg.role === 'user' ? 'order-first' : ''}`}>
        {sandboxSeg && (
          <div className="mb-3">
            <SandboxBlock seg={sandboxSeg} onOpenTerminal={onOpenSandboxTerminal} />
          </div>
        )}
        {thinkingSeg && (
          <div className="mb-3">
            <ThinkingBlock thinking={thinkingSeg.content} isStreaming={isStreamingLast && isThinking} />
          </div>
        )}
        {toolsSeg && toolsSeg.tools.length > 0 && (
          <div className="mb-3">
            <ToolsBlock tools={toolsSeg.tools} />
          </div>
        )}
        {textSeg && (
          <div className="mb-3">
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
                      <span className="max-w-[120px] truncate">{att.file_name || att.type}</span>
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
                  remarkPlugins={REMARK_PLUGINS}
                  rehypePlugins={msg.role === 'agent' && !isStreamingLast ? AGENT_REHYPE_PLUGINS : undefined}
                  components={msg.role === 'agent' ? agentMarkdownComponents : undefined}>
                  {textSeg.content}
                </ReactMarkdown>
              </div>
              {isStreamingLast && textSeg.content && (
                <span className="inline-block w-2 h-4 ml-0.5 animate-pulse rounded-sm align-middle" style={{ background: 'var(--color-primary)' }} />
              )}
            </div>
          </div>
        )}
        {isStreamingLast && msg.role === 'agent' && !hasAnySegments(msg.segments) && (
          <div className="flex items-center gap-2 py-2 px-1">
            <Loader2 size={16} className="animate-spin" style={{ color: 'var(--color-primary)' }} />
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Thinking...</span>
          </div>
        )}
        {planTodos && planTodos.length > 0 && msg.role === 'agent' && msgIndex === messagesLength - 1 && <PlanUpdateBlock todos={planTodos} />}
        {msgArtifacts.length > 0 && (
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
                onOpen={onOpenArtifact}
              />
            ))}
          </div>
        )}
        {(msg.timestamp || hasTextSegments(msg.segments) || showFeedback) && (
          <div className={`flex items-center gap-2 mt-1 ${msg.role === 'user' ? 'justify-end' : ''}`}>
            {hasTextSegments(msg.segments) && (
              <CopyButton text={getMessageText(msg)} />
            )}
            {showFeedback && (
              <button
                type="button"
                onClick={onFeedbackClick}
                className="inline-flex items-center gap-1 text-[11px] leading-none cursor-pointer hover:underline"
                style={{ background: 'none', border: 'none', color: 'var(--color-text-muted)' }}
              >
                <MessageSquareHeart size={12} />
                Feedback
              </button>
            )}
            {msg.timestamp && (
              <span className="text-[11px] leading-none select-none"
                title={formatMessageTimeFull(msg.timestamp)}
                style={{ color: 'var(--color-text-muted)' }}>
                {formatMessageTime(msg.timestamp)}
              </span>
            )}
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
              <button onClick={() => onApproval('APPROVE')}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
                style={{ background: 'var(--color-success)', color: 'white' }}>
                <Shield size={14} /> Approve
              </button>
              <button onClick={() => onApproval('DENY')}
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
  );
});

interface ChatMessagesPanelProps {
  messages: ChatMessage[];
  status: ChatStatus;
  isThinking: boolean;
  planTodos: PlanTodo[] | null;
  compressionInfo: { before: number; after: number } | null;
  sessionArtifacts: SessionArtifact[];
  selectedAgentName?: string;
  agentVariableEntries: [string, unknown][];
  variableValues: Record<string, string>;
  variablesExpanded: boolean;
  variablesPanelRef: RefObject<HTMLDivElement | null>;
  messagesContainerRef: RefObject<HTMLDivElement | null>;
  bottomRef: RefObject<HTMLDivElement | null>;
  showJumpToBottom: boolean;
  visibleMessageLimit: number;
  onMessagesScroll: () => void;
  onToggleVariables: () => void;
  onVariableChange: (key: string, value: string) => void;
  onShowEarlierMessages: () => void;
  onScrollToBottom: (behavior?: ScrollBehavior) => void;
  onOpenArtifact: (spec: ArtifactSpec) => void;
  onOpenSandboxTerminal: (spec: SandboxTerminalSpec) => void;
  onApproval: (decision: 'APPROVE' | 'DENY') => void;
  showFeedback?: boolean;
  onFeedbackClick?: () => void;
}

const ChatMessagesPanel = memo(function ChatMessagesPanel({
  messages,
  status,
  isThinking,
  planTodos,
  compressionInfo,
  sessionArtifacts,
  selectedAgentName,
  agentVariableEntries,
  variableValues,
  variablesExpanded,
  variablesPanelRef,
  messagesContainerRef,
  bottomRef,
  showJumpToBottom,
  visibleMessageLimit,
  onMessagesScroll,
  onToggleVariables,
  onVariableChange,
  onShowEarlierMessages,
  onScrollToBottom,
  onOpenArtifact,
  onOpenSandboxTerminal,
  onApproval,
  showFeedback,
  onFeedbackClick,
}: ChatMessagesPanelProps) {
  const renderableMessages = useMemo<VisibleMessage[]>(() => (
    messages
      .map((msg, index) => ({ msg, index }))
      .filter(({ msg, index }) => shouldRenderMessage(msg, index, messages.length, status))
  ), [messages, status]);
  const lastAgentMsgIndex = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'agent') return i;
    }
    return -1;
  }, [messages]);
  const hiddenMessageCount = Math.max(0, renderableMessages.length - visibleMessageLimit);
  const visibleMessages = hiddenMessageCount > 0
    ? renderableMessages.slice(hiddenMessageCount)
    : renderableMessages;
  const agentMarkdownComponents = useMemo(() => ({
    code({ inline, className, children, ...props }: { inline?: boolean; className?: string; children?: ReactNode } & HTMLAttributes<HTMLElement>) {
      const match = /language-(\w+)/.exec(className || '');
      if (!inline && match) {
        const lang = match[1].toLowerCase();
        const codeText = String(children ?? '').replace(/\n$/, '');
        if (lang === 'html' || lang === 'svg') {
          return (
            <ArtifactCard
              artifact={{ kind: lang, language: lang, title: lang === 'html' ? 'HTML page' : 'SVG image', content: codeText }}
              onOpen={onOpenArtifact}
            />
          );
        }
        return (
          <ArtifactCard
            artifact={{ kind: 'code', language: lang, title: `${lang} snippet`, content: codeText }}
            onOpen={onOpenArtifact}
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
          <span style={{ color: 'var(--color-warning)' }}>!</span>
          <span>Image <code style={{ color: 'var(--color-text)' }}>{src || alt || '?'}</code> not available - agent must call <code>submit_artifacts</code> first.</span>
        </span>
      );
    },
  }), [onOpenArtifact]);

  return (
    <div ref={messagesContainerRef} onScroll={onMessagesScroll} className="flex-1 overflow-auto p-6 relative">
      {agentVariableEntries.length > 0 && (
        <div className="absolute top-5 right-6 z-20">
          <div className="relative" ref={variablesPanelRef}>
            <button
              onClick={onToggleVariables}
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
                        onChange={e => onVariableChange(key, e.target.value)}
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
          <div className="text-lg font-medium">{selectedAgentName || 'AI Assistant'}</div>
          <div className="text-sm">Send a message to start</div>
        </div>
      )}
      <div className="max-w-4xl mx-auto flex flex-col gap-4">
        {compressionInfo && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg text-xs animate-pulse"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-muted)', border: '1px solid var(--color-border)' }}>
            <Sparkles size={14} />
            <span>Context compressed: {compressionInfo.before} -&gt; {compressionInfo.after} messages</span>
          </div>
        )}
        {hiddenMessageCount > 0 && (
          <button
            type="button"
            onClick={onShowEarlierMessages}
            className="self-center rounded-lg border px-3 py-1.5 text-xs cursor-pointer hover:opacity-80"
            style={{
              borderColor: 'var(--color-border)',
              background: 'var(--color-bg-secondary)',
              color: 'var(--color-text-secondary)',
            }}>
            Show earlier messages ({hiddenMessageCount})
          </button>
        )}
        {visibleMessages.map(({ msg, index }) => {
          const isStreamingLast = status === 'running' && index === messages.length - 1;
          return (
            <ChatMessageRow
              key={messageKey(msg, index)}
              msg={msg}
              msgIndex={index}
              messagesLength={messages.length}
              isStreamingLast={isStreamingLast}
              isThinking={isThinking}
              planTodos={isStreamingLast ? planTodos : null}
              sessionArtifacts={sessionArtifacts}
              agentMarkdownComponents={agentMarkdownComponents}
              onOpenArtifact={onOpenArtifact}
              onOpenSandboxTerminal={onOpenSandboxTerminal}
              onApproval={onApproval}
              showFeedback={showFeedback === true && index === lastAgentMsgIndex}
              onFeedbackClick={onFeedbackClick}
            />
          );
        })}
        <div ref={bottomRef} />
      </div>
      {showJumpToBottom && (
        <button
          onClick={() => onScrollToBottom('smooth')}
          className="absolute bottom-4 right-4 z-10 flex items-center justify-center rounded-full shadow-md cursor-pointer transition-opacity"
          style={{
            width: 36,
            height: 36,
            background: 'var(--color-bg-secondary)',
            border: '1px solid var(--color-border)',
            color: 'var(--color-text)',
          }}
          title="Jump to latest">
          <ChevronDown size={18} />
        </button>
      )}
    </div>
  );
});

export default ChatMessagesPanel;
