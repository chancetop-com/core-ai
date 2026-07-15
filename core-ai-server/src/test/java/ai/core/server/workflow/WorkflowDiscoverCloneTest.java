package ai.core.server.workflow;

import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import core.framework.inject.Inject;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import core.framework.web.exception.BadRequestException;
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

    private static final String[] EXPLORE_TAG_OWNERS = {"explore-owner", "explore-viewer"};

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

        var discover = definitionService.list("viewer-2", Boolean.FALSE);
        assertTrue(discover.stream().anyMatch(d -> d.id.equals(published.id)), "published workflow must be discoverable");
        assertFalse(discover.stream().anyMatch(d -> d.id.equals(draft.id)), "unpublished draft must not be discoverable");

        // the owner's own discover list must not surface their own workflows
        var ownerDiscover = definitionService.list("owner-1", Boolean.FALSE);
        assertFalse(ownerDiscover.stream().anyMatch(d -> d.id.equals(published.id)), "discover excludes the caller's own");

        // the viewer's own list (myWorkflows=true) must not surface the owner's published workflow
        var viewerOwn = definitionService.list("viewer-2", Boolean.TRUE);
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
        assertTrue(definitionService.list("viewer-2", Boolean.TRUE).stream().anyMatch(d -> d.id.equals(copy.id)),
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
    void workflowRunsArePrivateByDefaultAndPublicWhenRequested() {
        WorkflowDefinition published = definitionService.create("runnable", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(published.id, "owner-1");

        WorkflowRun privateRun = runService.createRun(published.id, "{}", TriggerType.API, "viewer-2");
        assertEquals("viewer-2", privateRun.userId, "run is attributed to the caller, not the owner");
        assertEquals(RunStatus.PENDING, privateRun.status);
        assertEquals(WorkflowVisibility.PRIVATE, WorkflowRunService.visibilityOf(privateRun.visibility));
        assertTrue(runService.listRuns(published.id, "viewer-2").stream().anyMatch(r -> r.id.equals(privateRun.id)));
        assertFalse(runService.listRuns(published.id, "owner-1").stream().anyMatch(r -> r.id.equals(privateRun.id)));
        assertThrows(ForbiddenException.class, () -> runService.getRun(privateRun.id, "owner-1"));

        WorkflowRun publicRun = runService.createRun(published.id, "{}", TriggerType.API, "viewer-2", WorkflowVisibility.PUBLIC);
        assertEquals(WorkflowVisibility.PUBLIC, WorkflowRunService.visibilityOf(publicRun.visibility));
        assertTrue(runService.listRuns(published.id, "owner-1").stream().anyMatch(r -> r.id.equals(publicRun.id)));
        assertEquals(publicRun.id, runService.getRun(publicRun.id, "third-user").id);
    }

    @Test
    void runningAnUnpublishedWorkflowByOtherUserIsForbidden() {
        WorkflowDefinition draft = definitionService.create("private-draft", "WORKFLOW", GRAPH, "owner-1");
        assertThrows(ForbiddenException.class, () -> runService.createRun(draft.id, "{}", TriggerType.API, "viewer-2"));
    }

    @Test
    void manualSaveVersionDoesNotPublishUntilExplicitPublish() {
        WorkflowDefinition definition = definitionService.create("manual-version", "WORKFLOW", GRAPH, "owner-1");
        WorkflowPublishedVersion version = publishService.saveVersion(definition.id, "owner-1");

        WorkflowDefinition savedOnly = definitionService.get(definition.id, "owner-1");
        assertNull(savedOnly.publishedVersionId, "saving a version must not make the workflow public");
        assertEquals(1, version.version);
        assertFalse(definitionService.explore("viewer-2", "manual-version", 0, 10).stream().anyMatch(d -> d.id.equals(definition.id)));
        assertThrows(BadRequestException.class, () -> runService.createRun(definition.id, "{}", TriggerType.API, "owner-1"));

        WorkflowDefinition published = publishService.publishVersion(definition.id, version.id, "owner-1");
        assertEquals(version.id, published.publishedVersionId);
        assertEquals(WorkflowVisibility.PUBLIC, WorkflowDefinitionService.visibilityOf(published));
        assertTrue(definitionService.explore("viewer-2", "manual-version", 0, 10).stream().anyMatch(d -> d.id.equals(definition.id)));
    }

    @Test
    void unpublishHidesWorkflowButKeepsSavedVersionHistory() {
        WorkflowDefinition definition = definitionService.create("unpublish-me", "WORKFLOW", GRAPH, "owner-1");
        WorkflowPublishedVersion version = publishService.publish(definition.id, "owner-1");

        WorkflowDefinition unpublished = publishService.unpublish(definition.id, "owner-1");
        assertEquals(version.id, unpublished.publishedVersionId, "unpublish keeps the last public pointer for history");
        assertEquals(WorkflowVisibility.PRIVATE, WorkflowDefinitionService.visibilityOf(unpublished));
        assertFalse(definitionService.explore("viewer-2", "unpublish-me", 0, 10).stream().anyMatch(d -> d.id.equals(definition.id)));
        assertThrows(BadRequestException.class, () -> runService.createRun(definition.id, "{}", TriggerType.API, "owner-1"));
        assertThrows(ForbiddenException.class, () -> definitionService.getVersionGraph(version.id, "viewer-2"));
        assertTrue(definitionService.getVersionGraph(version.id, "owner-1").contains("\"START\""));
        assertEquals(1, publishService.listVersions(definition.id, "owner-1").size());
    }

    @Test
    void publishingSavedVersionRechecksWorkflowNodeVisibility() {
        WorkflowDefinition child = definitionService.create("child-unpublished-before-parent-publish", "WORKFLOW", GRAPH, "owner-1");
        WorkflowPublishedVersion childVersion = publishService.publish(child.id, "owner-1");
        String parentGraph = """
            {"nodes": [
              {"id": "start", "type": "START"},
               {"id": "call_child", "type": "WORKFLOW",
                "config": {"source_workflow_id": "CHILD_ID", "version_id": "CHILD_VERSION"}},
              {"id": "end", "type": "END"}],
             "edges": [
              {"id": "e1", "source": "start", "target": "call_child"},
              {"id": "e2", "source": "call_child", "target": "end"}]}
            """.replace("CHILD_ID", child.id).replace("CHILD_VERSION", childVersion.id);
        WorkflowDefinition parent = definitionService.create("parent-after-child-unpublish", "WORKFLOW", parentGraph, "owner-1");
        WorkflowPublishedVersion parentVersion = publishService.saveVersion(parent.id, "owner-1");

        publishService.unpublish(child.id, "owner-1");

        assertThrows(WorkflowValidationException.class,
            () -> publishService.publishVersion(parent.id, parentVersion.id, "owner-1"));
    }

    @Test
    void adminDeletePublicWorkflowArchivesInsteadOfHardDelete() {
        WorkflowDefinition definition = definitionService.create("admin-archive-public", "WORKFLOW", GRAPH, "owner-1");
        publishService.publish(definition.id, "owner-1");

        definitionService.delete(definition.id, "admin-1", true);

        WorkflowDefinition archived = definitionService.get(definition.id, "owner-1");
        assertEquals(WorkflowDefinitionStatus.ARCHIVED, WorkflowDefinitionService.statusOf(archived));
        assertEquals(WorkflowVisibility.PRIVATE, WorkflowDefinitionService.visibilityOf(archived));
        assertFalse(definitionService.explore("viewer-2", "admin-archive-public", 0, 10).stream().anyMatch(d -> d.id.equals(definition.id)));
    }

    @Test
    void exploreReturnsOtherUsersPublishedFilteredAndPaged() {
        // Unique tag isolates this test's rows from other tests sharing the wftest DB.
        String tag = "explorekw";
        // Clean up orphaned test data from previous runs so the test is idempotent.
        for (var owner : EXPLORE_TAG_OWNERS) {
            for (var wf : definitionService.list(owner, Boolean.TRUE, tag, null, null)) {
                definitionService.delete(wf.id, owner);
            }
        }
        publishOwned("explore-owner", tag + " alpha");
        publishOwned("explore-owner", tag + " beta");
        publishOwned("explore-owner", tag + " gamma");
        publishOwned("explore-viewer", tag + " mine");                       // caller's own -> excluded
        definitionService.create(tag + " draft", "WORKFLOW", GRAPH, "explore-owner");   // unpublished -> excluded

        var hits = definitionService.explore("explore-viewer", tag, 0, 50);
        assertEquals(3, hits.size());
        assertTrue(hits.stream().noneMatch(d -> "explore-viewer".equals(d.userId)), "excludes the caller's own");
        assertTrue(hits.stream().allMatch(d -> d.publishedVersionId != null), "only published");
        assertEquals(3, definitionService.exploreCount("explore-viewer", tag));

        assertEquals(3, definitionService.explore("explore-viewer", "EXPLOREKW", 0, 50).size(), "case-insensitive");

        // paging: limit 2 -> page 1 = 2, page 2 = 1
        assertEquals(2, definitionService.explore("explore-viewer", tag, 0, 2).size());
        assertEquals(1, definitionService.explore("explore-viewer", tag, 2, 2).size());

        // clamps: negative offset -> 0, oversized limit -> capped (still returns the 3 matches)
        assertEquals(3, definitionService.explore("explore-viewer", tag, -10, 1000).size());
    }

    private void publishOwned(String userId, String name) {
        var wf = definitionService.create(name, "WORKFLOW", GRAPH, userId);
        publishService.publish(wf.id, userId);
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
