package ai.core.cli.graalvm;

import com.fasterxml.jackson.databind.JavaType;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Jackson's OptionalHandlerFactory resolves optional DOM/JAXB/SQL handlers via Class.forName,
 * which drags org.w3c.dom, javax.xml (Xerces) and java.sql into the native image. The CLI never
 * serializes those types, so the reflective instantiation is cut off here.
 */
@TargetClass(com.fasterxml.jackson.databind.ext.OptionalHandlerFactory.class)
final class TargetOptionalHandlerFactory {
    @Substitute
    Object instantiate(String className, JavaType valueType) {
        return null;
    }

    @Substitute
    Object instantiate(Class<?> handler, JavaType valueType) {
        return null;
    }
}
