package ai.core.agent.profile;

import ai.core.defaultagents.DeepResearchAgent;
import ai.core.defaultagents.DefaultCodeSimplifierAgent;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.defaultagents.DefaultGeneralAgent;

import java.util.List;

/**
 * Provides built-in agent profiles for the 4 default sub-agent types.
 * Priority=0 ensures built-in agents always take precedence over filesystem profiles.
 *
 * @author lim chen
 */
public class BuiltinAgentProfileProvider implements AgentProfileProvider {

    private static final List<AgentProfile> PROFILES = List.of(
            DeepResearchAgent.profile(),
            DefaultExploreAgent.profile(),
            DefaultCodeSimplifierAgent.profile(),
            DefaultGeneralAgent.profile()
    );

    @Override
    public List<AgentProfile> provide() {
        return PROFILES;
    }

    @Override
    public int priority() {
        return 0;
    }
}
