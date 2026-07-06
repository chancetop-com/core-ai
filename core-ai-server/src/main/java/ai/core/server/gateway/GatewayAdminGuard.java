package ai.core.server.gateway;

import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

final class GatewayAdminGuard {
    static void requireAdmin(MongoCollection<User> userCollection, String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }

    private GatewayAdminGuard() {
    }
}
