// Shared color palettes for Type/Source badges
// Keep synced with design doc

export interface TypePalette {
  label: string;
  dot: string;
  bg: string;
  text: string;
}

export interface SourcePalette {
  label: string;
  color: string;
}

export function typeColors(type?: string): TypePalette {
  switch (type) {
    case 'agent':
      return { label: 'Agent', dot: '#6366f1', bg: 'rgba(99, 102, 241, 0.12)', text: '#4f46e5' };
    case 'llm_call':
      return { label: 'LLM Call', dot: '#0891b2', bg: 'rgba(8, 145, 178, 0.12)', text: '#0e7490' };
    case 'external':
      return { label: 'External', dot: '#64748b', bg: 'rgba(100, 116, 139, 0.12)', text: '#475569' };
    default:
      return { label: 'Unknown', dot: '#94a3b8', bg: 'rgba(148, 163, 184, 0.12)', text: '#64748b' };
  }
}

export function sourceColors(source?: string): SourcePalette {
  switch (source) {
    case 'chat':     return { label: 'Chat',      color: '#3b82f6' };
    case 'test':     return { label: 'Test',      color: '#8b5cf6' };
    case 'api':      return { label: 'API',       color: '#f59e0b' };
    case 'a2a':      return { label: 'A2A',       color: '#ec4899' };
    case 'scheduled':return { label: 'Scheduled', color: '#eab308' };
    case 'llm_test': return { label: 'LLM Test',  color: '#8b5cf6' };
    case 'llm_api':  return { label: 'LLM API',   color: '#f59e0b' };
    case 'external': return { label: 'External',  color: '#64748b' };
    default:         return { label: 'Unknown',   color: '#94a3b8' };
  }
}
