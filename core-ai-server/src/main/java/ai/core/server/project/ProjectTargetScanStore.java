package ai.core.server.project;

import ai.core.server.domain.ProjectTargetScan;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write surface of the attribution scan markers. A target's scan state combines the marker
 * (offered to the attributor at material time X) with whether attribution rows exist for it in the
 * project, so deterministically attributed records (schedule → run) count as covered without ever
 * being offered.
 *
 * @author stephen
 */
public class ProjectTargetScanStore {
    @Inject
    ProjectTargetScanRepository scanRepository;
    @Inject
    ProjectAttributionRepository attributionRepository;

    /** scan state per target id for one batch (absent key = never offered and not attributed) */
    public Map<String, ScanState> states(String projectId, String targetType, List<String> targetIds) {
        var result = new HashMap<String, ScanState>();
        if (targetIds.isEmpty()) return result;
        var materialAt = new HashMap<String, ZonedDateTime>();
        for (var scan : scanRepository.listByProjectAndTargets(projectId, targetType, targetIds)) {
            materialAt.put(scan.targetId, scan.materialAt);
        }
        var attributedAt = new HashMap<String, ZonedDateTime>();
        for (var row : attributionRepository.listByProjectAndTargets(projectId, targetType, targetIds)) {
            var previous = attributedAt.get(row.targetId);
            var at = row.createdAt != null ? row.createdAt : ZonedDateTime.now();
            if (previous == null || at.isAfter(previous)) attributedAt.put(row.targetId, at);
        }
        for (var id : targetIds) {
            var scanned = materialAt.containsKey(id);
            var isAttributed = attributedAt.containsKey(id);
            if (!scanned && !isAttributed) continue;
            // attributed without a marker (deterministic binding / legacy): covered as of the
            // attribution time, so growth after it still shows up as newer material
            result.put(id, new ScanState(scanned ? materialAt.get(id) : attributedAt.get(id), isAttributed));
        }
        return result;
    }

    /** upserts the markers of every offered target with its material time */
    public void markOffered(String projectId, List<ProjectAttributionMaterial.OfferedTarget> targets) {
        var now = ZonedDateTime.now();
        for (var target : targets) {
            var scan = new ProjectTargetScan();
            scan.id = UUID.randomUUID().toString();
            scan.projectId = projectId;
            scan.targetType = target.targetType();
            scan.targetId = target.targetId();
            scan.materialAt = target.materialAt() != null ? target.materialAt() : now;
            scan.scannedAt = now;
            scanRepository.upsert(scan);
        }
    }

    /**
     * Deletes the markers of targets that have no attribution row in the project — they were offered
     * but the attributor matched them to nothing. The next round offers them again, which is how
     * material scanned before a subject existed (or before auto-discovery was enabled) gets
     * reclassified. Explicit and costly: it re-runs the LLM over that material.
     *
     * <p>The oldest marker is read BEFORE the drop and returned with the count: a caller rewinding the
     * attribution cursor to it must not read it afterwards, when the marker is gone.
     */
    public Rescan dropUnattributed(String projectId) {
        var oldest = oldestMarkerAt(projectId);
        long dropped = 0;
        for (var scan : scanRepository.listByProject(projectId)) {
            var attributed = attributionRepository.countByProjectAndTarget(projectId, scan.targetType, scan.targetId) > 0;
            if (attributed) continue;
            scanRepository.deleteById(scan.id);
            dropped++;
        }
        return new Rescan(oldest, dropped);
    }

    private ZonedDateTime oldestMarkerAt(String projectId) {
        return scanRepository.oldestMaterialAt(projectId).orElse(null);
    }

    /**
     * @param oldestMarkerAt material time of the project's oldest marker before the drop (null = nothing was scanned)
     * @param dropped how many markers were deleted
     */
    public record Rescan(ZonedDateTime oldestMarkerAt, long dropped) {
    }

    /**
     * @param materialAt material time of the record when it was last offered (null = never offered)
     * @param attributed whether attribution rows exist for the target in the project
     */
    public record ScanState(ZonedDateTime materialAt, boolean attributed) {
    }
}
