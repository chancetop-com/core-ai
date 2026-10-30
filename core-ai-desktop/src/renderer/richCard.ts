// Rich cards: the platform-defined `card` companion field of a custom event (design-session-rich-cards.md).
// The vocabulary is closed and additive-only; unknown blocks are skipped so a newer emitter cannot break
// this client. Every text is plain text — no markdown, no HTML — and a card never executes anything on
// render: links open only on a click, and the only writes are the user's own submissions.

export interface CardBlock {
  type: string;
  [key: string]: unknown;
}

export interface RichCard {
  schema_version?: number;
  title?: string;
  blocks: CardBlock[];
}

export interface RichCardHandlers {
  /** Opens an external link (http/https); called only from a click. */
  openLink: (url: string) => void;
  /** Maps a card image URL to something the renderer can load, or null to skip the block. */
  resolveImage: (url: string) => string | null;
  /** Sends the value as the next user message (same path as a typed message). */
  send: (value: string) => void;
}

export interface RichCardHandle {
  root: HTMLElement;
  /** Disables every "answer the card" control while a turn runs, like the web renderer does. */
  setBusy: (busy: boolean) => void;
}

const TONES = ['neutral', 'info', 'success', 'warning', 'danger'];
const MAX_VISIBLE_TABLE_ROWS = 100;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;
const warnedBlockTypes = new Set<string>();

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function scalarText(value: unknown): string {
  if (value === null || value === undefined) return '';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function toneClass(tone: unknown): string {
  return typeof tone === 'string' && TONES.includes(tone) ? `tone-${tone}` : '';
}

/** Parses the `card` field (JSON text, or an already-parsed object) into a renderable card. */
export function parseRichCard(raw: unknown): RichCard | null {
  let value = raw;
  if (typeof raw === 'string') {
    if (!raw.trim()) return null;
    try {
      value = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  const card = record(value);
  if (!card) return null;
  const blocks = Array.isArray(card.blocks)
    ? card.blocks.map(record).filter((block): block is Record<string, unknown> => block !== null && typeof block.type === 'string')
    : [];
  if (blocks.length === 0) return null;
  return {
    schema_version: typeof card.schema_version === 'number' ? card.schema_version : undefined,
    title: str(card.title),
    blocks: blocks as CardBlock[],
  };
}

interface FormOption {
  label: string;
  value: string;
}

interface FormFieldSpec {
  name: string;
  label: string;
  type: string;
  required: boolean;
  placeholder?: string;
  hint?: string;
  options: FormOption[];
  min?: number;
  max?: number;
}

type FormValue = string | boolean | string[];

export interface FormPayload {
  /** Omitted keys mean "the user left this optional field empty". */
  payload: Record<string, string | number | boolean | string[]>;
  /** Field name -> message; non-empty means the form cannot be submitted. */
  errors: Record<string, string>;
}

function formOptions(raw: unknown): FormOption[] {
  if (!Array.isArray(raw)) return [];
  const options: FormOption[] = [];
  for (const item of raw) {
    if (typeof item === 'string') {
      if (item) options.push({ label: item, value: item });
      continue;
    }
    const option = record(item);
    const label = option ? str(option.label) : undefined;
    if (!option || !label) continue;
    options.push({ label, value: str(option.value) ?? label });
  }
  return options;
}

export function formFieldSpecs(block: CardBlock): FormFieldSpec[] {
  const fields = Array.isArray(block.fields) ? block.fields : [];
  const specs: FormFieldSpec[] = [];
  for (const raw of fields) {
    const field = record(raw);
    const name = field ? str(field.name) : undefined;
    if (!field || !name) continue;
    specs.push({
      name,
      label: str(field.label) ?? name,
      type: str(field.type) ?? 'text',
      required: field.required === true,
      placeholder: str(field.placeholder),
      hint: str(field.hint),
      options: formOptions(field.options),
      min: typeof field.min === 'number' ? field.min : undefined,
      max: typeof field.max === 'number' ? field.max : undefined,
    });
  }
  return specs;
}

function textValue(value: FormValue | undefined): string {
  return typeof value === 'string' ? value : '';
}

function listValue(value: FormValue | undefined): string[] {
  return Array.isArray(value) ? value : [];
}

/** Validates and serializes form values; the JSON object is what the submit sends as a user message. */
export function buildFormPayload(specs: FormFieldSpec[], values: Record<string, FormValue>): FormPayload {
  const payload: Record<string, string | number | boolean | string[]> = {};
  const errors: Record<string, string> = {};
  for (const field of specs) {
    const raw = values[field.name];
    const fail = (message: string): void => { errors[field.name] = message; };

    if (field.type === 'checkbox') {
      if (field.required && raw !== true) {
        fail('Required');
        continue;
      }
      payload[field.name] = raw === true;
      continue;
    }

    if (field.type === 'multiselect' || field.type === 'checkboxes') {
      const selected = listValue(raw).filter(value => field.options.some(option => option.value === value));
      if (selected.length === 0) {
        if (field.required) fail('Choose at least one');
        continue;
      }
      payload[field.name] = selected;
      continue;
    }

    const text = textValue(raw);
    if (!text.trim()) {
      if (field.required) fail('Required');
      continue;
    }

    if (field.type === 'number') {
      const number = Number(text);
      if (!Number.isFinite(number)) {
        fail('Enter a number');
        continue;
      }
      if (field.min !== undefined && number < field.min) {
        fail(`Must be at least ${field.min}`);
        continue;
      }
      if (field.max !== undefined && number > field.max) {
        fail(`Must be at most ${field.max}`);
        continue;
      }
      payload[field.name] = number;
      continue;
    }

    if (field.type === 'select' || field.type === 'radio') {
      if (!field.options.some(option => option.value === text)) {
        fail('Choose one of the options');
        continue;
      }
      payload[field.name] = text;
      continue;
    }

    if (field.type === 'email') {
      if (!EMAIL_PATTERN.test(text.trim())) {
        fail('Enter a valid email');
        continue;
      }
      payload[field.name] = text.trim();
      continue;
    }

    if (field.type === 'url') {
      try {
        const url = new URL(text.trim());
        if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('unsupported protocol');
      } catch {
        fail('Enter a valid http(s) URL');
        continue;
      }
      payload[field.name] = text.trim();
      continue;
    }

    if (field.type === 'date') {
      if (!DATE_PATTERN.test(text) || Number.isNaN(Date.parse(`${text}T00:00:00`))) {
        fail('Enter a valid date');
        continue;
      }
    }
    if (field.type === 'time' && !TIME_PATTERN.test(text)) {
      fail('Enter a valid time');
      continue;
    }
    if (field.type === 'datetime' && Number.isNaN(Date.parse(text))) {
      fail('Enter a valid date and time');
      continue;
    }
    payload[field.name] = text;
  }
  return { payload, errors };
}

function renderText(block: CardBlock): HTMLElement | null {
  const text = str(block.text);
  if (!text) return null;
  return el('div', `rich-card-text ${toneClass(block.tone)}`.trim(), text);
}

function renderKeyValues(block: CardBlock): HTMLElement | null {
  const items = Array.isArray(block.items) ? block.items : [];
  const wrapper = el('div', 'rich-card-kv');
  for (const raw of items) {
    const item = record(raw);
    if (!item) continue;
    const row = el('div', 'rich-card-kv-row');
    row.append(el('span', 'rich-card-kv-label', str(item.label) ?? ''));
    row.append(el('span', `rich-card-kv-value ${toneClass(item.tone)}`.trim(), scalarText(item.value)));
    wrapper.append(row);
  }
  return wrapper.childElementCount ? wrapper : null;
}

function renderTable(block: CardBlock): HTMLElement | null {
  const columns = (Array.isArray(block.columns) ? block.columns : [])
    .map(record)
    .filter((column): column is Record<string, unknown> => column !== null && !!str(column.key));
  const rows = (Array.isArray(block.rows) ? block.rows : [])
    .filter(raw => record(raw) !== null) as Array<Record<string, unknown>>;
  if (!columns.length) return null;
  const wrapper = el('div', 'rich-card-table');
  const scroller = el('div', 'rich-card-table-scroll');
  const table = el('table');
  const head = el('thead');
  const headRow = el('tr');
  for (const column of columns) headRow.append(el('th', undefined, str(column.label) ?? str(column.key) ?? ''));
  head.append(headRow);
  table.append(head);
  const body = el('tbody');
  for (const row of rows.slice(0, MAX_VISIBLE_TABLE_ROWS)) {
    const tr = el('tr');
    for (const column of columns) {
      tr.append(el('td', undefined, scalarText(row[str(column.key) ?? ''])));
    }
    body.append(tr);
  }
  table.append(body);
  scroller.append(table);
  wrapper.append(scroller);
  const hidden = rows.length - Math.min(rows.length, MAX_VISIBLE_TABLE_ROWS);
  if (hidden > 0) wrapper.append(el('div', 'rich-card-more', `… ${hidden} more rows`));
  const caption = str(block.caption);
  if (caption) wrapper.append(el('div', 'rich-card-caption', caption));
  return wrapper;
}

function renderImage(block: CardBlock, handlers: RichCardHandlers): HTMLElement | null {
  const url = str(block.url);
  if (!url) return null;
  const src = handlers.resolveImage(url);
  if (!src) return null;
  const wrapper = el('div', 'rich-card-image');
  const image = el('img');
  image.src = src;
  image.alt = str(block.alt) ?? 'card image';
  image.loading = 'lazy';
  image.onerror = () => wrapper.remove();
  wrapper.append(image);
  const title = str(block.title);
  if (title) wrapper.append(el('div', 'rich-card-caption', title));
  return wrapper;
}

function renderActions(block: CardBlock, handlers: RichCardHandlers, busyHooks: Array<(busy: boolean) => void>): HTMLElement | null {
  const options = (Array.isArray(block.options) ? block.options : []).map(record);
  const row = el('div', 'rich-card-actions');
  for (const option of options) {
    if (!option) continue;
    const label = str(option.label);
    if (!label) continue;
    const url = str(option.url);
    const button = el('button', `rich-card-button ${toneClass(option.tone)}`.trim());
    button.type = 'button';
    button.textContent = label;
    const description = str(option.description);
    if (description) button.title = description;
    if (url) {
      // A link button never sends a message and stays clickable while a turn runs.
      button.classList.add('external');
      button.onclick = () => handlers.openLink(url);
    } else {
      const value = str(option.value) ?? label;
      button.onclick = () => handlers.send(value);
      busyHooks.push(busy => { button.disabled = busy; });
    }
    row.append(button);
  }
  return row.childElementCount ? row : null;
}

function renderForm(block: CardBlock, handlers: RichCardHandlers, busyHooks: Array<(busy: boolean) => void>): HTMLElement | null {
  const specs = formFieldSpecs(block);
  if (!specs.length) return null;
  const form = el('form', 'rich-card-form');
  form.noValidate = true;
  const values: Record<string, FormValue> = {};
  const controls: Array<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement> = [];
  const errorNodes = new Map<string, HTMLElement>();
  const idPrefix = `rc${Math.random().toString(36).slice(2)}`;
  let submitted = false;

  const setValue = (name: string, value: FormValue): void => {
    values[name] = value;
    const error = errorNodes.get(name);
    if (error) error.textContent = '';
  };

  const applyDisabled = (busy: boolean): void => {
    const locked = busy || submitted;
    for (const control of controls) control.disabled = locked;
    const submit = form.querySelector('button[type="submit"]') as HTMLButtonElement | null;
    if (submit) submit.disabled = locked;
  };

  const renderControl = (field: FormFieldSpec, inputId: string): HTMLElement => {
    const raw = values[field.name];
    const text = textValue(raw);
    const selected = listValue(raw);
    switch (field.type) {
      case 'textarea': {
        const area = el('textarea', 'rich-card-input');
        area.rows = 3;
        area.id = inputId;
        area.placeholder = field.placeholder ?? '';
        area.value = text;
        area.oninput = () => setValue(field.name, area.value);
        controls.push(area);
        return area;
      }
      case 'select': {
        const select = el('select', 'rich-card-input');
        select.id = inputId;
        const empty = el('option', undefined, '— select —');
        empty.value = '';
        select.append(empty);
        for (const option of field.options) {
          const node = el('option', undefined, option.label);
          node.value = option.value;
          select.append(node);
        }
        select.value = text;
        select.onchange = () => setValue(field.name, select.value);
        controls.push(select);
        return select;
      }
      case 'multiselect': {
        const select = el('select', 'rich-card-input');
        select.id = inputId;
        select.multiple = true;
        select.size = Math.min(Math.max(field.options.length, 2), 5);
        for (const option of field.options) {
          const node = el('option', undefined, option.label);
          node.value = option.value;
          node.selected = selected.includes(option.value);
          select.append(node);
        }
        select.onchange = () => setValue(field.name, Array.from(select.selectedOptions, option => option.value));
        controls.push(select);
        return select;
      }
      case 'radio': {
        const group = el('div', 'rich-card-choice-group');
        for (const option of field.options) {
          const label = el('label', 'rich-card-choice');
          const input = el('input');
          input.type = 'radio';
          input.name = inputId;
          input.value = option.value;
          input.checked = text === option.value;
          input.onchange = () => setValue(field.name, option.value);
          controls.push(input);
          label.append(input, el('span', undefined, option.label));
          group.append(label);
        }
        return group;
      }
      case 'checkbox': {
        const input = el('input');
        input.type = 'checkbox';
        input.id = inputId;
        input.checked = raw === true;
        input.onchange = () => setValue(field.name, input.checked);
        controls.push(input);
        return input;
      }
      case 'checkboxes': {
        const group = el('div', 'rich-card-choice-group');
        for (const option of field.options) {
          const label = el('label', 'rich-card-choice');
          const input = el('input');
          input.type = 'checkbox';
          input.checked = selected.includes(option.value);
          input.onchange = () => setValue(field.name, input.checked
            ? [...listValue(values[field.name]), option.value]
            : listValue(values[field.name]).filter(value => value !== option.value));
          controls.push(input);
          label.append(input, el('span', undefined, option.label));
          group.append(label);
        }
        return group;
      }
      case 'date':
      case 'time':
      case 'datetime': {
        const input = el('input', 'rich-card-input');
        input.type = field.type === 'datetime' ? 'datetime-local' : field.type;
        input.id = inputId;
        input.value = text;
        input.oninput = () => setValue(field.name, input.value);
        controls.push(input);
        return input;
      }
      default: {
        const input = el('input', 'rich-card-input');
        input.type = field.type === 'number' ? 'number' : field.type === 'email' ? 'email' : field.type === 'url' ? 'url' : 'text';
        input.id = inputId;
        input.placeholder = field.placeholder ?? '';
        input.value = text;
        if (field.type === 'number') {
          if (field.min !== undefined) input.min = String(field.min);
          if (field.max !== undefined) input.max = String(field.max);
          input.step = 'any';
        }
        input.oninput = () => setValue(field.name, input.value);
        controls.push(input);
        return input;
      }
    }
  };

  for (const field of specs) {
    const inputId = `${idPrefix}-${field.name}`;
    const wrapper = el('div', 'rich-card-field');
    const grouped = field.type === 'radio' || field.type === 'checkboxes';
    // A single checkbox wraps its own input in the row label, so this text must not be a nested label.
    const label = field.type === 'checkbox' ? el('span', 'rich-card-label')
      : grouped ? el('div', 'rich-card-label') : el('label', 'rich-card-label');
    if (field.type !== 'checkbox' && !grouped) (label as HTMLLabelElement).htmlFor = inputId;
    label.append(el('span', undefined, field.label));
    if (field.required) label.append(el('span', 'required', ' *'));
    const control = renderControl(field, inputId);
    if (field.type === 'checkbox') {
      const row = el('label', 'rich-card-choice');
      row.append(control, label);
      wrapper.append(row);
    } else if (grouped) {
      wrapper.append(label, control);
    } else {
      wrapper.append(label, control);
    }
    if (field.hint) wrapper.append(el('div', 'rich-card-hint', field.hint));
    const error = el('div', 'rich-card-error');
    errorNodes.set(field.name, error);
    wrapper.append(error);
    form.append(wrapper);
  }

  const footer = el('div', 'rich-card-form-footer');
  const submit = el('button', 'rich-card-button primary');
  submit.type = 'submit';
  submit.textContent = str(block.submit_label) ?? 'Submit';
  const status = el('span', 'rich-card-submitted');
  footer.append(submit, status);
  form.append(footer);

  form.onsubmit = (event) => {
    event.preventDefault();
    if (submitted) return;
    const result = buildFormPayload(specs, values);
    for (const [name, message] of Object.entries(result.errors)) {
      const node = errorNodes.get(name);
      if (node) node.textContent = message;
    }
    if (Object.keys(result.errors).length > 0) return;
    submitted = true;
    status.textContent = 'Submitted';
    applyDisabled(false);
    handlers.send(JSON.stringify(result.payload));
  };

  busyHooks.push(applyDisabled);
  return form;
}

function renderBlock(block: CardBlock, handlers: RichCardHandlers, busyHooks: Array<(busy: boolean) => void>): HTMLElement | null {
  switch (block.type) {
    case 'text':
      return renderText(block);
    case 'key_values':
      return renderKeyValues(block);
    case 'table':
      return renderTable(block);
    case 'image':
      return renderImage(block, handlers);
    case 'actions':
      return renderActions(block, handlers, busyHooks);
    case 'form':
      return renderForm(block, handlers, busyHooks);
    case 'divider':
      return el('hr', 'rich-card-divider');
    default:
      if (!warnedBlockTypes.has(block.type)) {
        warnedBlockTypes.add(block.type);
        console.warn(`[rich-card] unsupported block type "${block.type}" skipped`);
      }
      return null;
  }
}

/**
 * Builds the card's DOM. Returns null when nothing in the card can be drawn, so a card made only of
 * blocks this client does not know leaves no empty shell in the transcript.
 */
export function createRichCard(card: RichCard, handlers: RichCardHandlers, busy: boolean): RichCardHandle | null {
  const root = el('div', 'rich-card');
  if (card.title) root.append(el('div', 'rich-card-title', card.title));
  const busyHooks: Array<(busy: boolean) => void> = [];
  let rendered = 0;
  for (const block of card.blocks) {
    const node = renderBlock(block, handlers, busyHooks);
    if (!node) continue;
    root.append(node);
    rendered++;
  }
  if (rendered === 0) return null;
  const handle: RichCardHandle = {
    root,
    setBusy: (value: boolean) => { for (const hook of busyHooks) hook(value); },
  };
  handle.setBusy(busy);
  return handle;
}
