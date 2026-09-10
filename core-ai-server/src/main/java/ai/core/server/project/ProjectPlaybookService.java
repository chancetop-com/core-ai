package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.run.LLMCallExecutor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drafts the project playbook with ONE tunable LLM call ({@code project-playbook-writer}) so the
 * owner does not have to write it by hand. The input is the project definition (name/description/
 * goal), its members with their descriptions, the subjects already tracked and samples of the
 * members' recent material — the evidence of what the project actually does. The draft is returned
 * to the editor for review: nothing is written to the project, saving stays an explicit user action.
 *
 * @author stephen
 */
public class ProjectPlaybookService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectPlaybookService.class);
    static final int MAX_SESSIONS = 12;
    static final int MAX_MESSAGES_PER_SESSION = 6;
    static final int MAX_RUNS = 12;
    static final int MAX_WORKFLOW_RUNS = 8;
    static final int MAX_SUBJECTS = 40;
    static final int MAX_ARTIFACT_NAMES = 40;
    static final int MAX_MATERIAL_CHARS = 24000;
    static final int MAX_PLAYBOOK_CHARS = 20000;   // sanity cap only — the prompt asks for 3500

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;
    @Inject
    MongoCollection<FileRecord> fileRecordCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<WorkflowDefinition> workflowCollection;
    @Inject
    ProjectService projectService;
    @Inject
    LLMCallExecutor llmCallExecutor;

    public String generate(String projectId) {
        var project = projectCollection.get(projectId)
            .orElseThrow(() -> new IllegalStateException("project not found, id=" + projectId));
        var definition = agentCollection.get("builtin-" + ProjectBuiltinAgents.PLAYBOOK_WRITER).orElse(null);
        if (definition == null) {
            throw new IllegalStateException("playbook writer definition is missing; reset builtin agents to restore it");
        }
        var input = buildInput(project);
        var output = llmCallExecutor.execute(definition, input, null, 600).output();
        var playbook = cleanup(output);
        if (playbook.isBlank()) {
            throw new IllegalStateException("playbook generation returned an empty result; try again");
        }
        LOGGER.info("playbook drafted, projectId={}, chars={}", projectId, playbook.length());
        return playbook;
    }

    private String buildInput(Project project) {
        var text = new StringBuilder(4096);
        text.append("PROJECT:\nname: ").append(project.name)
            .append("\ndescription: ").append(orNone(project.description))
            .append("\ngoal: ").append(orNone(project.goal))
            .append("\nauto subjects: ").append(ProjectService.autoSubjectMode(project))
            .append("\n\nCURRENT PLAYBOOK:\n").append(playbookText(project))
            .append("\n\nMEMBERS (whose work this project tracks):\n").append(membersText(project))
            .append("\n\nEXISTING SUBJECTS:\n").append(subjectsText(project.id))
            .append("\n\nRECENT MATERIAL:\n").append(materialText(project));
        return text.toString();
    }

    private String membersText(Project project) {
        if (project.members == null || project.members.isEmpty()) return "(none attached yet)\n";
        var memberIds = project.members.stream().map(m -> m.id).toList();
        var agentDescriptions = agentDescriptions(memberIds);
        var workflowDescriptions = workflowDescriptions(memberIds);
        var text = new StringBuilder(256);
        for (var member : project.members) {
            var description = "workflow".equals(member.type) ? workflowDescriptions.get(member.id) : agentDescriptions.get(member.id);
            text.append("- ").append(member.type).append(" \"").append(member.name).append('"');
            if (description != null && !description.isBlank()) text.append(": ").append(limit(description, 250));
            text.append('\n');
        }
        return text.toString();
    }

    private String subjectsText(String projectId) {
        var subjects = projectService.subjects(projectId);
        if (subjects.isEmpty()) return "(none yet)\n";
        var text = new StringBuilder(512);
        for (var subject : subjects.stream().limit(MAX_SUBJECTS).toList()) {
            text.append("- ").append(subject.name);
            if (subject.description != null && !subject.description.isBlank()) {
                text.append(" — ").append(limit(subject.description, 150));
            }
            if (subject.phase != null && !subject.phase.isBlank()) text.append(" [phase: ").append(subject.phase).append(']');
            text.append('\n');
        }
        return text.toString();
    }

    private String materialText(Project project) {
        var text = new StringBuilder(2048);
        var agentIds = memberIds(project, "agent");
        var artifacts = new ArrayList<String>();
        if (!agentIds.isEmpty()) {
            appendSessions(text, agentIds, artifacts);
            appendRuns(text, agentIds, artifacts);
        }
        appendWorkflowRuns(text, memberIds(project, "workflow"));
        appendArtifactNames(text, artifacts);
        return text.length() == 0 ? "(no material yet)\n" : text.toString();
    }

    private void appendSessions(StringBuilder text, List<String> agentIds, List<String> artifacts) {
        var query = new Query();
        query.filter = Filters.in("agent_id", agentIds);
        query.sort = Sorts.descending("last_message_at");
        query.limit = MAX_SESSIONS;
        for (var session : chatSessionCollection.find(query)) {
            if (overCap(text)) return;
            text.append("## session \"").append(session.title).append("\" (agent: ").append(session.agentId).append(")\n");
            for (var message : userMessages(session.id)) {
                text.append("USER: ").append(limit(message, 400)).append('\n');
            }
            collectArtifacts(artifacts, session.artifacts);
        }
    }

    private void appendRuns(StringBuilder text, List<String> agentIds, List<String> artifacts) {
        var query = new Query();
        query.filter = Filters.in("agent_id", agentIds);
        query.sort = Sorts.descending("started_at");
        query.limit = MAX_RUNS;
        for (var run : agentRunCollection.find(query)) {
            if (overCap(text)) return;
            text.append("## run (agent: ").append(run.agentId).append(")\ninput: ").append(limit(run.input, 700)).append('\n');
            collectArtifacts(artifacts, run.artifacts);
        }
    }

    private void appendWorkflowRuns(StringBuilder text, List<String> workflowIds) {
        if (workflowIds.isEmpty()) return;
        var query = new Query();
        query.filter = Filters.in("workflow_id", workflowIds);
        query.sort = Sorts.descending("started_at");
        query.limit = MAX_WORKFLOW_RUNS;
        for (var run : workflowRunCollection.find(query)) {
            if (overCap(text)) return;
            text.append("## workflow run (workflow: ").append(run.workflowId).append(")\ninput: ")
                .append(limit(run.input, 900)).append('\n');
            if (run.artifacts != null) {
                for (var artifact : run.artifacts) {
                    if (artifact.fileName != null) text.append("file: ").append(limit(artifact.fileName, 120)).append('\n');
                }
            }
        }
    }

    // file names are the most concrete evidence of what the members deliver (report names carry
    // the subject names)
    private void appendArtifactNames(StringBuilder text, List<String> fileIds) {
        var unique = fileIds.stream().distinct().limit(MAX_ARTIFACT_NAMES).toList();
        if (unique.isEmpty()) return;
        var names = new ArrayList<String>();
        for (var file : fileRecordCollection.find(byIds(unique))) {
            if (file.fileName != null && !file.fileName.isBlank()) names.add(limit(file.fileName, 120));
        }
        if (names.isEmpty()) return;
        text.append("\n## files the members produced\n").append(String.join(", ", names)).append('\n');
    }

    private List<String> userMessages(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.ascending("seq");
        var history = chatMessageCollection.find(query);
        var tail = history.size() > MAX_MESSAGES_PER_SESSION
            ? history.subList(history.size() - MAX_MESSAGES_PER_SESSION, history.size()) : history;
        var messages = new ArrayList<String>();
        for (var message : tail) {
            if (!"user".equals(message.role)) continue;
            if (message.content == null || message.content.isBlank()) continue;
            messages.add(message.content);
        }
        return messages;
    }

    // the model is told to reply with markdown only; fences are stripped anyway so a wrapped
    // reply does not end up saved with the fence inside the playbook
    private String cleanup(String output) {
        if (output == null) return "";
        var text = output.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) text = text.substring(firstBreak + 1).strip();
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3).strip();
        }
        return limit(text, MAX_PLAYBOOK_CHARS);
    }

    private String playbookText(Project project) {
        if (project.playbook == null || project.playbook.isBlank()) return "(none — draft one)";
        return limit(project.playbook, 8000);
    }

    private Map<String, String> agentDescriptions(List<String> memberIds) {
        var byId = new HashMap<String, String>();
        for (var definition : agentCollection.find(byIds(memberIds))) byId.put(definition.id, definition.description);
        return byId;
    }

    private Map<String, String> workflowDescriptions(List<String> memberIds) {
        var byId = new HashMap<String, String>();
        for (var definition : workflowCollection.find(byIds(memberIds))) byId.put(definition.id, definition.description);
        return byId;
    }

    private List<String> memberIds(Project project, String type) {
        if (project.members == null) return List.of();
        return project.members.stream().filter(m -> type.equals(m.type)).map(m -> m.id).toList();
    }

    private void collectArtifacts(List<String> artifacts, List<AgentRunArtifact> source) {
        if (source == null) return;
        for (var artifact : source) {
            if (artifact.fileId != null) artifacts.add(artifact.fileId);
        }
    }

    private Bson byIds(List<String> values) {
        return Filters.in("_id", values);
    }

    private boolean overCap(StringBuilder text) {
        return text.length() >= MAX_MATERIAL_CHARS;
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.length() > maxChars ? value.substring(0, maxChars) + "...(truncated)" : value;
    }
}
