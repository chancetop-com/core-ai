import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../api/auth';
import AgentQualityList from './quality/AgentQualityList';
import SkillQualityList from './quality/SkillQualityList';

type QualityTab = 'agent' | 'skill';

export default function Quality() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const isAdmin = user?.role === 'admin';
  const tab: QualityTab = params.get('tab') === 'skill' && isAdmin ? 'skill' : 'agent';

  const selectTab = (next: QualityTab) => {
    const nextParams = new URLSearchParams(params);
    if (next === 'skill') nextParams.set('tab', 'skill');
    else nextParams.delete('tab');
    setParams(nextParams, { replace: true });
  };

  return (
    <div className="h-full overflow-auto">
      <div className="px-6 py-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Quality</h2>
            <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Publish rules and enforcement for agent and skill output
            </div>
          </div>
          <div className="flex items-center gap-2">
            <SegmentedButton active={tab === 'agent'} onClick={() => selectTab('agent')}>Agent Quality</SegmentedButton>
            {isAdmin && (
              <SegmentedButton active={tab === 'skill'} onClick={() => selectTab('skill')}>Skill Quality</SegmentedButton>
            )}
          </div>
        </div>
      </div>

      <div className="p-6">
        {tab === 'agent' ? <AgentQualityList /> : <SkillQualityList />}
      </div>
    </div>
  );
}

function SegmentedButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick}
      className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
      style={{
        background: active ? 'var(--color-bg-tertiary)' : 'transparent',
        color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
      }}>
      {children}
    </button>
  );
}
