import { useEffect, useRef, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import VariablePicker from './VariablePicker';
import { parseSegments, selectorMeta } from './variables';
import type { WorkflowRFNode } from './graph';

interface Props {
  value: string;
  onChange: (next: string) => void;
  nodes: WorkflowRFNode[];
  selfId: string;
  placeholder?: string;
  multiline?: boolean;
}

/** Inline variable field: free text with variables rendered as name-labeled chips (Dify-style). The user never
 *  sees the raw {{ nodes.<id>... }} / node ids; chips resolve their label live from the node name. Stored value
 *  stays the id-based template string. Uncontrolled contentEditable so the caret survives edits. */
export default function VariableChipField({ value, onChange, nodes, selfId, placeholder, multiline }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const range = useRef<Range | null>(null);

  // Seed the DOM once (the panel remounts per node, so value never changes underneath after mount).
  useEffect(() => {
    if (ref.current) ref.current.replaceChildren(...build(value, nodes));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const serialize = (): string => {
    const el = ref.current;
    if (!el) return '';
    let out = '';
    el.childNodes.forEach((n) => {
      if (n.nodeType === Node.TEXT_NODE) out += n.textContent ?? '';
      else if (n instanceof HTMLElement && n.dataset.sel) out += `{{ ${n.dataset.sel} }}`;
      else out += n.textContent ?? '';
    });
    return out;
  };

  const saveRange = () => {
    const sel = window.getSelection();
    if (sel && sel.rangeCount && ref.current && ref.current.contains(sel.anchorNode)) {
      range.current = sel.getRangeAt(0).cloneRange();
    }
  };

  // Keep the editor a flat list of text nodes + chips: handle Enter ourselves (insert a "\n" text node) so the
  // browser never wraps lines in <div>/<br>, which would break serialize() and lose newlines.
  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    if (!multiline) return;
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    const r = sel.getRangeAt(0);
    const nl = document.createTextNode('\n');
    r.deleteContents();
    r.insertNode(nl);
    r.setStartAfter(nl);
    r.collapse(true);
    sel.removeAllRanges();
    sel.addRange(r);
    range.current = r.cloneRange();
    onChange(serialize());
  };

  const insert = (selector: string) => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    let r = range.current;
    if (!r || !el.contains(r.startContainer)) { r = document.createRange(); r.selectNodeContents(el); r.collapse(false); }
    const chip = chipEl(selector, nodes);
    const space = document.createTextNode(' ');
    r.insertNode(space);
    r.insertNode(chip);
    r.setStartAfter(space);
    r.collapse(true);
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(r);
    range.current = r.cloneRange();
    onChange(serialize());
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 4 }}>
        <VariablePicker nodes={nodes} selfId={selfId} onPick={insert} />
      </div>
      <div
        ref={ref}
        contentEditable
        suppressContentEditableWarning
        role="textbox"
        aria-multiline={multiline ? 'true' : 'false'}
        data-placeholder={placeholder ?? ''}
        onInput={() => onChange(serialize())}
        onKeyDown={onKeyDown}
        onKeyUp={saveRange}
        onMouseUp={saveRange}
        onBlur={saveRange}
        className="var-chip-field"
        style={{ ...box, minHeight: multiline ? 76 : 34 }}
      />
    </div>
  );
}

function build(value: string, nodes: WorkflowRFNode[]): ChildNode[] {
  const out: ChildNode[] = [];
  for (const seg of parseSegments(value)) {
    if (seg.text != null) out.push(document.createTextNode(seg.text));
    else if (seg.selector != null) out.push(chipEl(seg.selector, nodes));
  }
  return out;
}

function chipEl(selector: string, nodes: WorkflowRFNode[]): HTMLElement {
  const { label, color } = selectorMeta(nodes, selector);
  const span = document.createElement('span');
  span.contentEditable = 'false';
  span.dataset.sel = selector;
  span.textContent = label;
  span.style.cssText = `display:inline-flex;align-items:center;padding:0 6px;margin:0 1px;border-radius:5px;`
    + `font-size:12px;font-family:system-ui,sans-serif;white-space:nowrap;background:${color}22;color:${color};border:1px solid ${color}55;`;
  return span;
}

const box: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13, lineHeight: 1.7,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)',
  outline: 'none', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
};
