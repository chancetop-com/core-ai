package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowRun;
import core.framework.inject.Inject;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-user discover + clone against a real Mongo: a published workflow shows up in another user's discover list
 * (myWorkflows=false), an unpublished draft does not, and cloning copies the frozen published graph into a fresh
 * draft owned by the caller.
 *
 * @author Xander
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class WorkflowDiscoverCloneTest {
    private static final String GRAPH = """
        {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
         "edges": [{"id": "e0", "source": "start", "target": "end"}]}
        """;

    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    WorkflowPublishService publishService;

    @Inject
    WorkflowRunService runService;

    @Test
    void publishedWorkflowIsDiscoverableByOtherUsersButDraftIsNot() {
        WorkflowDefinition published = definitionService.create("owner-published", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(published.id, "owner-1");
        WorkflowDefinition draft = definitionService.create("owner-draft", "WORKFLOW", GRAPH, "owner-1");

        var discover = definitionService.list("viewer-2", false);
        assertTrue(discover.stream().anyMatch(d -> d.id.equals(published.id)), "published workflow must be discoverable");
        assertFalse(discover.stream().anyMatch(d -> d.id.equals(draft.id)), "unpublished draft must not be discoverable");

        // the owner's own discover list must not surface their own workflows
        var ownerDiscover = definitionService.list("owner-1", false);
        assertFalse(ownerDiscover.stream().anyMatch(d -> d.id.equals(published.id)), "discover excludes the caller's own");

        // the viewer's own list (myWorkflows=true) must not surface the owner's published workflow
        var viewerOwn = definitionService.list("viewer-2", true);
        assertFalse(viewerOwn.stream().anyMatch(d -> d.id.equals(published.id)), "own list excludes other users' workflows");
    }

    @Test
    void cloneCopiesFrozenPublishedGraphIntoCallerOwnedDraft() {
        WorkflowDefinition source = definitionService.create("clone-source", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(source.id, "owner-1");

        WorkflowDefinition copy = definitionService.clone(source.id, "viewer-2");
        assertEquals("viewer-2", copy.userId, "clone is owned by the caller");
        assertNull(copy.publishedVersionId, "clone starts as a fresh draft");
        assertTrue(copy.draftGraph.contains("START"), "clone carries the published graph");
        assertFalse(copy.id.equals(source.id), "clone is a distinct definition");
        assertTrue(definitionService.list("viewer-2", true).stream().anyMatch(d -> d.id.equals(copy.id)),
            "clone shows up in the caller's own list");
    }

    @Test
    void cloningAnUnpublishedWorkflowIsRejected() {
        WorkflowDefinition draft = definitionService.create("never-published", "WORKFLOW", GRAPH, "owner-1");
        assertThrows(ForbiddenException.class, () -> definitionService.clone(draft.id, "viewer-2"));
    }

    @Test
    void cloneOfAgentlessWorkflowHasNoPublishBlockingWarnings() {
        WorkflowDefinition source = definitionService.create("agentless-source", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(source.id, "owner-1");
        WorkflowDefinition copy = definitionService.clone(source.id, "viewer-2");
        // The web layer surfaces publishService.validate(copy) as clone warnings; a clean START->END clone has none
        assertTrue(publishService.validate(copy).isEmpty(), "an agentless clone must not produce false publish warnings");
    }

    @Test
    void otherUserCanRunPublishedWorkflowAndRunIsAttributedToThem() {
        WorkflowDefinition published = definitionService.create("runnable", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(published.id, "owner-1");

        WorkflowRun run = runService.createRun(published.id, "{}", TriggerType.API, "viewer-2");
        assertEquals("viewer-2", run.userId, "run is attributed to the caller, not the owner");
        assertEquals(RunStatus.PENDING, run.status);
        assertEquals(published.id, run.workflowId);
        // run-level ownership: the runner sees their own run, the owner does not
        assertTrue(runService.listRuns(published.id, "viewer-2").stream().anyMatch(r -> r.id.equals(run.id)));
        assertFalse(runService.listRuns(published.id, "owner-1").stream().anyMatch(r -> r.id.equals(run.id)));
    }

    @Test
    void runningAnUnpublishedWorkflowByOtherUserIsForbidden() {
        WorkflowDefinition draft = definitionService.create("private-draft", "WORKFLOW", GRAPH, "owner-1");
        assertThrows(ForbiddenException.class, () -> runService.createRun(draft.id, "{}", TriggerType.API, "viewer-2"));
    }

    @Test
    void getReadableServesPublishedGraphToOtherUsersButForbidsDrafts() {
        WorkflowDefinition published = definitionService.create("viewable", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(published.id, "owner-1");
        // owner edits the draft AFTER publishing; the read-only view must NOT leak that unpublished change
        definitionService.update(published.id, null, GRAPH.replace("END", "END_EDITED"), "owner-1");

        WorkflowDefinition readable = definitionService.getReadable(published.id, "viewer-2");
        assertTrue(readable.draftGraph.contains("\"END\""), "non-owner sees the frozen published graph");
        assertFalse(readable.draftGraph.contains("END_EDITED"), "non-owner must not see the owner's unpublished draft edit");

        // getReadable returns a DETACHED copy: the owner's real stored draft must stay exactly as they left it
        WorkflowDefinition ownerStored = definitionService.get(published.id, "owner-1");
        assertTrue(ownerStored.draftGraph.contains("END_EDITED"), "getReadable must not overwrite the owner's stored draft");

        WorkflowDefinition draft = definitionService.create("hidden-draft", "WORKFLOW", GRAPH, "owner-1");
        assertThrows(ForbiddenException.class, () -> definitionService.getReadable(draft.id, "viewer-2"));
    }
}
