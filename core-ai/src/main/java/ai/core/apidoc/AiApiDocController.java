package ai.core.apidoc;

import ai.core.api.apidefinition.AiApiDefinition;
import core.framework.http.ContentType;
import core.framework.internal.web.http.IPAccessControl;
import core.framework.json.JSON;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AiApiDocController implements Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiApiDocController.class);

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private final IPAccessControl accessControl;
    private final Set<Class<?>> serviceInterfaces;
    private final Set<Class<?>> beanClasses;
    private final String appDescription;
    private final boolean warnOnMissingDescription;
    private final boolean failOnMissingDescription;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile AiApiDefinition definition;

    public AiApiDocController(IPAccessControl accessControl,
                              Set<Class<?>> serviceInterfaces,
                              Set<Class<?>> beanClasses,
                              String appDescription,
                              boolean warnOnMissingDescription,
                              boolean failOnMissingDescription) {
        this.accessControl = accessControl;
        this.serviceInterfaces = serviceInterfaces;
        this.beanClasses = beanClasses;
        this.appDescription = appDescription;
        this.warnOnMissingDescription = warnOnMissingDescription;
        this.failOnMissingDescription = failOnMissingDescription;
    }

    @Override
    public Response execute(Request request) {
        accessControl.validate(request.clientIP());
        return Response.text(JSON.toJSON(definition())).contentType(ContentType.APPLICATION_JSON);
    }

    public void initialize() {
        try {
            definition();
        } catch (RuntimeException e) {
            LOGGER.error("failed to initialize core-ai api definition", e);
        }
    }

    private AiApiDefinition definition() {
        var result = definition;
        if (result != null) {
            return result;
        }
        lock.lock();
        try {
            if (definition == null) {
                var builder = new AiApiDocBuilder(serviceInterfaces, beanClasses, appDescription);
                definition = builder.build();
                validateCoverage(definition);
            }
            return definition;
        } finally {
            lock.unlock();
        }
    }

    private void validateCoverage(AiApiDefinition definition) {
        var coverage = coverage(definition);
        if (warnOnMissingDescription && coverage.hasMissing()) {
            LOGGER.warn("core-ai api definition is missing descriptions: app={}, services={}, operations={}/{}, pathParams={}/{}, requestFields={}/{}, fields={}/{}",
                    definition.app, definition.services.size(),
                    coverage.describedOperations, coverage.operations,
                    coverage.describedPathParams, coverage.pathParams,
                    coverage.describedRequestFields, coverage.requestFields,
                    coverage.describedFields, coverage.fields);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("core-ai api definition missing descriptions: {}", coverage.missing.stream().limit(50).toList());
            }
        } else {
            LOGGER.info("core-ai api definition: app={}, services={}, operations={}/{}, pathParams={}/{}, requestFields={}/{}, fields={}/{}",
                    definition.app, definition.services.size(),
                    coverage.describedOperations, coverage.operations,
                    coverage.describedPathParams, coverage.pathParams,
                    coverage.describedRequestFields, coverage.requestFields,
                    coverage.describedFields, coverage.fields);
        }
        if (failOnMissingDescription && coverage.hasMissing()) {
            throw new Error("core-ai api definition is missing descriptions: " + coverage.missing.stream().limit(50).toList());
        }
    }

    private Coverage coverage(AiApiDefinition definition) {
        var coverage = new Coverage();
        var typeMap = definition.types.stream().collect(Collectors.toMap(type -> type.name, type -> type, (left, right) -> left));
        for (var service : definition.services) {
            for (var operation : service.operations) {
                countOperation(coverage, service.name, operation, typeMap);
            }
        }
        for (var type : definition.types) {
            countType(coverage, type);
        }
        return coverage;
    }

    private void countOperation(Coverage coverage, String serviceName, AiApiDefinition.Operation operation, Map<String, AiApiDefinition.Type> typeMap) {
        coverage.operations++;
        if (hasText(operation.description)) {
            coverage.describedOperations++;
        } else {
            coverage.missing.add(serviceName + "." + operation.name);
        }
        for (var pathParam : operation.pathParams) {
            coverage.pathParams++;
            if (hasText(pathParam.description)) {
                coverage.describedPathParams++;
            } else {
                coverage.missing.add(serviceName + "." + operation.name + "." + pathParam.name);
            }
        }
        var requestType = operation.requestType == null ? null : typeMap.get(operation.requestType);
        if (requestType != null) {
            countRequestFields(coverage, requestType);
        }
    }

    private void countRequestFields(Coverage coverage, AiApiDefinition.Type type) {
        if (type.fields == null) {
            return;
        }
        for (var field : type.fields) {
            coverage.requestFields++;
            if (hasText(field.description)) {
                coverage.describedRequestFields++;
            } else {
                coverage.missing.add(type.name + "." + field.name);
            }
        }
    }

    private void countType(Coverage coverage, AiApiDefinition.Type type) {
        if (type.fields == null) {
            return;
        }
        for (var field : type.fields) {
            coverage.fields++;
            if (hasText(field.description)) {
                coverage.describedFields++;
            }
        }
    }

    private static final class Coverage {
        private int operations;
        private int describedOperations;
        private int pathParams;
        private int describedPathParams;
        private int requestFields;
        private int describedRequestFields;
        private int fields;
        private int describedFields;
        private final List<String> missing = new ArrayList<>();

        private boolean hasMissing() {
            return describedOperations < operations || describedPathParams < pathParams || describedRequestFields < requestFields;
        }
    }
}
