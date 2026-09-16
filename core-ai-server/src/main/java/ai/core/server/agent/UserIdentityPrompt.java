package ai.core.server.agent;

import ai.core.agent.ExecutionContext;
import ai.core.prompt.PromptInject;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;

/**
 * Tells a personal assistant who it is working for: the authenticated caller's name and email become a
 * system prompt section, so tasks that have to be scoped to a person (my tickets, the pages I authored)
 * do not require the user to restate their identity in every session.
 *
 * <p>The section lives on {@link ExecutionContext#getPromptSections()}: the agent builders merge the
 * execution context sections into the system prompt, and built-in sub agents inherit them. Only the
 * template fork ({@code forked_from != null}) carries it, and it carries context only, never a
 * credential: authorization stays on the server side, business identity travels through outbound
 * caller headers instead of the prompt.
 *
 * @author Xander
 */
public final class UserIdentityPrompt implements PromptInject {
    private static final int MAX_VALUE_LENGTH = 120;
    private static final String TITLE = "## Current User";
    // the whole system prompt is rendered through a Mustache template, so the values must not carry
    // markup or braces that the renderer would interpret
    private static final String FORBIDDEN_CHARACTERS = "[<>{}]";
    private static final String INSTRUCTION = "The above is the authenticated user you are serving. Use it whenever a task has to be "
        + "scoped to this person, for example searching tickets, pages or records by owner, assignee or author. "
        + "The values come from the platform session: use them verbatim and never assume another identity.";

    /** Attaches the caller identity when the executing agent is a personal assistant, no-op otherwise. */
    public static void attach(ExecutionContext context, AgentDefinition definition, User user) {
        if (context == null || !PersonalAssistantService.isPersonalAssistant(definition)) return;
        var section = render(user);
        if (section != null) context.getPromptSections().add(section);
    }

    /** Session rebuild only knows the agent id, the definition decides whether the section applies. */
    public static void attach(ExecutionContext context, String agentId, MongoCollection<AgentDefinition> agentDefinitionCollection, User user) {
        if (context == null || agentId == null || agentDefinitionCollection == null) return;
        attach(context, agentDefinitionCollection.get(agentId).orElse(null), user);
    }

    /** Returns null when the user is unknown or carries no identity field, so no empty section is attached. */
    private static UserIdentityPrompt render(User user) {
        if (user == null) return null;
        var name = sanitize(user.name);
        var email = sanitize(user.email);
        if (name == null && email == null) return null;
        var lines = new ArrayList<String>();
        lines.add(TITLE);
        if (name != null) lines.add("Name: " + name);
        if (email != null) lines.add("Email: " + email);
        lines.add("");
        lines.add(INSTRUCTION);
        return new UserIdentityPrompt(String.join("\n", lines));
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        var cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").replaceAll(FORBIDDEN_CHARACTERS, "").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > MAX_VALUE_LENGTH ? cleaned.substring(0, MAX_VALUE_LENGTH) : cleaned;
    }

    private final String content;

    private UserIdentityPrompt(String content) {
        this.content = content;
    }

    @Override
    public String inject() {
        return content;
    }

    @Override
    public SectionType type() {
        return SectionType.ENVIRONMENT;
    }
}
