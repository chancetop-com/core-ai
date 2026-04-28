import { useState } from 'react';
import { Copy, Check } from 'lucide-react';

export default function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.warn('copy failed', err);
    }
  };
  return (
    <button onClick={handleCopy}
      className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer transition-opacity hover:opacity-100"
      style={{
        color: copied ? 'var(--color-success)' : 'var(--color-text-secondary)',
        background: 'var(--color-bg-tertiary)',
        border: '1px solid var(--color-border)',
      }}
      title={copied ? 'Copied' : 'Copy message'}>
      {copied ? <Check size={12} /> : <Copy size={12} />}
      <span>{copied ? 'Copied' : 'Copy'}</span>
    </button>
  );
}
