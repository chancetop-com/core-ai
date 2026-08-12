package ai.core.server.rbac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the RBAC action permissions required to invoke a WebServiceImpl method
 * (or a {@code http().route(controller::method)} controller method).
 * <p>
 * The interceptor resolves the annotation from the implementation method
 * ({@code Invocation.annotation()}), which is the same mechanism core-ng uses
 * for {@code @LimitRate}. Missing annotation on a protected route is rejected
 * (fail-closed) by {@code PermissionInterceptor}.
 *
 * @author stephen
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionsRequired {
    String[] value();
}
