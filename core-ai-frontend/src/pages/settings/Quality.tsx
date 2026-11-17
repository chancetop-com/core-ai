import { useSearchParams } from 'react-router-dom';
import { ClipboardCheck, ShieldCheck } from 'lucide-react';
import { useAuth } from '../../api/auth';
import AgentQualityTab from './quality/AgentQualityTab';
import SkillQualityTab from './quality/SkillQualityTab';

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

  const tabStyle = (active: boolean) => ({
    background: active ? 'var(--color-bg-tertiary)' : 'transparent',
    color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
  });

  return (
    <div className="p-6 max-w-7xl">
      <div className="mb-4">
        <h1 className="text-xl font-semibold">Quality</h1>
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          发布质量规则、enforcement 与测试证据。
        </p>
      </div>

      <div className="flex gap-1 p-1 rounded-lg mb-6 w-fit" style={{ background: 'var(--color-bg-secondary)' }}>
        <button onClick={() => selectTab('agent')}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
          style={tabStyle(tab === 'agent')}>
          <ShieldCheck size={14} />
          Agent Quality
        </button>
        {isAdmin && (
          <button onClick={() => selectTab('skill')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={tabStyle(tab === 'skill')}>
            <ClipboardCheck size={14} />
            Skill Quality
          </button>
        )}
      </div>

      {tab === 'agent' ? <AgentQualityTab /> : <SkillQualityTab />}
    </div>
  );
}
