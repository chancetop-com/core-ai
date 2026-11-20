import { api } from '../../../api/client';
import { skillQualityApi } from '../../../api/quality';
import QualityRuleEditor from './QualityRuleEditor';

export default function SkillQualityDetail() {
  return (
    <QualityRuleEditor
      api={skillQualityApi}
      basePath="/quality/skill"
      subject="skill"
      defaultInput="DESCRIPTION"
      createTitle="New decision rule"
      createSubtitle="Create a DECISION rule over the skill package, test it, then activate it."
      testTitle="Test against a skill"
      keyPlaceholder="SKILL.OUTPUT_SPECIFIC"
      note="A passing test only proves this revision on the current sample; BLOCK activation requires tested revisions and budget review. An inactive revision does not affect publishing."
      loadFixtures={async () => {
        const response = await api.skills.list(undefined, undefined, undefined, undefined, 0, 100);
        return (response.skills || []).map(skill => ({
          id: skill.id,
          label: `${skill.qualified_name} (${skill.id})`,
        }));
      }}
      testPayload={(fixtureId, sample) => ({
        skill_id: fixtureId,
        sample_name: sample || undefined,
        skill_md: sample || undefined,
      })}
      canEditDefinition={rule => !rule.built_in}
    />
  );
}
