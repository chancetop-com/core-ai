import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, Send, X } from 'lucide-react';

interface Preset {
  label: string;
  message: string;
  agent: string;
}

const PRESETS: Preset[] = [
  { label: 'Report Issue', message: 'I want to report issue of core-ai!', agent: 'core-ai-issue-reporter' },
  { label: 'Build Agent', message: 'help', agent: 'agent-builder' },
  { label: 'Build LLM Call', message: 'help', agent: 'llm-call-builder' },
];

const DRAG_THRESHOLD = 4; // px — below this, treat as a click

export default function QuickActionDialog() {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const navigate = useNavigate();
  const dialogRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Drag state
  const [pos, setPos] = useState({ x: -1, y: -1 }); // -1 means use default (bottom-right)
  const dragging = useRef(false);
  const dragStart = useRef({ x: 0, y: 0, posX: 0, posY: 0 });
  const wasDragged = useRef(false);
  // Tracks the position the user last chose (by drag or init), so the resize handler
  // can restore it when the viewport is large enough again.
  const intendedPos = useRef({ x: -1, y: -1 });

  // Init default position (bottom-right inset)
  const getDefaultPos = useCallback(() => ({
    x: window.innerWidth - 72,  // 24px right + 48px button
    y: window.innerHeight - 72, // 24px bottom + 48px button
  }), []);

  useEffect(() => {
    if (pos.x < 0) {
      const dp = getDefaultPos();
      setPos(dp);
      intendedPos.current = dp;
    }
  }, [pos.x, getDefaultPos]);

  // Re-anchor on window resize — restore intended position when possible
  useEffect(() => {
    const handleResize = () => {
      setPos(prev => {
        const maxX = window.innerWidth - 48;
        const maxY = window.innerHeight - 48;

        // If the intended position (user-chosen or initial) fits in the new viewport,
        // restore it so the button goes back to where the user placed it.
        if (intendedPos.current.x >= 0 && intendedPos.current.y >= 0) {
          if (intendedPos.current.x <= maxX && intendedPos.current.y <= maxY) {
            return intendedPos.current;
          }
        }

        // Otherwise clamp the current position to keep it visible
        const nx = Math.max(0, Math.min(prev.x, maxX));
        const ny = Math.max(0, Math.min(prev.y, maxY));
        return nx === prev.x && ny === prev.y ? prev : { x: nx, y: ny };
      });
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    dragging.current = true;
    wasDragged.current = false;
    dragStart.current = {
      x: e.clientX,
      y: e.clientY,
      posX: pos.x,
      posY: pos.y,
    };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }, [pos]);

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!dragging.current) return;
    const dx = e.clientX - dragStart.current.x;
    const dy = e.clientY - dragStart.current.y;
    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
      wasDragged.current = true;
    }
    setPos({
      x: Math.max(0, Math.min(dragStart.current.posX + dx, window.innerWidth - 48)),
      y: Math.max(0, Math.min(dragStart.current.posY + dy, window.innerHeight - 48)),
    });
  }, []);

  const handlePointerUp = useCallback((e: React.PointerEvent) => {
    dragging.current = false;
    if (!wasDragged.current) {
      setOpen(prev => !prev);
    } else {
      // Save the final drag position as the intended position
      setPos(current => {
        intendedPos.current = { x: current.x, y: current.y };
        return current;
      });
    }
    (e.target as HTMLElement).releasePointerCapture(e.pointerId);
  }, []);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (dialogRef.current && !dialogRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [open]);

  // Focus input when opened
  useEffect(() => {
    if (open) {
      setTimeout(() => inputRef.current?.focus(), 150);
    } else {
      setInput('');
    }
  }, [open]);

  const handleSend = useCallback((message: string, agent: string) => {
    const encoded = encodeURIComponent(message);
    navigate(`/chat?agent=${agent}&auto=help&message=${encoded}`);
    setOpen(false);
  }, [navigate]);

  const handleInputKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && input.trim()) {
      e.preventDefault();
      handleSend(input.trim(), 'default-assistant');
    }
  }, [input, handleSend]);

  if (pos.x < 0) return null; // not initialized yet

  return (
    <div ref={dialogRef} className="fixed z-50" style={{ left: pos.x, top: pos.y }}>
      {/* Dialog panel — appears above or left of the button */}
      {open && (
        <div
          className="absolute bottom-14 right-0 w-80 rounded-2xl shadow-2xl border overflow-hidden"
          style={{
            background: 'var(--color-bg-secondary)',
            borderColor: 'var(--color-border)',
          }}>
          {/* Header */}
          <div className="flex items-center justify-between px-4 py-3 border-b"
            style={{ borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-2">
              <Bot size={18} style={{ color: 'var(--color-primary)' }} />
              <span className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>Quick Action</span>
            </div>
            <button
              onClick={() => setOpen(false)}
              className="p-1 rounded-lg cursor-pointer transition-colors"
              style={{ color: 'var(--color-text-secondary)' }}>
              <X size={16} />
            </button>
          </div>

          {/* Input area */}
          <div className="p-3">
            <textarea
              ref={inputRef}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleInputKeyDown}
              placeholder="Ask or type a message..."
              rows={2}
              className="w-full rounded-xl border px-3 py-2.5 text-sm resize-none focus:outline-none"
              style={{
                background: 'var(--color-bg)',
                borderColor: 'var(--color-border)',
                color: 'var(--color-text)',
              }}
            />
            <div className="flex justify-end mt-2">
              <button
                onClick={() => input.trim() && handleSend(input.trim(), 'default-assistant')}
                disabled={!input.trim()}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer transition-colors disabled:opacity-30"
                style={{
                  background: 'var(--color-primary)',
                  color: '#fff',
                }}>
                <Send size={12} />
                Send
              </button>
            </div>
          </div>

          {/* Preset suggestions */}
          <div className="px-3 pb-3 flex flex-wrap gap-1.5">
            {PRESETS.map(preset => (
              <button
                key={preset.label}
                onClick={() => handleSend(preset.message, preset.agent)}
                className="px-2.5 py-1 rounded-full text-xs font-medium cursor-pointer transition-colors border"
                style={{
                  background: 'var(--color-bg-tertiary)',
                  borderColor: 'var(--color-border)',
                  color: 'var(--color-text-secondary)',
                }}>
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Floating trigger button — draggable */}
      <button
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="w-12 h-12 rounded-full shadow-lg cursor-grab active:cursor-grabbing transition-transform flex items-center justify-center select-none touch-none"
        style={{
          background: 'var(--color-primary)',
          color: '#fff',
          transform: open ? 'rotate(90deg)' : 'rotate(0deg)',
        }}
        title="Quick Action — drag to move">
        {open ? <X size={22} /> : <Bot size={22} />}
      </button>
    </div>
  );
}
