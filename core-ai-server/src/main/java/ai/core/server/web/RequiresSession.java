package ai.core.server.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a controller method as session-scoped. When this annotation is present,
 * the {@link SessionRoutingInterceptor} checks whether the current Pod owns
 * the session. If not, the request is forwarded to the owner Pod via RPC.
 * <p>
 * The annotated method's first parameter must be the session ID (String),
 * and the second parameter (if present) must be the request body bean.
 * <p>
 * Example:
 * <pre>{@code
 * @RequiresSession
 * public LoadToolsResponse loadTools(String sessionId, LoadToolsRequest request) {
 *     // ...
 * }
 * }</pre>
 *
 * @author stephen
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresSession {
}
