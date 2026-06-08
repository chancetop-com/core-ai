import { useCallback, type CSSProperties, type ReactNode, type MouseEvent as ReactMouseEvent } from 'react';

interface Props {
  width: number;
  onWidthChange: (width: number) => void;
  children: ReactNode;
  min?: number;
  max?: number;
}

/** Wraps a right-side panel with a draggable left-edge handle so the user can resize it. The panel content
 *  fills the wrapper (children should be width:100% / height:100%). */
export default function ResizablePanel({ width, onWidthChange, children, min = 260, max = 720 }: Props) {
  const onMouseDown = useCallback((e: ReactMouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = width;
    const move = (ev: MouseEvent) => onWidthChange(Math.min(max, Math.max(min, startWidth + (startX - ev.clientX))));
    const up = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, [width, onWidthChange, min, max]);

  return (
    <div style={{ width, flexShrink: 0, display: 'flex' }}>
      <div onMouseDown={onMouseDown} style={handle} title="Drag to resize" />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>{children}</div>
    </div>
  );
}

const handle: CSSProperties = {
  width: 6, flexShrink: 0, cursor: 'col-resize', borderLeft: '1px solid var(--color-border)',
};
