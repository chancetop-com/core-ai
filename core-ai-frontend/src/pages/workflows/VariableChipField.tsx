import { useEffect, useRef, type CSSProperties, type ClipboardEvent as ReactClipboardEvent, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import VariablePicker from './VariablePicker';
import { parseSegments, selectorMeta } from './variables';
import type { WorkflowRFNode } from './graph';
import type { Edge } from '@xyflow/react';

interface Props {
  value: string;
  onChange: (next: string) => void;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  selfId: string;
  placeholder?: string;
  multiline?: boolean;
}

/** Inline variable field: free text with variables rendered as name-labeled chips (Dify-style). The user never
 *  sees the raw {{ nodes.<id>... }} / node ids; chips resolve their label live from the node name. Stored value
 *  stays the id-based template string. Uncontrolled contentEditable so the caret survives edits. */
export default function VariableChipField({ value, onChange, nodes, edges, selfId, placeholder, multiline }: Props) {
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

  // Paste as plain text into the flat text-node/chip structure. The browser's default paste inserts <div>/<br>
  // wrappers that serialize() flattens — silently dropping newlines and gluing lines together; this avoids that.
  const onPaste = (e: ReactClipboardEvent<HTMLDivElement>) => {
    e.preventDefault();
    const raw = e.clipboardData.getData('text/plain');
    const text = multiline ? raw : raw.replace(/\r?\n/g, ' ');   // single-line fields collapse newlines
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    const r = sel.getRangeAt(0);
    r.deleteContents();
    const node = document.createTextNode(text);
    r.insertNode(node);
    r.setStartAfter(node);
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
        <VariablePicker nodes={nodes} edges={edges} selfId={selfId} onPick={insert} />
      </div>
      <div
        ref={ref}
        contentEditable
        suppressContentEditableWarning
        role="textbox"
        aria-multiline={multiline ? 'true' : 'false'}
        data-placeholder={placeholder ?? ''}
        onInput={() => onChange(serialize())}
        onPaste={onPaste}
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
  span.title = label;   // long names truncate to an ellipsis; the full selector label stays on hover
  // inline-block (not inline-flex) + vertical margin keeps chips from overlapping the next wrapped line — the
  // editor's tall line-height gives each row room; max-width caps a long name so one chip can't blow out the field.
  span.style.cssText = `display:inline-block;vertical-align:middle;line-height:1.5;padding:1px 6px;margin:2px 1px;`
    + `max-width:220px;overflow:hidden;text-overflow:ellipsis;border-radius:5px;`
    + `font-size:12px;font-family:system-ui,sans-serif;white-space:nowrap;background:${color}22;color:${color};border:1px solid ${color}55;`;
  return span;
}

const box: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13, lineHeight: 2.1,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)',
  outline: 'none', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
};
