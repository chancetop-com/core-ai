import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Bot, Check, ChevronDown, Search, Star } from 'lucide-react';
import type { AgentDefinition } from '../../../api/client';
import { api } from '../../../api/client';

type ChatStatus = 'idle' | 'running';

interface AgentSelectorProps {
  status: ChatStatus;
  myAgents: AgentDefinition[];
  favoriteAgents: AgentDefinition[];
  selectedAgentId: string;
  selectedAgent?: AgentDefinition;
  onSelectAgent: (id: string, agent?: AgentDefinition) => void;
  onToggleFavorite: (id: string) => void;
}

function canChatWithAgent(agent: AgentDefinition): boolean {
  return agent.type !== 'LLM_CALL' && (agent.status === 'PUBLISHED' || agent.type === 'local');
}

const AgentSelector = memo(function AgentSelector({
  status,
  myAgents,
  favoriteAgents,
  selectedAgentId,
  selectedAgent,
  onSelectAgent,
  onToggleFavorite,
}: AgentSelectorProps) {
  const [open, setOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchedAgents, setSearchedAgents] = useState<AgentDefinition[]>([]);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const defaultAgents = useMemo(
    () => myAgents.filter(agent => agent.system_default && canChatWithAgent(agent)),
    [myAgents],
  );
  const ownedAgents = useMemo(
    () => myAgents.filter(agent => !agent.system_default && canChatWithAgent(agent)),
    [myAgents],
  );
  const favorites = useMemo(
    () => favoriteAgents.filter(agent => canChatWithAgent(agent)),
    [favoriteAgents],
  );
  const favoriteIds = useMemo(() => new Set(favoriteAgents.map(agent => agent.id)), [favoriteAgents]);

  // Debounced server-side search for shared agents
  useEffect(() => {
    if (searchQuery.trim().length === 0) {
      setSearchedAgents([]);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      api.agents.list(false, searchQuery.trim(), 20).then(res => {
        if (!cancelled) setSearchedAgents((res.agents || []).filter(a => a.type !== 'LLM_CALL'));
      }).catch(() => {
        if (!cancelled) setSearchedAgents([]);
      });
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [searchQuery]);

  useEffect(() => {
    if (!open) return;

    const handlePointerDown = (event: PointerEvent) => {
      const dropdown = dropdownRef.current;
      const target = event.target as Node | null;
      if (!dropdown || !target) return;
      if (!dropdown.contains(target)) {
        setOpen(false);
        setSearchQuery('');
        setSearchedAgents([]);
      }
    };

    document.addEventListener('pointerdown', handlePointerDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
    };
  }, [open]);

  const handleSelect = (id: string, agent?: AgentDefinition) => {
    onSelectAgent(id, agent);
    setOpen(false);
    setSearchQuery('');
    setSearchedAgents([]);
  };

  const renderAgentRow = (agent: AgentDefinition, icon: 'bot' | 'star', showCreatedBy: boolean, favorited: boolean) => (
    <div key={agent.id}
      className="w-full flex items-center gap-2 px-2 py-1.5 rounded-lg transition-colors"
      style={{
        background: selectedAgentId === agent.id ? 'var(--color-bg-tertiary)' : 'transparent',
      }}
      onMouseEnter={event => { if (selectedAgentId !== agent.id) event.currentTarget.style.background = 'var(--color-bg-secondary)'; }}
      onMouseLeave={event => { if (selectedAgentId !== agent.id) event.currentTarget.style.background = 'transparent'; }}>
      <button
        onClick={() => handleSelect(agent.id, agent)}
        className="flex items-center gap-2 flex-1 min-w-0 text-left text-sm cursor-pointer"
        style={{ color: 'var(--color-text)', background: 'transparent' }}>
        {icon === 'bot' ? (
          <Bot size={14} style={{ color: 'var(--color-primary)' }} />
        ) : (
          <Star size={14} style={{ color: 'var(--color-primary)' }} fill={favorited ? 'var(--color-primary)' : 'none'} />
        )}
        <span className="flex-1 truncate">{agent.name}</span>
        {showCreatedBy && (
          <span className="text-xs truncate max-w-[90px]" style={{ color: 'var(--color-text-muted)' }}>{agent.created_by}</span>
        )}
        {selectedAgentId === agent.id && <Check size={14} style={{ color: 'var(--color-primary)' }} />}
      </button>
      <button
        title={favorited ? 'Remove favorite' : 'Add favorite'}
        className="p-1 rounded cursor-pointer transition-colors shrink-0"
        style={{ color: favorited ? 'var(--color-primary)' : 'var(--color-text-muted)' }}
        onMouseEnter={event => { event.currentTarget.style.color = 'var(--color-primary)'; }}
        onMouseLeave={event => { event.currentTarget.style.color = favorited ? 'var(--color-primary)' : 'var(--color-text-muted)'; }}
        onClick={() => onToggleFavorite(agent.id)}>
        <Star size={14} fill={favorited ? 'currentColor' : 'none'} />
      </button>
    </div>
  );

  return (
    <div className="border-b px-6 py-3 flex items-center justify-between"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center gap-3 min-w-0">
        <div className="relative" ref={dropdownRef}>
          <button
            onClick={() => setOpen(value => !value)}
            disabled={status === 'running'}
            className="flex w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
            <Bot size={14} style={{ color: 'var(--color-primary)' }} />
            <span className="truncate max-w-[160px]">{selectedAgent?.name || 'Select Agent'}</span>
            <ChevronDown size={14} className={open ? 'rotate-180' : ''} />
          </button>

          {open && (
            <div className="absolute left-0 top-11 z-50 w-[380px] rounded-xl border shadow-lg"
              style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
              {favorites.length > 0 && (
                <div className="p-2">
                  <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    <Star size={12} /> Favorites
                  </div>
                  <div className="max-h-[200px] overflow-auto">
                    {favorites.map(agent => renderAgentRow(agent, 'star', true, true))}
                  </div>
                </div>
              )}

              {defaultAgents.length > 0 && (
                <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    <Bot size={12} /> Default
                  </div>
                  <div className="max-h-[200px] overflow-auto">
                    {defaultAgents.map(agent => renderAgentRow(agent, 'bot', false, favoriteIds.has(agent.id)))}
                  </div>
                </div>
              )}

              {ownedAgents.length > 0 && (
                <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    <Bot size={12} /> My Agents
                  </div>
                  <div className="max-h-[200px] overflow-auto">
                    {ownedAgents.map(agent => renderAgentRow(agent, 'bot', false, favoriteIds.has(agent.id)))}
                  </div>
                </div>
              )}

              <div className="p-2 border-t" style={{ borderColor: 'var(--color-border)' }}>
                <div className="flex items-center gap-2 px-2 py-1 text-xs font-medium mb-1"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  <Star size={12} /> Shared Agents
                </div>
                <div className="relative mb-1">
                  <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2"
                    style={{ color: 'var(--color-text-secondary)' }} />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={event => setSearchQuery(event.target.value)}
                    placeholder="Search shared agents..."
                    className="w-full pl-7 pr-2 py-1.5 rounded-lg border text-xs outline-none"
                    style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
                  />
                </div>
                {searchQuery.length > 0 && (
                  <div className="max-h-[200px] overflow-auto">
                    {searchedAgents.length === 0 ? (
                      <div className="text-center py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        No agents found
                      </div>
                    ) : (
                      searchedAgents.map(agent => renderAgentRow(agent, 'star', true, favoriteIds.has(agent.id)))
                    )}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {selectedAgent && (
          <span className="flex-1 min-w-0 text-xs truncate" style={{ color: 'var(--color-text-secondary)' }}>
            {selectedAgent.model || 'default model'}
            {selectedAgent.description && ` · ${selectedAgent.description}`}
          </span>
        )}
      </div>
    </div>
  );
});

export default AgentSelector;
