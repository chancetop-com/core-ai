package ai.core.server.quality;

import ai.core.server.domain.AgentDefinition;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityExemptionsTest {
    @Test
    void normalizeTrimsDropsBlanksAndDeduplicatesInOrder() {
        var normalized = QualityExemptions.normalize(java.util.Arrays.asList(" agent-1 ", "", "agent-2", "agent-1", null));

        assertThat(normalized).containsExactly("agent-1", "agent-2");
    }

    @Test
    void matchUsesExactIdentityAndReturnsTheWhitelistEntry() {
        var exemptions = List.of("namespace/skill-a", "skill-b");

        assertThat(QualityExemptions.match(exemptions, "namespace/skill-a", "skill-a"))
            .isEqualTo("namespace/skill-a");
        assertThat(QualityExemptions.match(exemptions, "other/skill-b", "skill-b")).isEqualTo("skill-b");
        assertThat(QualityExemptions.match(exemptions, "namespace/skill-c", "skill-c")).isNull();
    }

    @Test
    void wildcardEntriesAreRejected() {
        assertThatThrownBy(() -> QualityExemptions.normalize(List.of("namespace/*")))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void builtInAgentsAreExemptWithoutWhitelistEntries() {
        var system = new AgentDefinition();
        system.systemDefault = Boolean.TRUE;
        assertThat(QualityExemptions.builtInAgentReason(system)).isEqualTo(QualityExemptions.BUILT_IN_AGENT_REASON);

        var personal = new AgentDefinition();
        personal.forkedFrom = "default-assistant";
        assertThat(QualityExemptions.builtInAgentReason(personal))
            .isEqualTo(QualityExemptions.PERSONAL_ASSISTANT_REASON);

        var ordinary = new AgentDefinition();
        ordinary.id = "agent-1";
        assertThat(QualityExemptions.builtInAgentReason(ordinary)).isNull();
    }
}
