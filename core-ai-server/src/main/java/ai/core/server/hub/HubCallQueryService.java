package ai.core.server.hub;

import ai.core.api.server.hub.HubCallView;
import ai.core.api.server.hub.ListHubCallsResponse;
import ai.core.server.domain.HubCall;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.bson.BsonNull;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read side of {@code hub_calls}: the filtered, paged list of hub tool executions behind the
 * observability "Hub Calls" page. Every query is anchored to the {@code created_at} window so
 * MongoDB (with {@code notablescan} on) always has an index to scan; the user visibility scope is
 * applied by the caller, not here.
 *
 * @author stephen
 */
public class HubCallQueryService {
    public static final Set<String> KINDS = Set.of(HubCallAuditService.KIND_MCP_TOOL, HubCallAuditService.KIND_API_TOOL, HubCallAuditService.KIND_AGENT);
    private static final Set<String> STATES = Set.of("completed", "failed", "unknown");

    @Inject
    MongoCollection<HubCall> callCollection;
    @Inject
    MongoCollection<User> userCollection;

    public ListHubCallsResponse list(HubCallListFilter filter) {
        var mongoFilter = mongoFilter(filter);
        var response = new ListHubCallsResponse();
        response.total = callCollection.count(mongoFilter);
        var query = new Query();
        query.filter = mongoFilter;
        query.sort = Sorts.descending("created_at");
        query.skip = filter.offset;
        query.limit = filter.limit;
        response.calls = toViews(callCollection.find(query));
        return response;
    }

    private Bson mongoFilter(HubCallListFilter filter) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.gte("created_at", filter.startFrom));
        if (filter.startTo != null) filters.add(Filters.lte("created_at", filter.startTo));
        addKindFilter(filters, filter.kind);
        if (hasText(filter.userId)) filters.add(Filters.eq("user_id", filter.userId.trim()));
        if (hasText(filter.source)) filters.add(Filters.eq("source", filter.source.trim().toLowerCase(Locale.ROOT)));
        addStateFilter(filters, filter.state);
        return Filters.and(filters);
    }

    private void addKindFilter(List<Bson> filters, String kind) {
        if (!hasText(kind)) return;
        var value = kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(value)) throw new BadRequestException("kind must be one of " + KINDS);
        filters.add(Filters.eq("kind", value));
    }

    // success is null (or absent) on rows whose call never completed — reported as state=unknown
    private void addStateFilter(List<Bson> filters, String state) {
        if (!hasText(state)) return;
        switch (state.trim().toLowerCase(Locale.ROOT)) {
            case "completed" -> filters.add(Filters.eq("success", Boolean.TRUE));
            case "failed" -> filters.add(Filters.eq("success", Boolean.FALSE));
            case "unknown" -> filters.add(Filters.eq("success", BsonNull.VALUE));
            default -> throw new BadRequestException("state must be one of " + STATES);
        }
    }

    private List<HubCallView> toViews(List<HubCall> calls) {
        var accountCache = new HashMap<String, Account>();
        return calls.stream().map(call -> toView(call, accountCache)).toList();
    }

    private HubCallView toView(HubCall call, Map<String, Account> accountCache) {
        var view = new HubCallView();
        view.id = call.id;
        view.kind = call.kind;
        view.source = call.source;
        view.target = call.target;
        view.group = call.group;
        view.name = call.name;
        view.refId = call.refId;
        view.userId = call.userId;
        view.userType = call.userType;
        var account = accountFor(call.userId, accountCache);
        view.userName = account.name;
        view.userEmail = account.email;
        view.success = call.success;
        view.isError = call.isError;
        view.statusCode = call.statusCode;
        view.durationMs = call.durationMs;
        view.outputBytes = call.outputBytes;
        view.errorMessage = call.errorMessage;
        view.argsHash = call.argsHash;
        view.argsPreview = call.argsPreview;
        view.taskId = call.taskId;
        view.contextId = call.contextId;
        view.inputTokens = call.inputTokens;
        view.outputTokens = call.outputTokens;
        view.createdAt = call.createdAt;
        return view;
    }

    private Account accountFor(String userId, Map<String, Account> accountCache) {
        if (!hasText(userId)) return Account.EMPTY;
        return accountCache.computeIfAbsent(userId, id -> userCollection.get(id)
                .map(user -> new Account(user.name, user.email))
                .orElse(Account.EMPTY));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Account(String name, String email) {
        private static final Account EMPTY = new Account(null, null);
    }
}
