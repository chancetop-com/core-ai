package ai.core.server.seoops;

import ai.core.api.server.seoops.SeoOpsApiModels.PageRequest;
import ai.core.server.domain.User;
import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoMerchant;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, actor-scoped read models for the SEO operations workspace.
 *
 * @author xander
 */
public class SeoOpsQueryService {
    static final MerchantTaskCounts EMPTY_COUNTS = new MerchantTaskCounts(0, 0, 0, 0, Set.of());

    @Inject
    MongoCollection<SeoMerchant> merchantCollection;

    @Inject
    MongoCollection<SeoLocation> locationCollection;

    @Inject
    MongoCollection<SeoTask> taskCollection;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    SeoMerchantService merchantService;

    @Inject
    SeoOpsViewMapper viewMapper;

    public PortfolioData portfolio(String actorUserId) {
        var merchants = merchantCollection.find(Filters.eq("operator_user_ids", requireActor(actorUserId)));
        if (merchants.isEmpty()) return new PortfolioData(List.of(), Map.of(), Map.of(), Map.of());
        var merchantIds = merchants.stream().map(merchant -> merchant.id).toList();
        var locations = locationCollection.find(Filters.in("merchant_id", merchantIds));
        var tasks = taskCollection.find(Filters.in("merchant_id", merchantIds));
        var operatorIds = merchants.stream().flatMap(merchant -> merchant.operatorUserIds.stream())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var users = operatorIds.isEmpty() ? List.<User>of() : userCollection.find(Filters.in("_id", operatorIds));
        return new PortfolioData(merchants, groupLocations(locations), countTasks(merchantIds, tasks),
            users.stream().collect(java.util.stream.Collectors.toMap(user -> user.id, user -> user.name)));
    }

    public Page<SeoTask> inbox(String actorUserId, PageRequest request) {
        var page = pageRequest(request);
        var visibleIds = merchantService.visibleMerchantIds(requireActor(actorUserId));
        if (visibleIds.isEmpty() || requestedInvisibleMerchant(request, visibleIds)) return Page.empty(page);
        var filter = taskFilter(visibleIds, request);
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.orderBy(Sorts.ascending("priority_rank"), Sorts.ascending("due_at"),
            Sorts.descending("updated_at"));
        query.skip = page.offset();
        query.limit = page.limit();
        var items = taskCollection.find(query);
        return new Page<>(items, page.offset(), page.limit(), taskCollection.count(filter));
    }

    public Page<ReviewItem> reviews(String actorUserId, PageRequest request) {
        var page = pageRequest(request);
        var visibleIds = merchantService.visibleMerchantIds(requireActor(actorUserId));
        if (visibleIds.isEmpty() || requestedInvisibleMerchant(request, visibleIds)) return Page.empty(page);
        var tasks = taskCollection.find(taskFilter(visibleIds, request));
        var items = tasks.stream().map(task -> new ReviewItem(task, viewMapper.reviewClassification(task))).toList();
        return slice(items, page);
    }

    public Page<ReportItem> reports(String actorUserId, PageRequest request) {
        var page = pageRequest(request);
        var range = captureRange(request);
        var visibleIds = merchantService.visibleMerchantIds(requireActor(actorUserId));
        if (visibleIds.isEmpty() || requestedInvisibleMerchant(request, visibleIds)) return Page.empty(page);
        var tasks = taskCollection.find(taskFilter(visibleIds, request));
        var items = tasks.stream().flatMap(task -> reportItems(task, request, range).stream())
            .sorted(Comparator.comparing((ReportItem item) -> item.evidence().capturedAt).reversed()).toList();
        return slice(items, page);
    }

    public SeoTask requireVisibleTask(String actorUserId, String taskId) {
        var task = taskId == null ? null : taskCollection.get(taskId).orElse(null);
        if (task == null) throw new NotFoundException("task not found");
        merchantService.requireVisibleMerchant(requireActor(actorUserId), task.merchantId);
        return task;
    }

    public Page<SeoTask.TaskEvent> events(String actorUserId, String taskId, PageRequest request) {
        var page = pageRequest(request);
        var task = requireVisibleTask(actorUserId, taskId);
        var events = new ArrayList<>(task.events);
        events.sort(Comparator.comparing((SeoTask.TaskEvent event) -> event.occurredAt).reversed());
        return slice(events, page);
    }

    public Map<String, String> merchantNames(List<SeoTask> tasks) {
        var ids = tasks.stream().map(task -> task.merchantId).collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return merchantCollection.find(Filters.in("_id", ids)).stream()
            .collect(java.util.stream.Collectors.toMap(merchant -> merchant.id, merchant -> merchant.displayName));
    }

    public Map<String, String> locationNames(List<SeoTask> tasks) {
        var ids = tasks.stream().map(task -> task.locationId).filter(id -> id != null)
            .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return locationCollection.find(Filters.in("_id", ids)).stream()
            .collect(java.util.stream.Collectors.toMap(location -> location.id, location -> location.displayName));
    }

    private Bson taskFilter(List<String> visibleIds, PageRequest request) {
        var filters = new ArrayList<Bson>();
        if (request != null && request.merchantId != null && !request.merchantId.isBlank()) {
            filters.add(Filters.eq("merchant_id", request.merchantId));
        } else {
            filters.add(Filters.in("merchant_id", visibleIds));
        }
        if (request != null) {
            addEquals(filters, "location_id", request.locationId);
            addEnumEquals(filters, "status", request.status, SeoTaskStatus.class);
            addEquals(filters, "owner_id", request.ownerId);
            addEnumEquals(filters, "evidence_state", request.evidenceState, SeoEvidenceState.class);
        }
        return Filters.and(filters);
    }

