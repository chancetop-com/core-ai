import { api } from '../../../api/client';
import { qualityApi } from '../../../api/quality';
import QualityRuleEditor from './QualityRuleEditor';

export default function AgentQualityDetail() {
  return (
    <QualityRuleEditor
      api={qualityApi}
      basePath="/settings/quality/agent"
      subject="agent"
      defaultInput="SYSTEM_PROMPT"
      createTitle="New decision rule"
      createSubtitle="Create a DECISION rule draft, test it, then activate it."
      testTitle="Test against an agent"
      keyPlaceholder="PROMPT.OUTPUT_REQUIREMENT_PRESENT"
      note="A passing test only proves this revision on the current sample; BLOCK activation requires tested revisions and budget review. An inactive revision does not affect publishing."
      loadFixtures={async () => {
        const response = await api.agents.listSummaries();
        return (response.agents || []).map(agent => ({ id: agent.id, label: `${agent.name} (${agent.id})` }));
      }}
      testPayload={(fixtureId, sample) => ({
        agent_id: fixtureId,
        sample_name: sample || undefined,
        system_prompt: sample || undefined,
      })}
      canEditDefinition={() => true}
    />
  );
}
