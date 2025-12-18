package ai.core.api.tool.function;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author stephen
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CoreAiMethod {
    String name() default "";
    String description();
    boolean needAuth() default false;
    boolean continueAfterSlash() default true;
    boolean directReturn() default false;
}
