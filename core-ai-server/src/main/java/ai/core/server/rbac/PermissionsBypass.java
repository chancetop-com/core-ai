package ai.core.server.rbac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route as exempt from RBAC action checks.
 * <p>
 * Used for protocol surfaces (A2A, OpenAI-compatible gateway proxy, litellm proxy,
 * channel inbound) and personal self-service surfaces ({@code /api/user/*},
 * {@code /api/auth/me}). The route still requires authentication via
 * {@code AuthInterceptor}; only the role-based permission check is skipped.
 *
 * @author stephen
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionsBypass {
}
