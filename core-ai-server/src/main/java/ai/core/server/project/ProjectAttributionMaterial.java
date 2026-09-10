package ai.core.server.project;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection state of one attribution run: the digest handed to the attributor plus the bookkeeping
 * that makes the run convergent — which targets were offered (to be marked scanned), which of them
 * had grown since their last offer (to be re-opened for analysis) and how far the forward cursor may
 * advance. The cursor is tracked PER TARGET TYPE and only follows the slowest one: the types are
 * collected in batches of different sizes, so on a single cursor the fastest type walks ahead and
 * drags the cursor over the slower type's backlog, which is then never offered again. Pure in-memory
 * logic, unit-testable without Mongo.
 *
 * @author stephen
 */
final class ProjectAttributionMaterial {
    private final String projectId;
    private final int maxDigestChars;
    private final StringBuilder digest = new StringBuilder(8192);
    private final Map<String, OfferedTarget> offered = new LinkedHashMap<>();
    private final List<OfferedTarget> grown = new ArrayList<>();
    private final Set<String> fileIds = new LinkedHashSet<>();
    private final Map<String, ZonedDateTime> coveredThrough = new LinkedHashMap<>();

    ProjectAttributionMaterial(String projectId, int maxDigestChars) {
        this.projectId = projectId;
        this.maxDigestChars = maxDigestChars;
    }

    String projectId() {
        return projectId;
    }

    /**
     * Decides what to do with one candidate record. {@code state} is the record's scan state (null =
     * never offered). In forward mode every record passed over — offered or already covered — moves
     * the cursor; the cursor never moves past a record that did not fit into this run.
     */
    Decision consider(String targetType, String targetId, ZonedDateTime materialAt, ProjectTargetScanStore.ScanState state, boolean forward) {
        var key = targetType + ":" + targetId;
        if (offered.containsKey(key)) {
            if (forward) coverThrough(targetType, materialAt);
            return Decision.SKIP;   // already collected by the other pass
        }
        var stale = state != null && materialAt != null && state.materialAt() != null && materialAt.isAfter(state.materialAt());
        if (state != null && !stale) {
            if (forward) coverThrough(targetType, materialAt);
            return Decision.SKIP;   // offered before and unchanged since (attributed or not)
        }
        if (digest.length() >= maxDigestChars) return Decision.STOP;
        var target = new OfferedTarget(targetType, targetId, materialAt);
        offered.put(key, target);
        if (stale && state.attributed()) grown.add(target);
        if (forward) coverThrough(targetType, materialAt);
        return Decision.OFFER;
    }

    /**
     * Records that everything of this target type before {@code time} is covered. The stage seeds a
     * type with the oldest record its forward query returned, so a type whose walk was cut off by the
     * digest cap BEFORE considering anything still holds the cursor back instead of letting it pass.
     */
    void coverThrough(String targetType, ZonedDateTime time) {
        if (time == null) return;
        var covered = coveredThrough.get(targetType);
        if (covered == null || time.isAfter(covered)) coveredThrough.put(targetType, time);
    }

    void append(String text) {
        digest.append(text);
    }

    void addFile(String fileId) {
        fileIds.add(fileId);
    }

    boolean isEmpty() {
        return offered.isEmpty();
    }

    String digest() {
        return digest.toString();
    }

    List<OfferedTarget> offered() {
        return new ArrayList<>(offered.values());
    }

    List<OfferedTarget> grown() {
        return grown;
    }

    /**
     * How far the shared cursor may advance: the oldest waterline across the types the forward pass
     * actually walked. A type with no records past the cursor is not behind and does not constrain it.
     */
    ZonedDateTime forwardLatest() {
        return coveredThrough.values().stream().min(Comparator.naturalOrder()).orElse(null);
    }

    boolean contains(String targetType, String targetId) {
        if (ProjectAttributionStore.TARGET_FILE.equals(targetType)) return fileIds.contains(targetId);
        return offered.containsKey(targetType + ":" + targetId);
    }

    enum Decision {
        OFFER, SKIP, STOP
    }

    record OfferedTarget(String targetType, String targetId, ZonedDateTime materialAt) {
    }
}
