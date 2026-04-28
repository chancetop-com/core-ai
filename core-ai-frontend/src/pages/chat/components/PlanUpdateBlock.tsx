import { useState } from 'react';
import { ChevronDown, ChevronRight, ListTodo } from 'lucide-react';
import type { PlanTodo } from '../types';

export default function PlanUpdateBlock({ todos }: { todos: PlanTodo[] }) {
  const [expanded, setExpanded] = useState(true);
  const statusColor = (status: string) => {
    switch (status.toLowerCase()) {
      case 'completed': return 'var(--color-success)';
      case 'in_progress': return 'var(--color-warning)';
      default: return 'var(--color-text-muted)';
    }
  };
  const statusLabel = (status: string) => {
    switch (status.toLowerCase()) {
      case 'completed': return 'Done';
      case 'in_progress': return 'In Progress';
      default: return 'Pending';
    }
  };

  return (
    <div className="mb-3 rounded-xl border text-xs"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      <button onClick={() => setExpanded(e => !e)}
        className="flex items-center gap-1.5 w-full px-3 py-2 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <ListTodo size={14} />
        <span className="font-medium">Planning ({todos.filter(t => t.status.toLowerCase() === 'completed').length}/{todos.length})</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <table className="w-full text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            <thead>
              <tr style={{ color: 'var(--color-text-muted)', borderBottom: '1px solid var(--color-border)' }}>
                <th className="text-left py-1.5 pr-3 font-medium">Status</th>
                <th className="text-left py-1.5 font-medium">Task</th>
              </tr>
            </thead>
            <tbody>
              {todos.map((t, j) => (
                <tr key={j} className="border-b" style={{ borderColor: 'var(--color-border)' }}>
                  <td className="py-1.5 pr-3 whitespace-nowrap">
                    <span style={{ color: statusColor(t.status) }}>
                      {t.status.toLowerCase() === 'completed' ? '\u2713 ' : t.status.toLowerCase() === 'in_progress' ? '\u25B6 ' : '\u25CB '}{statusLabel(t.status)}
                    </span>
                  </td>
                  <td className="py-1.5" style={{ color: 'var(--color-text)' }}>{t.content}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
