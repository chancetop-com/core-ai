import { useId, useState, type ReactNode } from 'react';
import { ExternalLink } from 'lucide-react';
import { fetchBlob, needsAuthFetch } from '../../../api/authedBlob';
import type { CardBlock, QuickReplyOption, RichCard } from '../types';
import AuthedImage from './AuthedImage';

const TONE_COLORS: Record<string, string> = {
  neutral: 'var(--color-text-secondary)',
  info: 'var(--color-primary)',
  success: 'var(--color-success)',
  warning: 'var(--color-warning)',
  danger: 'var(--color-danger)',
};
const MAX_VISIBLE_TABLE_ROWS = 100;
const warnedBlockTypes = new Set<string>();

function toneColor(tone: unknown): string | undefined {
  return typeof tone === 'string' && tone in TONE_COLORS ? TONE_COLORS[tone] : undefined;
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function scalarText(value: unknown): string {
  if (value === null || value === undefined) return '';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/**
 * Opens a card link in a new tab. Only ever called from a user click — nothing here runs on render.
 * Platform file URLs need the bearer token, which a fresh tab cannot carry, so the content is fetched
 * with auth first and loaded into the tab that the click already opened.
 */
function openLink(url: string) {
  if (!needsAuthFetch(url)) {
    window.open(url, '_blank', 'noopener,noreferrer');
    return;
  }
  const tab = window.open('', '_blank');
  fetchBlob(url).then(blob => {
    const blobUrl = URL.createObjectURL(blob);
    if (tab) tab.location.href = blobUrl;
    else window.open(blobUrl, '_blank');
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
  }).catch(err => {
    if (tab) tab.close();
    console.warn('[rich-card] failed to open authenticated link', err);
  });
}

/**
 * Quick-reply buttons for the platform-standard quick_replies event (present_choices tool):
 * clicking one immediately sends its value as the next user message.
 */
export function QuickReplyButtons({ question, options, disabled, onSelect }: {
  question?: string;
  options: QuickReplyOption[];
  disabled: boolean;
  onSelect: (value: string) => void;
}) {
  return (
    <div className="rounded-xl border px-4 py-3"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      {question && <div className="text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>{question}</div>}
      <div className="flex flex-wrap gap-2">
        {options.map((option, idx) => (
          <button key={`${idx}-${option.label}`} type="button" disabled={disabled}
            onClick={() => onSelect(option.value)}
            title={option.description}
            className="px-3 py-1.5 rounded-full text-xs font-medium cursor-pointer hover:opacity-80 disabled:opacity-40 disabled:cursor-not-allowed"
            style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)', color: 'var(--color-text)' }}>
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
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

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

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

function formFieldSpecs(block: CardBlock): FormFieldSpec[] {
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

/**
 * Form block: collects values locally, then submits them the same way a quick-reply click does —
 * as the next user message, whose text is the JSON object of field values.
 */
function FormBlockView({ block, disabled, onSubmit }: {
  block: CardBlock;
  disabled: boolean;
  onSubmit: (value: string) => void;
}) {
  const fields = formFieldSpecs(block);
  const idPrefix = useId();
  const [values, setValues] = useState<Record<string, FormValue>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitted, setSubmitted] = useState(false);
  const locked = disabled || submitted;

  const setValue = (name: string, value: FormValue) => {
    setValues(prev => ({ ...prev, [name]: value }));
    setErrors(prev => {
      if (!(name in prev)) return prev;
      const next = { ...prev };
      delete next[name];
      return next;
    });
  };

  const submit = () => {
    const payload: Record<string, unknown> = {};
    const nextErrors: Record<string, string> = {};
    for (const field of fields) {
      const raw = values[field.name];
      const fail = (message: string) => { nextErrors[field.name] = message; };

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
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors);
      return;
    }
    setErrors({});
    setSubmitted(true);
    onSubmit(JSON.stringify(payload));
  };

  const inputStyle = {
    background: 'var(--color-bg)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  const renderControl = (field: FormFieldSpec, inputId: string): ReactNode => {
    const raw = values[field.name];
    const text = textValue(raw);
    const selected = listValue(raw);

    switch (field.type) {
      case 'textarea':
        return (
          <textarea id={inputId} rows={3} value={text} placeholder={field.placeholder} disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm resize-y" style={inputStyle} />
        );
      case 'select':
        return (
          <select id={inputId} value={text} disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle}>
            <option value="">— select —</option>
            {field.options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        );
      case 'multiselect':
        return (
          <select id={inputId} multiple size={Math.min(Math.max(field.options.length, 2), 5)} value={selected} disabled={locked}
            onChange={event => setValue(field.name, Array.from(event.target.selectedOptions, option => option.value))}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle}>
            {field.options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        );
      case 'radio':
        return (
          <div className="flex flex-col gap-1" role="radiogroup" aria-label={field.label}>
            {field.options.map(option => (
              <label key={option.value} className="flex items-center gap-2 text-sm" style={{ color: 'var(--color-text)' }}>
                <input type="radio" name={inputId} value={option.value} checked={text === option.value} disabled={locked}
                  onChange={() => setValue(field.name, option.value)} />
                {option.label}
              </label>
            ))}
          </div>
        );
      case 'checkbox':
        return (
          <input id={inputId} type="checkbox" checked={raw === true} disabled={locked}
            onChange={event => setValue(field.name, event.target.checked)} />
        );
      case 'checkboxes':
        return (
          <div className="flex flex-col gap-1" role="group" aria-label={field.label}>
            {field.options.map(option => (
              <label key={option.value} className="flex items-center gap-2 text-sm" style={{ color: 'var(--color-text)' }}>
                <input type="checkbox" checked={selected.includes(option.value)} disabled={locked}
                  onChange={event => setValue(field.name, event.target.checked
                    ? [...selected, option.value]
                    : selected.filter(value => value !== option.value))} />
                {option.label}
              </label>
            ))}
          </div>
        );
      case 'date':
        return (
          <input id={inputId} type="date" value={text} disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle} />
        );
      case 'time':
        return (
          <input id={inputId} type="time" value={text} disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle} />
        );
      case 'datetime':
        return (
          <input id={inputId} type="datetime-local" value={text} disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle} />
        );
      default: {
        const inputType = field.type === 'number' ? 'number' : field.type === 'email' ? 'email' : field.type === 'url' ? 'url' : 'text';
        return (
          <input id={inputId} type={inputType} value={text} placeholder={field.placeholder}
            min={field.type === 'number' ? field.min : undefined}
            max={field.type === 'number' ? field.max : undefined}
            step={field.type === 'number' ? 'any' : undefined}
            disabled={locked}
            onChange={event => setValue(field.name, event.target.value)}
            className="rounded-lg border px-2 py-1.5 text-sm" style={inputStyle} />
        );
      }
    }
  };

  return (
    <form noValidate className="flex flex-col gap-2"
      onSubmit={event => { event.preventDefault(); if (!locked) submit(); }}>
      {fields.map(field => {
        const inputId = `${idPrefix}-${field.name}`;
        const labelText = (
          <>
            {field.label}
            {field.required && <span style={{ color: 'var(--color-danger)' }}> *</span>}
          </>
        );
        const grouped = field.type === 'radio' || field.type === 'checkboxes';
        const control = renderControl(field, inputId);
        return (
          <div key={field.name} className="flex flex-col gap-1">
            {field.type === 'checkbox' ? (
              <label htmlFor={inputId} className="flex items-center gap-2 text-xs"
                style={{ color: 'var(--color-text-secondary)' }}>
                {control}
                <span>{labelText}</span>
              </label>
            ) : grouped ? (
              <>
                <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{labelText}</div>
                {control}
              </>
            ) : (
              <>
                <label htmlFor={inputId} className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {labelText}
                </label>
                {control}
              </>
            )}
            {field.hint && (
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{field.hint}</div>
            )}
            {errors[field.name] && (
              <div className="text-xs" style={{ color: 'var(--color-danger)' }}>{errors[field.name]}</div>
            )}
          </div>
        );
      })}
      <div className="flex items-center gap-2">
        <button type="submit" disabled={locked}
          className="px-3 py-1.5 rounded-full text-xs font-medium cursor-pointer hover:opacity-80 disabled:opacity-40 disabled:cursor-not-allowed"
          style={{ background: 'var(--color-primary)', border: 'none', color: 'white' }}>
          {str(block.submit_label) ?? 'Submit'}
        </button>
        {submitted && <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Submitted</span>}
      </div>
    </form>
  );
}

function CardBlockView({ block, disabled, onAction }: {
  block: CardBlock;
  disabled: boolean;
  onAction: (value: string) => void;
}) {
  switch (block.type) {
    case 'text': {
      const text = str(block.text) ?? '';
      if (!text) return null;
      return <div className="text-sm whitespace-pre-wrap" style={{ color: toneColor(block.tone) ?? 'var(--color-text)' }}>{text}</div>;
    }
    case 'key_values': {
      const items = Array.isArray(block.items) ? block.items : [];
      return (
        <div className="flex flex-col gap-1">
          {items.map((raw, idx) => {
            const item = record(raw);
            if (!item) return null;
            return (
              <div key={idx} className="flex gap-3 text-sm">
                <span className="shrink-0 min-w-[96px]" style={{ color: 'var(--color-text-secondary)' }}>{str(item.label) ?? ''}</span>
                <span className="whitespace-pre-wrap" style={{ color: toneColor(item.tone) ?? 'var(--color-text)' }}>{scalarText(item.value)}</span>
              </div>
            );
          })}
        </div>
      );
    }
    case 'table': {
      const columns = (Array.isArray(block.columns) ? block.columns : []).map(record).filter((c): c is Record<string, unknown> => c !== null);
      const rows = (Array.isArray(block.rows) ? block.rows : []).filter(raw => record(raw) !== null);
      const visibleRows = rows.slice(0, MAX_VISIBLE_TABLE_ROWS);
      const hidden = rows.length - visibleRows.length;
      return (
        <div className="flex flex-col gap-1">
          <div className="overflow-x-auto">
            <table className="border-collapse text-sm">
              <thead>
                <tr>
                  {columns.map((column, idx) => (
                    <th key={idx} className="border px-2 py-1 text-left font-medium"
                      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>{str(column.label) ?? ''}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {visibleRows.map((raw, idx) => {
                  const row = record(raw) ?? {};
                  return (
                    <tr key={idx}>
                      {columns.map((column, cidx) => (
                        <td key={cidx} className="border px-2 py-1 align-top" style={{ borderColor: 'var(--color-border)' }}>
                          {scalarText(row[str(column.key) ?? ''])}
                        </td>
                      ))}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {hidden > 0 && (
            <div className="text-xs" style={{ color: 'var(--color-text-muted)' }}>… {hidden} more rows</div>
          )}
          {str(block.caption) && <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{str(block.caption)}</div>}
        </div>
      );
    }
    case 'image': {
      const url = str(block.url);
      if (!url) return null;
      return (
        <div className="flex flex-col gap-1">
          <AuthedImage src={url} alt={str(block.alt) ?? 'card image'} className="max-w-full rounded-lg border" />
          {str(block.title) && <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{str(block.title)}</div>}
        </div>
      );
    }
    case 'actions': {
      const options = (Array.isArray(block.options) ? block.options : []).map(record).filter((o): o is Record<string, unknown> => o !== null);
      return (
        <div className="flex flex-wrap gap-2">
          {options.map((option, idx) => {
            const label = str(option.label);
            if (!label) return null;
            const url = str(option.url);
            if (url) {
              return (
                <button key={idx} type="button" onClick={() => openLink(url)}
                  title={str(option.description)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium cursor-pointer hover:opacity-80"
                  style={{ background: toneColor(option.tone) ?? 'var(--color-primary)', border: 'none', color: 'white' }}>
                  {label}
                  <ExternalLink size={12} />
                </button>
              );
            }
            return (
              <button key={idx} type="button" disabled={disabled}
                onClick={() => onAction(str(option.value) ?? label)}
                title={str(option.description)}
                className="px-3 py-1.5 rounded-full text-xs font-medium cursor-pointer hover:opacity-80 disabled:opacity-40 disabled:cursor-not-allowed"
                style={{ background: toneColor(option.tone) ?? 'var(--color-primary)', border: 'none', color: 'white' }}>
                {label}
              </button>
            );
          })}
        </div>
      );
    }
    case 'form':
      return <FormBlockView block={block} disabled={disabled} onSubmit={onAction} />;
    case 'divider':
      return <hr className="border-0 border-t m-0" style={{ borderColor: 'var(--color-border)' }} />;
    default:
      if (!warnedBlockTypes.has(block.type)) {
        warnedBlockTypes.add(block.type);
        console.warn(`[rich-card] unsupported block type "${block.type}" skipped`);
      }
      return null;
  }
}

/**
 * Generic renderer for the platform card companion field. Unknown blocks are skipped so a client that
 * lags behind a newer vocabulary still renders the rest of the card.
 */
export function RichCardView({ card, disabled, onAction }: {
  card: RichCard;
  disabled: boolean;
  onAction: (value: string) => void;
}) {
  return (
    <div className="rounded-xl border px-4 py-3 flex flex-col gap-3"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      {card.title && <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>{card.title}</div>}
      {card.blocks.map((block, idx) => (
        <CardBlockView key={idx} block={block} disabled={disabled} onAction={onAction} />
      ))}
    </div>
  );
}
