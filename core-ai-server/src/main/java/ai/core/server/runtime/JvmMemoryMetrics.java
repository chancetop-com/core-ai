package ai.core.server.runtime;

import com.sun.management.GcInfo;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JVM and container memory numbers that the per-minute runtime sample keeps. Three physical quantities
 * are reported separately: the instantaneous heap (allocation pressure), the Old Gen after the last
 * collection (retained pressure) and the container working set (memory outside the heap included).
 * Admission reads the retained signal, never the instantaneous one.
 *
 * @author stephen
 */
public final class JvmMemoryMetrics {
    private static final double PERCENT = 100.0;
    private static final long KILOBYTE = 1024;
    private static final String OLD_GEN_POOL = "G1 Old Gen";
    private static final List<MemoryPoolMXBean> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
            .toList();
    private static final List<com.sun.management.GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(com.sun.management.GarbageCollectorMXBean.class::isInstance)
            .map(com.sun.management.GarbageCollectorMXBean.class::cast)
            .toList();
    private static final String CGROUP_MEMORY = firstReadable("/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes");
    private static final String CGROUP_MEMORY_MAX = firstReadable("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes");
    private static final String PROC_STATUS = "/proc/self/status";

    public static Snapshot snapshot() {
        long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long maxMemory = Runtime.getRuntime().maxMemory();
        Long heapMax = maxMemory > 0 ? maxMemory : null;
        var gc = latestGc();
        var gcInfo = gc == null ? null : gc.info();
        Long heapBeforeGc = heapUsage(gcInfo, true);
        Long heapAfterGc = heapUsage(gcInfo, false);
        Long containerMemory = readLong(CGROUP_MEMORY);
        Long containerMemoryMax = readLong(CGROUP_MEMORY_MAX);
        Long gcId = gcInfo == null ? null : gcInfo.getId();
        Long gcAgeMs = gcInfo == null ? null : Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime() - gcInfo.getEndTime());
        Long oldGen = oldGenUsage();
        return new Snapshot(heapUsed, heapMax, percent(heapUsed, heapMax),
                heapBeforeGc, percent(heapBeforeGc, heapMax),
                heapAfterGc, percent(heapAfterGc, heapMax), gcId, gcAgeMs,
                containerMemory, containerMemoryMax, percent(containerMemory, containerMemoryMax),
                readRss(),
                oldGen, percent(oldGen, heapMax),
                gc == null ? null : gc.kind(),
                gc == null ? null : gc.reclaimedBytes());
    }

    private static Long heapUsage(GcInfo gcInfo, boolean beforeGc) {
        if (gcInfo == null) return null;
        var usages = beforeGc ? gcInfo.getMemoryUsageBeforeGc() : gcInfo.getMemoryUsageAfterGc();
        long used = 0;
        boolean found = false;
        for (var pool : HEAP_POOLS) {
            var usage = usages.get(pool.getName());
            if (usage != null) {
                used += usage.getUsed();
                found = true;
            }
        }
        return found ? used : null;
    }

    /**
     * The collection usage of the Old Gen pool is what survived the last collection; the live usage is
     * a fallback for collectors that do not expose a separate collection-usage snapshot.
     */
    private static Long oldGenUsage() {
        var pool = HEAP_POOLS.stream()
                .filter(candidate -> OLD_GEN_POOL.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        if (pool == null) return null;
        var collection = pool.getCollectionUsage();
        var usage = collection != null ? collection : pool.getUsage();
        return usage == null ? null : usage.getUsed();
    }

    /**
     * Picks the most recent GC event and classifies it. A concurrent cycle reports before == after, so
     * the guard must not treat it as a point where retained memory dropped.
     */
    private static GcSample latestGc() {
        GcSample latest = null;
        for (var bean : GC_BEANS) {
            var info = bean.getLastGcInfo();
            if (info == null) continue;
            if (latest == null || info.getEndTime() > latest.info().getEndTime()) {
                var before = heapUsage(info, true);
                var after = heapUsage(info, false);
                var reclaimed = before == null || after == null ? 0 : Math.max(0, before - after);
                latest = new GcSample(info, gcKind(bean.getName()), reclaimed);
            }
        }
        return latest;
    }

    private static String gcKind(String beanName) {
        if (beanName == null) return "unknown";
        if (beanName.contains("Concurrent")) return "concurrent";
        if (beanName.contains("Old")) return "old";
        if (beanName.contains("Young")) return "young";
        return "unknown";
    }

    private static Double percent(Long used, Long max) {
        if (used == null || max == null || max <= 0) return null;
        return Math.round(used * PERCENT * 100.0 / max) / 100.0;
    }

    private static Long readLong(String path) {
        if (path == null) return null;
        try {
            var value = Files.readString(Path.of(path), StandardCharsets.US_ASCII).trim();
            if (value.isEmpty() || "max".equals(value)) return null;
            return Long.valueOf(value);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Long readRss() {
        try {
            var status = Path.of(PROC_STATUS);
            if (!Files.isReadable(status)) return null;
            for (var line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (!line.startsWith("VmRSS:")) continue;
                var parts = line.trim().split("\\s+");
                if (parts.length >= 2) return Long.parseLong(parts[1]) * KILOBYTE;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String firstReadable(String... candidates) {
        for (var candidate : candidates) {
            if (readable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean readable(String candidate) {
        try {
            return Files.isReadable(Path.of(candidate));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private JvmMemoryMetrics() {
    }

    private record GcSample(GcInfo info, String kind, long reclaimedBytes) {
    }

    public record Snapshot(Long heapUsed,
                           Long heapMax,
                           Double heapUsedPercent,
                           Long heapBeforeGc,
                           Double heapBeforeGcPercent,
                           Long heapAfterGc,
                           Double heapAfterGcPercent,
                           Long gcId,
                           Long gcAgeMs,
                           Long containerMemory,
                           Long containerMemoryMax,
                           Double containerMemoryPercent,
                           Long rss,
                           Long oldGen,
                           Double oldGenPercent,
                           String gcKind,
                           Long gcReclaimedBytes) {
    }
}
