import { useEffect, useRef } from 'react';
import { X, Mic, MicOff, Loader2, Trash2, Download, ArrowRightToLine, Send, Play } from 'lucide-react';
import type { TranscriptionSegment } from '../hooks/useSpeechRecognition';

interface Props {
  segments: TranscriptionSegment[];
  isListening: boolean;
  error: string | null;
  language: string;
  onLanguageChange: (lang: string) => void;
  onStop: () => void;
  onClose: () => void;
  onClear: () => void;
  onSendToChat: (text: string) => void;
  onSendAllToChat: () => void;
  onStartListening: () => void;
}

const SPEAKER_COLORS = ['#3b82f6', '#ef4444', '#10b981', '#f59e0b', '#8b5cf6', '#ec4899'];

const SUPPORTED_LANGUAGES = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' },
  { value: 'ja-JP', label: '日本語' },
  { value: 'ko-KR', label: '한국어' },
];

function getSpeakerLabel(speakerId: string): string {
  const match = speakerId.match(/speaker-(\d+)/i);
  if (match) return `Speaker ${match[1]}`;
  return speakerId;
}

function getSpeakerColor(speakerId: string): string {
  const match = speakerId.match(/speaker-(\d+)/i);
  const idx = match ? (parseInt(match[1]) - 1) % SPEAKER_COLORS.length : 0;
  return SPEAKER_COLORS[idx];
}

