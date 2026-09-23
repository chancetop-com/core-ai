package ai.core.server.domain.migration;

import ai.core.prompt.Prompts;
import ai.core.server.workflow.WorkflowTestModule;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Runs the personality migration against a real Mongo: the template and the forks that inherited from it gain
 * the paragraph on both the editable and the published prompt, while a fork that already carries it, a
 * document that merely looks like a fork, and an unrelated agent stay untouched. Re-running the migration must
 * be a no-op.
 *
 * @author stephen
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class SchemaMigrationVDefaultAssistantPersonalityMongoTest {
    private static final String AGENTS = "agents";
    private static final String TEMPLATE_ID = SchemaMigrationVDefaultAgent.DEFAULT_AGENT_ID;
    private static final String RUN = Long.toHexString(System.nanoTime());
    private static final String OLD_PROMPT = "You are a helpful AI assistant, migration fixture " + RUN + ".";
    private static final String WITH_PERSONALITY = OLD_PROMPT + "\n\n" + Prompts.ASSISTANT_PERSONALITY_PROMPT;
    private static final String FORK = "assistant:mig-persona-fork-" + RUN;
    private static final String FORK_WITHOUT_PUBLISHED = "assistant:mig-persona-unpublished-" + RUN;
    private static final String FORK_ALREADY_UPDATED = "assistant:mig-persona-updated-" + RUN;
    private static final String LOOKALIKE = "assistant:mig-persona-lookalike-" + RUN;
    private static final String FOREIGN = "mig-persona-agent-" + RUN;
    private static final List<String> AGENT_IDS = List.of(FORK, FORK_WITHOUT_PUBLISHED, FORK_ALREADY_UPDATED, LOOKALIKE, FOREIGN);

    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    Mongo mongo;

    @BeforeEach
    void reset() {
        deleteFixtures();
    }

    @AfterEach
    void cleanup() {
        deleteFixtures();
    }

    private void deleteFixtures() {
        deleteAgents(new Document("$in", AGENT_IDS));
        deleteAgents(new Document("$in", List.of(TEMPLATE_ID)));
    }

    @Test
    void appendsPersonalityToTemplateAndForks() {
        insertAgent(template());
        insertAgent(fork(FORK, OLD_PROMPT, TEMPLATE_ID, true));
        insertAgent(fork(FORK_WITHOUT_PUBLISHED, OLD_PROMPT, TEMPLATE_ID, false));
        insertAgent(fork(FORK_ALREADY_UPDATED, WITH_PERSONALITY, TEMPLATE_ID, true));
        // same id shape as a fork but not forked from the template, and a plain agent: both must be skipped
        insertAgent(fork(LOOKALIKE, OLD_PROMPT, null, true));
        insertAgent(new Document("_id", FOREIGN).append("system_prompt", OLD_PROMPT));

        migrate();
        migrate();

        assertEquals(WITH_PERSONALITY, agent(TEMPLATE_ID).getString("system_prompt"), "the template prompt must carry the personality once");
        assertEquals(WITH_PERSONALITY, publishedPrompt(agent(TEMPLATE_ID)));
        assertEquals(WITH_PERSONALITY, agent(FORK).getString("system_prompt"));
        assertEquals(WITH_PERSONALITY, publishedPrompt(agent(FORK)));
        assertEquals(WITH_PERSONALITY, agent(FORK_WITHOUT_PUBLISHED).getString("system_prompt"));
        assertNull(agent(FORK_WITHOUT_PUBLISHED).get("published_config"), "a fork without a snapshot must not grow one");
        assertEquals(WITH_PERSONALITY, agent(FORK_ALREADY_UPDATED).getString("system_prompt"), "an already updated prompt must not be appended twice");
        assertEquals(OLD_PROMPT, agent(LOOKALIKE).getString("system_prompt"), "only real forks inherit the personality");
        assertEquals(OLD_PROMPT, agent(FOREIGN).getString("system_prompt"));
    }

    @Test
    void toleratesMissingTemplate() {
        insertAgent(fork(FORK, OLD_PROMPT, TEMPLATE_ID, true));

        migrate();

        assertEquals(WITH_PERSONALITY, agent(FORK).getString("system_prompt"), "a fork is updated even when the template row is gone");
    }

    private Document template() {
        return new Document("_id", TEMPLATE_ID)
            .append("system_prompt", OLD_PROMPT)
            .append("published_config", new Document("system_prompt", OLD_PROMPT));
    }

    private Document fork(String id, String prompt, String forkedFrom, boolean published) {
        var fork = new Document("_id", id).append("system_prompt", prompt);
        if (forkedFrom != null) fork.append("forked_from", forkedFrom);
        if (published) fork.append("published_config", new Document("system_prompt", prompt));
        return fork;
    }

    private void migrate() {
        new SchemaMigrationVDefaultAssistantPersonality().migrate(mongo);
    }

    private void insertAgent(Document agent) {
        mongo.runCommand(new Document("insert", AGENTS).append("documents", List.of(agent)));
    }

    private void deleteAgents(Document filter) {
        mongo.runCommand(new Document("delete", AGENTS)
            .append("deletes", List.of(new Document("q", new Document("_id", filter)).append("limit", 0))));
    }

    private Document agent(String id) {
        var result = mongo.runCommand(new Document("find", AGENTS)
            .append("filter", new Document("_id", id))
            .append("limit", 1));
        var batch = ((Document) result.get("cursor")).getList("firstBatch", Document.class);
        return batch.isEmpty() ? null : batch.getFirst();
    }

    private String publishedPrompt(Document agent) {
        var published = agent.get("published_config");
        return published instanceof Document document ? document.getString("system_prompt") : null;
    }
}