    private List<ReportItem> reportItems(SeoTask task, PageRequest request, CaptureRange range) {
        var items = new ArrayList<ReportItem>();
        for (var evidence : task.evidenceRefs) {
            if (!task.taskRevision.equals(evidence.taskRevision) || evidence.type == null
                || !evidence.type.endsWith("_REPORT")) continue;
            if (request != null && hasText(request.reportType)
                && !evidence.type.equals(request.reportType.toUpperCase(Locale.ROOT))) continue;
            if (!range.includes(evidence.capturedAt)) continue;
            var freshness = viewMapper.freshness(evidence.capturedAt);
            if (request != null && hasText(request.freshness)
                && !freshness.equals(request.freshness.toUpperCase(Locale.ROOT))) continue;
            items.add(new ReportItem(task, evidence, freshness));
        }
        return items;
    }

    private Map<String, List<SeoLocation>> groupLocations(List<SeoLocation> locations) {
        var grouped = new HashMap<String, List<SeoLocation>>();
        for (var location : locations) grouped.computeIfAbsent(location.merchantId, ignored -> new ArrayList<>()).add(location);
        grouped.values().forEach(items -> items.sort(Comparator.comparing(location -> location.displayName)));
        return grouped;
    }

    private Map<String, MerchantTaskCounts> countTasks(List<String> merchantIds, List<SeoTask> tasks) {
        var mutable = new HashMap<String, MutableCounts>();
        merchantIds.forEach(id -> mutable.put(id, new MutableCounts()));
        var now = ZonedDateTime.now();
        for (var task : tasks) {
            var counts = mutable.computeIfAbsent(task.merchantId, ignored -> new MutableCounts());
            counts.tasks++;
            if (task.status == SeoTaskStatus.BLOCKED) counts.blocked++;
            if (task.status == SeoTaskStatus.READY_FOR_APPROVAL) counts.ready++;
            if (task.dueAt != null && task.dueAt.isBefore(now) && task.status != SeoTaskStatus.APPROVED) counts.overdue++;
            if (task.ownerId != null) counts.owners.add(task.ownerId);
        }
        var result = new HashMap<String, MerchantTaskCounts>();
        mutable.forEach((id, counts) -> result.put(id, counts.freeze()));
        return result;
    }

    private CaptureRange captureRange(PageRequest request) {
        var from = parseTime(request == null ? null : request.capturedFrom, "captured_from");
        var to = parseTime(request == null ? null : request.capturedTo, "captured_to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("captured_from must not be after captured_to");
        }
        return new CaptureRange(from, to);
    }

    private ZonedDateTime parseTime(String value, String field) {
        if (!hasText(value)) return null;
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be an ISO-8601 date-time", "INVALID_DATE_TIME", e);
        }
    }

    private void addEquals(List<Bson> filters, String field, String value) {
        if (hasText(value)) filters.add(Filters.eq(field, value));
    }

    private <E extends Enum<E>> void addEnumEquals(List<Bson> filters, String field, String value, Class<E> type) {
        if (!hasText(value)) return;
        try {
            filters.add(Filters.eq(field, Enum.valueOf(type, value.toUpperCase(Locale.ROOT))));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(field + " is invalid", "INVALID_FILTER", e);
        }
    }

    private boolean requestedInvisibleMerchant(PageRequest request, List<String> visibleIds) {
        return request != null && hasText(request.merchantId) && !visibleIds.contains(request.merchantId);
    }

    private PageSpec pageRequest(PageRequest request) {
        var offset = request == null || request.offset == null ? 0 : Math.max(0, request.offset);
        var requestedLimit = request == null || request.limit == null ? 50 : request.limit;
        return new PageSpec(offset, Math.max(1, Math.min(100, requestedLimit)));
    }

    private <T> Page<T> slice(List<T> items, PageSpec page) {
        var from = Math.min(page.offset(), items.size());
        var to = Math.min(from + page.limit(), items.size());
        return new Page<>(items.subList(from, to), page.offset(), page.limit(), items.size());
    }

    private String requireActor(String actorUserId) {
        if (!hasText(actorUserId)) throw new NotFoundException("actor not found");
        return actorUserId;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Page<T>(List<T> items, int offset, int limit, long total) {
        static <T> Page<T> empty(PageSpec page) {
            return new Page<>(List.of(), page.offset(), page.limit(), 0);
        }
    }

    record MerchantTaskCounts(long tasks, long blocked, long readyForApproval, long overdue, Set<String> ownerIds) {
    }

    record PortfolioData(List<SeoMerchant> merchants, Map<String, List<SeoLocation>> locationsByMerchant,
                         Map<String, MerchantTaskCounts> countsByMerchant, Map<String, String> operatorNamesById) {
    }

    record ReviewItem(SeoTask task, String classification) {
    }

    record ReportItem(SeoTask task, SeoTask.EvidenceRef evidence, String freshness) {
    }

    private record PageSpec(int offset, int limit) {
    }

    private record CaptureRange(ZonedDateTime from, ZonedDateTime to) {
        boolean includes(ZonedDateTime capturedAt) {
            return capturedAt != null && (from == null || !capturedAt.isBefore(from))
                && (to == null || !capturedAt.isAfter(to));
        }
    }

    private static final class MutableCounts {
        long tasks;
        long blocked;
        long ready;
        long overdue;
        final Set<String> owners = new LinkedHashSet<>();

        MerchantTaskCounts freeze() {
            return new MerchantTaskCounts(tasks, blocked, ready, overdue, Set.copyOf(owners));
        }
    }
}