function formatTimestamp(ms: number): string {
  const sec = Math.floor(ms / 1000);
  const min = Math.floor(sec / 60);
  const s = sec % 60;
  return `${min.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

function exportTranscript(segments: TranscriptionSegment[]): void {
  if (segments.length === 0) return;

  const lines = segments.map(seg => {
    const label = getSpeakerLabel(seg.speakerId);
    const ts = formatTimestamp(seg.timestamp);
    return `[${ts}] ${label}: ${seg.text}`;
  });

  const content = lines.join('\n');
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `transcript-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`;
  a.click();
  URL.revokeObjectURL(url);
}

export default function VoiceTranscriberSidebar({
  segments, isListening, error, language, onLanguageChange,
  onStop, onClose, onClear, onSendToChat, onSendAllToChat, onStartListening,
}: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [segments]);

  // Group consecutive segments from same speaker
  const grouped = segments.reduce<{ speakerId: string; speakerLabel: string; texts: string[]; ids: string[] }[]>((acc, seg) => {
    const label = getSpeakerLabel(seg.speakerId);
    const last = acc[acc.length - 1];
    if (last && last.speakerId === seg.speakerId) {
      last.texts.push(seg.text);
      last.ids.push(seg.id);
    } else {
      acc.push({ speakerId: seg.speakerId, speakerLabel: label, texts: [seg.text], ids: [seg.id] });
    }
    return acc;
  }, []);

  return (
    <div className="flex flex-col h-full w-80 shrink-0 border-l"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
      {/* Header — same structure as chat top bar for height alignment */}
      <div className="flex items-center justify-between px-6 py-3 border-b min-h-[61px]"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex items-center justify-center rounded-lg shrink-0"
            style={{ background: isListening ? 'var(--color-error)' + '18' : 'var(--color-bg-tertiary)', width: 36, height: 36 }}>
            {isListening ? (
              <Mic size={16} style={{ color: 'var(--color-error)' }} className="animate-pulse" />
            ) : (
              <Mic size={16} style={{ color: 'var(--color-text-secondary)' }} />
            )}
          </div>
          <div className="min-w-0">
            <span className="text-sm font-medium truncate block" style={{ color: 'var(--color-text)' }}>
              Voice Transcript
            </span>
            {isListening ? (
              <span className="text-xs truncate block" style={{ color: 'var(--color-error)' }}>● Live</span>
            ) : (
              <span className="text-xs truncate block" style={{ color: 'var(--color-text-muted)' }}>
                Speech recognition
              </span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1">
          <button onClick={() => exportTranscript(segments)}
            disabled={segments.length === 0}
            className="p-1.5 rounded-lg cursor-pointer transition-colors disabled:opacity-30"
            style={{ color: 'var(--color-text-secondary)' }}
            title="Export transcript"
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
            <Download size={14} />
          </button>
          <button onClick={onClear}
            disabled={segments.length === 0}
            className="p-1.5 rounded-lg cursor-pointer transition-colors disabled:opacity-30"
            style={{ color: 'var(--color-text-secondary)' }}
            title="Clear transcript"
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
            <Trash2 size={14} />
          </button>
          <button onClick={onClose}
            className="p-1.5 rounded-lg cursor-pointer transition-colors"
            style={{ color: 'var(--color-text-secondary)' }}
            title="Close sidebar"
            onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
            <X size={16} />
          </button>
        </div>
      </div>

      {/* Body: language + error + segments, bottom buttons pinned to bottom */}
      <div className="flex-1 flex flex-col overflow-hidden">

      {/* Language selector */}
      <div className="px-6 py-2 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <select
          value={language}
          onChange={e => onLanguageChange(e.target.value)}
          disabled={isListening}
          className="w-full px-2 py-1.5 rounded-lg text-xs border cursor-pointer disabled:opacity-40"
          style={{
            background: 'var(--color-bg-secondary)',
            borderColor: 'var(--color-border)',
            color: 'var(--color-text)',
          }}>
          {SUPPORTED_LANGUAGES.map(l => (
            <option key={l.value} value={l.value}>{l.label}</option>
          ))}
        </select>
      </div>

      {/* Error */}
      {error && (
        <div className="px-6 py-3 text-xs border-b" style={{
          background: 'var(--color-error)' + '10',
          color: 'var(--color-error)',
          borderColor: 'var(--color-border)',
        }}>
          {error}
        </div>
      )}

      {/* Segments */}
      <div ref={scrollRef} className="flex-1 overflow-auto px-6 py-3">
        {!isListening && segments.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full gap-4"
            style={{ color: 'var(--color-text-secondary)' }}>
            <Mic size={40} strokeWidth={1} style={{ opacity: 0.3 }} />
            <button onClick={onStartListening}
              className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-medium cursor-pointer transition-colors"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              <Play size={16} />
              Start
            </button>
          </div>
        )}

        {grouped.map((group) => {
          const speakerColor = getSpeakerColor(group.speakerId);
          const fullText = group.texts.join(' ');
          return (
            <div key={group.ids[0]} className="mb-3 group/utterance">
              <div className="flex items-center gap-1.5 mb-1">
                <div className="w-2 h-2 rounded-full shrink-0"
                  style={{ background: speakerColor }} />
                <span className="text-xs font-medium" style={{ color: speakerColor }}>
                  {group.speakerLabel}
                </span>
                <span className="text-xs opacity-50 ml-auto"
                  style={{ color: 'var(--color-text-muted)' }}>
                  {formatTimestamp(segments.find(s => s.id === group.ids[0])?.timestamp ?? 0)}
                </span>
              </div>
              <div className="flex items-start gap-1 ml-3.5 pl-2 border-l-2"
                style={{ borderColor: speakerColor + '30' }}>
                <p className="text-sm leading-relaxed flex-1" style={{ color: 'var(--color-text)' }}>
                  {group.texts.map((text, i) => (
                    <span key={group.ids[i]}>{text}{i < group.texts.length - 1 ? ' ' : ''}</span>
                  ))}
                </p>
                <button
                  onClick={() => onSendToChat(fullText)}
                  className="p-0.5 rounded cursor-pointer opacity-0 group-hover/utterance:opacity-100 transition-opacity shrink-0 mt-0.5"
                  style={{ color: 'var(--color-text-secondary)' }}
                  title="Send to chat"
                  onMouseEnter={e => (e.currentTarget.style.color = 'var(--color-primary)')}
                  onMouseLeave={e => (e.currentTarget.style.color = 'var(--color-text-secondary)')}>
                  <ArrowRightToLine size={14} />
                </button>
              </div>
            </div>
          );
        })}

        {isListening && (
          <div className="flex items-center gap-2 py-2">
            <Loader2 size={14} className="animate-spin" style={{ color: 'var(--color-text-secondary)' }} />
            <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>Listening...</span>
          </div>
        )}
      </div>

      </div>{/* End body flex-1 */}

      {/* Send all to chat */}
      {segments.length > 0 && !isListening && (
        <div className="p-4 border-t min-h-[79px] flex items-center" style={{ borderColor: 'var(--color-border)' }}>
          <button onClick={onSendAllToChat}
            className="w-full flex items-center justify-center gap-2 px-3 py-3 rounded-lg text-sm font-medium cursor-pointer transition-colors"
            style={{ background: 'var(--color-primary)', color: 'white' }}>
            <Send size={16} />
            Send All to Chat
          </button>
        </div>
      )}

      {/* Stop button */}
      {isListening && (
        <div className="p-4 border-t min-h-[79px] flex items-center" style={{ borderColor: 'var(--color-border)' }}>
          <button onClick={onStop}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer transition-colors"
            style={{ background: 'var(--color-error)', color: 'white' }}>
            <MicOff size={14} />
            Stop Listening
          </button>
        </div>
      )}
    </div>
  );
}
