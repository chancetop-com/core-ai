import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { agentVersionApi } from '../../api/comparison';
import AgentEditor from '../agents/AgentEditor';

/**
 * Version editing reuses the agent editor UI itself (AgentEditor in version mode): same sections and
 * controls, with agent-only actions (publish/export/import/delete/test/runs) hidden and Save writing
 * to the saved version. This route only resolves/creates the version and hands the id over.
 *
 * @author stephen
 */
export default function AgentVersionEditor() {
  const { versionId: versionIdParam } = useParams<{ versionId: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const creatingRef = useRef(false);
  const [versionId, setVersionId] = useState<string | null>(versionIdParam && versionIdParam !== 'new' ? versionIdParam : null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (versionIdParam !== 'new') {
      if (versionIdParam) setVersionId(versionIdParam);
      return;
    }
    if (creatingRef.current) return;
    creatingRef.current = true;
    const agentId = searchParams.get('agent');
    if (!agentId) {
      setError('缺少 agent 参数');
      return;
    }
    agentVersionApi.create({ agent_id: agentId })
      .then(version => {
        setVersionId(version.id);
        navigate(`/experiments/agent-comparison/versions/${version.id}`, { replace: true });
      })
      .catch(err => setError(err instanceof Error ? err.message : String(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versionIdParam]);

  if (error) {
    return (
      <div className="mx-auto max-w-4xl p-6">
        <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
      </div>
    );
  }
  if (!versionId) {
    return (
      <div className="flex h-full items-center justify-center py-12">
        <Loader2 className="animate-spin" size={18} style={{ color: 'var(--color-text-secondary)' }} />
      </div>
    );
  }
  return <AgentEditor versionId={versionId} />;
}
