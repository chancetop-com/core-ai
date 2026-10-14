package ai.core.server.web.auth;

import ai.core.server.apiuser.ApiUserKeyService;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.UnauthorizedException;

import java.util.List;

/**
 * Resolves the {@code X-Subuser-Id} delegation header for api key requests: the header declares which
 * sub user a {@code cmk_} call acts for, and it only takes effect after the server verifies that the
 * target is an active api user owned by the key's manager. The header is never a credential — it is
 * a statement of representation that the server validates on its own. Without the header the request
 * keeps the key owner's identity. HTTP and SSE entry points share this resolver so both apply the
 * same rules.
 *
 * @author stephen
 */
public class SubUserDelegationResolver {
    public static final String HEADER = "X-Subuser-Id";

    private static final String NOT_API_KEY_MESSAGE = "X-Subuser-Id is only valid with an api key";
    private static final String MANAGEMENT_API_MESSAGE = "management api does not accept X-Subuser-Id";
    private static final String NOT_DELEGABLE_MESSAGE = "X-Subuser-Id is not supported on this route";
    // surfaces where "acting as the sub user" is defined; extend by adding one entry plus a test
    private static final List<String> DELEGABLE_PREFIXES = List.of("/api/agents", "/api/runs", "/api/llm", "/api/sessions");

    /**
     * Rejects the header on requests that do not authenticate with a ctk_/cmk_ bearer key; silently
     * ignoring it would let the caller believe the call is attributed to the sub user when it is not.
     */
    public static void rejectHeaderIfPresent(Request request) {
        if (carriesHeader(request)) throw new BadRequestException(NOT_API_KEY_MESSAGE);
    }

    /** The management surface ({@code /api/api-users}) never acts for a sub user. */
    public static void rejectHeaderOnManagementSurface(Request request) {
        if (carriesHeader(request)) throw new BadRequestException(MANAGEMENT_API_MESSAGE);
    }

    /** Only the surfaces enumerated in {@link #DELEGABLE_PREFIXES} accept the header. */
    public static void requireDelegableSurface(String path, Request request) {
        if (carriesHeader(request) && !isDelegableSurface(path)) throw new BadRequestException(NOT_DELEGABLE_MESSAGE);
    }

    /** Applies the resolved identity to the request context; the action log records the delegation. */
    public static void apply(WebContext context, Delegation delegation) {
        context.put(AuthContext.USER_ID_KEY, delegation.effectiveUserId());
        context.put(AuthContext.KEY_ID_KEY, delegation.keyId());
        if (!delegation.delegated()) return;
        context.put(AuthContext.DELEGATOR_ID_KEY, delegation.delegatorUserId());
        ActionLogContext.put("user_id", delegation.effectiveUserId());
        ActionLogContext.put("delegated_by", delegation.delegatorUserId());
        ActionLogContext.put("key_id", delegation.keyId());
    }

    private static boolean carriesHeader(Request request) {
        var header = request.header(HEADER);
        return header.isPresent() && !header.get().isBlank();
    }

    private static boolean isDelegableSurface(String path) {
        for (var prefix : DELEGABLE_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    @Inject
    MongoCollection<User> userCollection;

    /**
     * Resolves the effective identity of an already-authenticated api key request; the caller must
     * have verified the surface accepts delegation. Target validation order: management scope,
     * target exists as an api user owned by the key's manager (the manager itself never qualifies),
     * target active.
     */
    public Delegation resolve(Request request, RequestAuthenticator.AuthResult authResult) {
        var header = request.header(HEADER);
        if (header.isEmpty() || header.get().isBlank()) {
            return new Delegation(authResult.userId(), authResult.keyId(), null);
        }
        if (!ApiUserKeyService.SCOPE_MANAGE.equals(authResult.scope())) {
            throw new ForbiddenException("X-Subuser-Id requires a management key");
        }
        var target = userCollection.get(header.get().trim()).orElse(null);
        if (target == null || !ApiUserService.USER_TYPE_API.equals(target.userType) || target.ownerId == null) {
            throw new ForbiddenException("invalid sub user");
        }
        if (!target.ownerId.equals(authResult.userId())) {
            throw new ForbiddenException("sub user does not belong to current manager");
        }
        if (!"active".equals(target.status)) throw new UnauthorizedException("sub user disabled");
        return new Delegation(target.id, authResult.keyId(), authResult.userId());
    }

    public record Delegation(String effectiveUserId, String keyId, String delegatorUserId) {
        public boolean delegated() {
            return delegatorUserId != null;
        }
    }
}
