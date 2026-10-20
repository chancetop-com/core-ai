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
 * JVM and container memory numbers that the per-minute runtime sample keeps. The heap watermark read by
 * {@link RuntimeCapacityGuard} is an instantaneous used/max ratio; the post-GC and peak values here are
 * what separate retained state from garbage when the numbers are used for capacity planning.
 *
 * @author stephen
 */
public final class JvmMemoryMetrics {
    private static final double PERCENT = 100.0;
    private static final long KILOBYTE = 1024;
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
        var gcInfo = latestGcInfo();
        Long heapBeforeGc = heapUsage(gcInfo, true);
        Long heapAfterGc = heapUsage(gcInfo, false);
        Long containerMemory = readLong(CGROUP_MEMORY);
        Long containerMemoryMax = readLong(CGROUP_MEMORY_MAX);
        Long gcId = gcInfo == null ? null : gcInfo.getId();
        Long gcAgeMs = gcInfo == null ? null : Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime() - gcInfo.getEndTime());
        return new Snapshot(heapUsed, heapMax, percent(heapUsed, heapMax),
                heapBeforeGc, percent(heapBeforeGc, heapMax),
                heapAfterGc, percent(heapAfterGc, heapMax), gcId, gcAgeMs,
                containerMemory, containerMemoryMax, percent(containerMemory, containerMemoryMax),
                readRss());
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

    private static GcInfo latestGcInfo() {
        GcInfo latest = null;
        for (var bean : GC_BEANS) {
            var info = bean.getLastGcInfo();
            if (info != null && (latest == null || info.getEndTime() > latest.getEndTime())) {
                latest = info;
            }
        }
        return latest;
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
                           Long rss) {
    }
}
