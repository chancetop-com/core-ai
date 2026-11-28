package ai.core.server.quality;

import ai.core.api.server.quality.AddQualityExemptionRequest;
import ai.core.api.server.quality.QualityExemptionSubjectListRequest;
import ai.core.api.server.quality.RemoveQualityExemptionRequest;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.skill.SkillStore;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityExemptionAdminServiceTest {
    private final AgentDefinitionStore agentDefinitionStore = mock(AgentDefinitionStore.class);
    private final SkillStore skillStore = mock(SkillStore.class);
    private final QualityPolicyService agentPolicyService = mock(QualityPolicyService.class);
    private final SkillQualityPolicyService skillPolicyService = mock(SkillQualityPolicyService.class);
    private final QualityExemptionAdminService service = new QualityExemptionAdminService();

    @BeforeEach
    void setUp() {
        service.agentDefinitionStore = agentDefinitionStore;
        service.skillStore = skillStore;
        service.agentPolicyService = agentPolicyService;
        service.skillPolicyService = skillPolicyService;
        when(skillPolicyService.findActive(any())).thenReturn(Optional.empty());
    }

    @Test
    void listResolvesEntriesAndMarksDanglingValues() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of("agent-1", "deleted-agent")));
        var domainPolicy = skillPolicy(5, 6, List.of("demo/demo-skill"));
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(domainPolicy);
        when(skillPolicyService.findActive(SkillTier.SCOPE_DOMAIN)).thenReturn(Optional.of(domainPolicy));

        var agent = agent("agent-1", "Report Renderer");
        agent.systemDefault = Boolean.TRUE;
        when(agentDefinitionStore.get("agent-1")).thenReturn(Optional.of(agent));
        when(agentDefinitionStore.get("deleted-agent")).thenReturn(Optional.empty());
        when(agentDefinitionStore.findOne(any())).thenReturn(Optional.empty());
        when(skillStore.findByQualifiedName("demo/demo-skill")).thenReturn(Optional.of(skill("skill-1", "demo", "demo-skill")));

        var response = service.list("admin-1");

        assertThat(response.agent.policyRevision).isEqualTo(7);
        assertThat(response.agent.generation).isEqualTo(9);
        assertThat(response.agent.entries).hasSize(2);
        assertThat(response.agent.entries.get(0).name).isEqualTo("Report Renderer");
        assertThat(response.agent.entries.get(0).resolved).isTrue();
        assertThat(response.agent.entries.get(0).automatic).isTrue();
        assertThat(response.agent.entries.get(1).resolved).isFalse();
        assertThat(response.skill.entries.get(0).name).isEqualTo("demo-skill");
        assertThat(response.skill.entries.get(0).detail).isEqualTo("demo/demo-skill");
    }

    @Test
    void addPinsTheAgentIdOnANewPolicyRevision() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of("agent-1")));
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(skillPolicy(5, 6, List.of()));
        when(agentDefinitionStore.get("agent-2")).thenReturn(Optional.of(agent("agent-2", "New Agent")));
        var request = new AddQualityExemptionRequest();
        request.type = "AGENT";
        request.subjectId = "agent-2";
        request.expectedGeneration = 9;

        service.add(request, "admin-1");

        var captor = ArgumentCaptor.forClass(QualityPolicyService.ActivatePolicyCommand.class);
        verify(agentPolicyService).createPolicy(captor.capture(), eq("admin-1"));
        assertThat(captor.getValue().exemptions()).containsExactly("agent-1", "agent-2");
        assertThat(captor.getValue().expectedGeneration()).isEqualTo(9);
        assertThat(captor.getValue().changeReason()).contains("New Agent");
    }

    @Test
    void addRejectsAnAgentWhitelistedByItsName() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of("Report Renderer")));
        when(agentDefinitionStore.get("agent-1")).thenReturn(Optional.of(agent("agent-1", "Report Renderer")));
        var request = new AddQualityExemptionRequest();
        request.type = "AGENT";
        request.subjectId = "agent-1";

        assertThatThrownBy(() -> service.add(request, "admin-1"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("already exempt");
    }

    @Test
    void addStoresTheQualifiedSkillName() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of()));
        var domainPolicy = skillPolicy(5, 6, List.of());
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(domainPolicy);
        when(skillPolicyService.findActive(SkillTier.SCOPE_DOMAIN)).thenReturn(Optional.of(domainPolicy));
        when(skillStore.get("skill-1")).thenReturn(Optional.of(skill("skill-1", "demo", "demo-skill")));
        var request = new AddQualityExemptionRequest();
        request.type = "SKILL";
        request.subjectId = "skill-1";
        request.expectedGeneration = 6;

        service.add(request, "admin-1");

        var captor = ArgumentCaptor.forClass(SkillQualityPolicyService.ActivateCommand.class);
        verify(skillPolicyService).createPolicy(eq(SkillTier.SCOPE_DOMAIN), captor.capture(), eq("admin-1"));
        assertThat(captor.getValue().exemptions()).containsExactly("demo/demo-skill");
    }

    @Test
    void removeDropsTheStoredEntry() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of("agent-1", "agent-2")));
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(skillPolicy(5, 6, List.of()));
        when(agentDefinitionStore.get("agent-2")).thenReturn(Optional.of(agent("agent-2", "New Agent")));
        var request = new RemoveQualityExemptionRequest();
        request.type = "AGENT";
        request.value = "agent-2";
        request.expectedGeneration = 9;

        service.remove(request, "admin-1");

        var captor = ArgumentCaptor.forClass(QualityPolicyService.ActivatePolicyCommand.class);
        verify(agentPolicyService).createPolicy(captor.capture(), eq("admin-1"));
        assertThat(captor.getValue().exemptions()).containsExactly("agent-1");
        assertThat(captor.getValue().changeReason()).contains("New Agent");
    }

    @Test
    void subjectsFlagAlreadyExemptAgentsAndSkills() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of("agent-1")));
        var domainPolicy = skillPolicy(5, 6, List.of("demo/demo-skill"));
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(domainPolicy);
        when(skillPolicyService.findActive(SkillTier.SCOPE_DOMAIN)).thenReturn(Optional.of(domainPolicy));
        when(agentDefinitionStore.find(any())).thenReturn(List.of(agent("agent-1", "Agent One"), agent("agent-2", "Agent Two")));
        when(skillStore.listSummaries(null, null, null, null))
            .thenReturn(List.of(skill("skill-1", "demo", "demo-skill")));

        var agents = service.subjects(subjectRequest("AGENT"), "admin-1").subjects;
        var skills = service.subjects(subjectRequest("SKILL"), "admin-1").subjects;

        assertThat(agents).hasSize(2);
        assertThat(agents.get(0).alreadyExempt).isTrue();
        assertThat(agents.get(1).alreadyExempt).isFalse();
        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).alreadyExempt).isTrue();
    }

    @Test
    void subjectsMarkBuiltInAgentsAsAutomatic() {
        when(agentPolicyService.active()).thenReturn(agentPolicy(7, 9, List.of()));
        when(skillPolicyService.active(SkillTier.SCOPE_DOMAIN)).thenReturn(skillPolicy(5, 6, List.of()));
        var builtIn = agent("default-assistant", "Assistant");
        builtIn.systemDefault = Boolean.TRUE;
        when(agentDefinitionStore.find(any())).thenReturn(List.of(builtIn));

        var subjects = service.subjects(subjectRequest("AGENT"), "admin-1").subjects;

        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).automatic).isTrue();
    }

    @Test
    void rejectsAnUnknownExemptionType() {
        var request = subjectRequest("TEAM");

        assertThatThrownBy(() -> service.subjects(request, "admin-1"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("invalid exemption type");
    }

    private QualityExemptionSubjectListRequest subjectRequest(String type) {
        var request = new QualityExemptionSubjectListRequest();
        request.type = type;
        return request;
    }

    private QualityPolicyService.ActivePolicy agentPolicy(int revision, int generation, List<String> exemptions) {
        var policy = new AgentQualityPolicyRevision();
        policy.revision = revision;
        policy.exemptions = exemptions;
        var head = new AgentQualityPolicyHead();
        head.generation = generation;
        return new QualityPolicyService.ActivePolicy(policy, head, List.of());
    }

    private SkillQualityPolicyService.ActivePolicy skillPolicy(int revision, int generation, List<String> exemptions) {
        var policy = new SkillQualityPolicyRevision();
        policy.revision = revision;
        policy.scope = SkillTier.SCOPE_DOMAIN;
        policy.exemptions = exemptions;
        var head = new SkillQualityPolicyHead();
        head.scope = SkillTier.SCOPE_DOMAIN;
        head.generation = generation;
        return new SkillQualityPolicyService.ActivePolicy(policy, head, List.of());
    }

    private AgentDefinition agent(String id, String name) {
        var agent = new AgentDefinition();
        agent.id = id;
        agent.name = name;
        agent.userId = "user-1";
        return agent;
    }

    private SkillDefinition skill(String id, String namespace, String name) {
        var skill = new SkillDefinition();
        skill.id = id;
        skill.namespace = namespace;
        skill.name = name;
        skill.qualifiedName = namespace + "/" + name;
        return skill;
    }
}
