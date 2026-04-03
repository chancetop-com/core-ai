import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Bot, User, Wrench, Settings, Zap, Clock, Copy, Check } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentRunDetail, AgentDefinition } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

export default function RunDetail() {
  const { id: runId } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [run, setRun] = useState<AgentRunDetail | null>(null);
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [loading, setLoading] = useState(true);
  const [copied, setCopied] = useState('');

  useEffect(() => {
    if (!runId) return;
    api.agents.getRun(runId).then(r => {
      setRun(r);
      if (r.agent_id) {
        api.agents.get(r.agent_id).then(setAgent).catch(console.error);
      }
    }).catch(console.error).finally(() => setLoading(false));
  }, [runId]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!run) return <div className="p-6">Run not found</div>;

  const duration = run.started_at && run.completed_at
    ? new Date(run.completed_at).getTime() - new Date(run.started_at).getTime()
    : null;

  const inputTokens = run.token_usage?.input || 0;
  const outputTokens = run.token_usage?.output || 0;

  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  const roleIcon = (role: string) => {
    switch (role) {
      case 'system': return <Settings size={16} style={{ color: '#8b5cf6' }} />;
      case 'user': return <User size={16} style={{ color: '#3b82f6' }} />;
      case 'assistant': return <Bot size={16} style={{ color: '#22c55e' }} />;
      case 'tool': return <Wrench size={16} style={{ color: '#f59e0b' }} />;
      default: return <Bot size={16} style={{ color: 'var(--color-text-secondary)' }} />;
    }
  };

  const roleColor = (role: string) => {
    switch (role) {
      case 'system': return '#8b5cf6';
      case 'user': return '#3b82f6';
      case 'assistant': return '#22c55e';
      case 'tool': return '#f59e0b';
      default: return 'var(--color-text-secondary)';
    }
  };

  return (
    <div className="p-6">
      <button onClick={() => agent ? navigate(`/agents/${agent.id}`) : navigate('/agents')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Agent
      </button>

      {/* Run Header */}
      <div className="rounded-xl border p-5 mb-6"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-semibold">Run Detail</h1>
            <StatusBadge status={run.status} />
            <span className="px-2 py-0.5 rounded text-xs"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              {run.triggered_by}
            </span>
          </div>
          <span className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>{run.id}</span>
        </div>

        <div className="flex gap-6 text-sm flex-wrap" style={{ color: 'var(--color-text-secondary)' }}>
          {duration !== null && (
            <span className="flex items-center gap-1">
              <Clock size={14} /> {duration < 1000 ? `${duration}ms` : `${(duration / 1000).toFixed(2)}s`}
            </span>
          )}
          <span className="flex items-center gap-1">
            <Zap size={14} />
            {inputTokens + outputTokens} tokens
            <span className="text-xs ml-1">({inputTokens} in / {outputTokens} out)</span>
          </span>
          {run.started_at && <span>Started: {new Date(run.started_at).toLocaleString()}</span>}
        </div>
        {run.error && (
          <div className="mt-2 p-2 rounded-lg text-sm" style={{ background: 'var(--color-error)', color: 'white', opacity: 0.9 }}>
            Error: {run.error}
          </div>
        )}
      </div>

      <div className="grid grid-cols-3 gap-6">
        {/* Left: Transcript */}
        <div className="col-span-2">
          <div className="rounded-xl border overflow-hidden"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="px-4 py-3 border-b font-medium text-sm" style={{ borderColor: 'var(--color-border)' }}>
              Transcript ({run.transcript?.length || 0} messages)
            </div>
            <div className="divide-y" style={{ borderColor: 'var(--color-border)' }}>
              {run.transcript?.map((entry, i) => (
                <div key={i} className="p-4">
                  <div className="flex items-center gap-2 mb-2">
                    {roleIcon(entry.role)}
                    <span className="text-sm font-medium" style={{ color: roleColor(entry.role) }}>
                      {entry.role}
                    </span>
                    {entry.name && (
                      <span className="text-xs font-mono px-2 py-0.5 rounded"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {entry.name}
                      </span>
                    )}
                    {entry.ts && (
                      <span className="text-xs ml-auto" style={{ color: 'var(--color-text-secondary)' }}>
                        {new Date(entry.ts).toLocaleTimeString()}
                      </span>
                    )}
                  </div>

                  {entry.content && (
                    <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg"
                      style={{ background: 'var(--color-bg-tertiary)' }}>
                      {entry.content}
                    </pre>
                  )}

                  {entry.args && (
                    <div className="mt-2">
                      <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Arguments:</span>
                      <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg mt-1 overflow-auto max-h-40"
                        style={{ background: 'var(--color-bg-tertiary)' }}>
                        {tryFormatJson(entry.args)}
                      </pre>
                    </div>
                  )}

                  {entry.result && (
                    <div className="mt-2">
                      <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Result:</span>
                      <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg mt-1 overflow-auto max-h-40"
                        style={{ background: 'var(--color-bg-tertiary)' }}>
                        {tryFormatJson(entry.result)}
                      </pre>
                    </div>
                  )}

                  {entry.status && entry.role === 'tool' && (
                    <span className="inline-block mt-1 text-xs px-2 py-0.5 rounded"
                      style={{
                        background: entry.status === 'success' ? 'rgba(34,197,94,0.1)' : 'rgba(239,68,68,0.1)',
                        color: entry.status === 'success' ? 'var(--color-success)' : 'var(--color-error)',
                      }}>
                      {entry.status}
                    </span>
                  )}
                </div>
              )) || (
                <div className="p-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                  No transcript available
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Right: Agent Info */}
        <div className="space-y-4">
          {agent && (
            <div className="rounded-xl border p-4"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <h3 className="font-medium text-sm mb-3">Agent Config</h3>
              <dl className="text-xs space-y-2" style={{ color: 'var(--color-text-secondary)' }}>
                <div className="flex justify-between"><dt>Name</dt><dd className="font-medium" style={{ color: 'var(--color-text)' }}>{agent.name}</dd></div>
                <div className="flex justify-between"><dt>Type</dt><dd>{agent.type}</dd></div>
                <div className="flex justify-between"><dt>Model</dt><dd className="font-mono">{agent.model || 'default'}</dd></div>
                <div className="flex justify-between"><dt>Temperature</dt><dd>{agent.temperature ?? 'default'}</dd></div>
                <div className="flex justify-between"><dt>Max Turns</dt><dd>{agent.max_turns || '-'}</dd></div>
                <div className="flex justify-between"><dt>Timeout</dt><dd>{agent.timeout_seconds || 600}s</dd></div>
                <div className="flex justify-between"><dt>Status</dt><dd>{agent.status}</dd></div>
              </dl>
            </div>
          )}

          {agent?.tool_ids && agent.tool_ids.length > 0 && (
            <div className="rounded-xl border p-4"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <h3 className="font-medium text-sm mb-3">Tools ({agent.tool_ids.length})</h3>
              <div className="space-y-1">
                {agent.tool_ids.map(t => (
                  <div key={t} className="flex items-center gap-2 text-xs">
                    <Wrench size={12} style={{ color: '#f59e0b' }} />
                    <span className="font-mono">{t}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {agent?.system_prompt && (
            <div className="rounded-xl border p-4"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <h3 className="font-medium text-sm mb-3">System Prompt</h3>
              <pre className="text-xs whitespace-pre-wrap p-3 rounded-lg overflow-auto max-h-48"
                style={{ background: 'var(--color-bg-tertiary)' }}>
                {agent.system_prompt}
              </pre>
            </div>
          )}

          {/* Input / Output summary */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">I/O Summary</h3>
            {run.input && (
              <div className="mb-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Input</span>
                  <button onClick={() => handleCopy(run.input, 'input')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'input' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-32"
                  style={{ background: 'var(--color-bg-tertiary)' }}>
                  {run.input}
                </pre>
              </div>
            )}
            {run.output && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Output</span>
                  <button onClick={() => handleCopy(run.output, 'output')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'output' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-32"
                  style={{ background: 'var(--color-bg-tertiary)' }}>
                  {run.output}
                </pre>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function tryFormatJson(str: string): string {
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}
