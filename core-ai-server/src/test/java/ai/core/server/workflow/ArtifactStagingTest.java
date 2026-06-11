package ai.core.server.workflow;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ArtifactRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactStagingTest {
    private final VariablePool pool = new VariablePool(Map.of(), Map.of(
        "a", List.of(ref("f1", "report.pdf"), ref("f2", "data.csv")),
        "b", List.of(ref("f3", "img.png"))), "{}");

    @Test
    void wholeArrayReferenceStagesEveryArtifactOfTheNode() {
        var staged = ArtifactStaging.scanTemplate("process: {{ nodes.a.artifacts }}", pool);
        assertEquals(List.of("/tmp/inputs/a/report.pdf", "/tmp/inputs/a/data.csv"),
            staged.stream().map(f -> f.targetPath()).toList());
    }

    @Test
    void indexAndPathReferencesStageTheSingleArtifact() {
        assertEquals(List.of("f2"),
            ArtifactStaging.scanTemplate("{{ nodes.a.artifacts.1 }}", pool).stream().map(f -> f.fileId()).toList());
        assertEquals(List.of("f1"),
            ArtifactStaging.scanTemplate("read {{ nodes.a.artifacts.0.path }}", pool).stream().map(f -> f.fileId()).toList());
    }

    @Test
    void metadataOnlyReferencesDoNotStage() {
        assertTrue(ArtifactStaging.scanTemplate("link: {{ nodes.a.artifacts.0.url }}", pool).isEmpty());
        assertTrue(ArtifactStaging.scanTemplate("name: {{ nodes.a.artifacts.0.file_name }}", pool).isEmpty());
    }

    @Test
    void mixedReferencesDedupAcrossNodesByTargetPath() {
        var staged = ArtifactStaging.scanTemplate(
            "{{ nodes.a.artifacts.0.path }} and {{ nodes.a.artifacts }} and {{ nodes.b.artifacts }}", pool);
        assertEquals(List.of("/tmp/inputs/a/report.pdf", "/tmp/inputs/a/data.csv", "/tmp/inputs/b/img.png"),
            staged.stream().map(f -> f.targetPath()).toList());
    }

    @Test
    void bareSelectorFormFollowsTheSameRule() {
        assertEquals(List.of("f3"),
            ArtifactStaging.scanSelector("nodes.b.artifacts", pool).stream().map(f -> f.fileId()).toList());
        assertTrue(ArtifactStaging.scanSelector("nodes.a.artifacts.0.url", pool).isEmpty());
        assertTrue(ArtifactStaging.scanSelector("nodes.a.output", pool).isEmpty());
    }

    @Test
    void unknownNodeOrOutOfRangeIndexStagesNothing() {
        assertTrue(ArtifactStaging.scanTemplate("{{ nodes.zzz.artifacts }}", pool).isEmpty());
        assertTrue(ArtifactStaging.scanTemplate("{{ nodes.a.artifacts.9 }}", pool).isEmpty());
    }

    @Test
    void fileNamesAreSanitizedToASingleSegment() {
        assertEquals("/tmp/inputs/n/passwd", ArtifactStaging.pathOf("n", "../../etc/passwd"));
        assertEquals("/tmp/inputs/n/win.txt", ArtifactStaging.pathOf("n", "evil\\dir\\win.txt"));
        assertEquals("/tmp/inputs/n/file", ArtifactStaging.pathOf("n", "  /  "));
    }

    @Test
    void stagedViewInjectsPathAndPlainPoolDoesNot() {
        var staged = pool.stagedView();
        assertEquals("/tmp/inputs/a/report.pdf", staged.resolve("nodes.a.artifacts.0.path").orElseThrow());
        assertTrue(staged.render("{{ nodes.a.artifacts }}").contains("\"path\""));
        assertFalse(pool.resolve("nodes.a.artifacts.0.path").isPresent());
        assertFalse(pool.render("{{ nodes.a.artifacts }}").contains("\"path\""));
    }

    private static ArtifactRef ref(String fileId, String fileName) {
        var artifact = new AgentRunArtifact();
        artifact.fileId = fileId;
        artifact.fileName = fileName;
        return ArtifactRef.of(artifact, "https://h/api/files/" + fileId + "/content");
    }
}
