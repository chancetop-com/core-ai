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
