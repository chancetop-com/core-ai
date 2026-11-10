package ai.core.apidoc;

import ai.core.api.apidefinition.AiApiDefinition;
import ai.core.api.apidefinition.AiField;
import ai.core.api.apidefinition.AiOperation;
import ai.core.api.apidefinition.AiParam;
import ai.core.api.apidefinition.AiService;
import core.framework.api.json.Property;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.QueryParam;
import core.framework.internal.reflect.Classes;
import core.framework.internal.reflect.GenericTypes;
import core.framework.internal.reflect.Params;
import core.framework.internal.web.api.APIDefinitionBuilder;
import core.framework.internal.web.api.APIDefinitionResponse;
import core.framework.internal.web.api.APIType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class AiApiDocBuilder {
    private static final Set<Class<?>> VALUE_CLASSES = Set.of(
            String.class, Boolean.class, Integer.class, Long.class, Double.class, BigDecimal.class,
            LocalDate.class, LocalDateTime.class, ZonedDateTime.class, LocalTime.class, Instant.class
    );

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private final Set<Class<?>> serviceInterfaces;
    private final Set<Class<?>> beanClasses;
    private final String appDescription;

    public AiApiDocBuilder(Set<Class<?>> serviceInterfaces, Set<Class<?>> beanClasses, String appDescription) {
        this.serviceInterfaces = serviceInterfaces;
        this.beanClasses = beanClasses;
        this.appDescription = appDescription;
    }

    public AiApiDefinition build() {
        var base = new APIDefinitionBuilder(serviceInterfaces, beanClasses).build();
        var definition = map(base);
        definition.schema = AiApiDefinition.SCHEMA_V1;
        definition.description = text(appDescription);

        var serviceAnnotations = new HashMap<String, ServiceAnnotations>();
        var fieldAnnotations = new HashMap<String, Map<String, FieldAnnotations>>();
        var visited = new HashSet<Class<?>>();
        for (var serviceInterface : serviceInterfaces) {
            indexService(serviceInterface, serviceAnnotations, fieldAnnotations, visited);
        }
        for (var beanClass : beanClasses) {
            indexType(beanClass, fieldAnnotations, visited);
        }
        applyServiceAnnotations(definition, serviceAnnotations);
        applyFieldAnnotations(definition, fieldAnnotations);
        return definition;
    }

    private void indexService(Class<?> serviceInterface,
                              Map<String, ServiceAnnotations> serviceAnnotations,
                              Map<String, Map<String, FieldAnnotations>> fieldAnnotations,
                              Set<Class<?>> visited) {
        var service = serviceInterface.getDeclaredAnnotation(AiService.class);
        var methods = new HashMap<String, MethodAnnotations>();
        for (Method method : serviceInterface.getMethods()) {
            var operation = method.getDeclaredAnnotation(AiOperation.class);
            var pathParams = new HashMap<String, ParamAnnotations>();
            var annotations = method.getParameterAnnotations();
            var parameterTypes = method.getGenericParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {
                var pathParam = Params.annotation(annotations[i], PathParam.class);
                if (pathParam != null) {
                    var aiParam = Params.annotation(annotations[i], AiParam.class);
                    if (aiParam != null) {
                        pathParams.put(pathParam.value(), new ParamAnnotations(text(aiParam.description()), text(aiParam.example())));
                    }
                }
                indexType(parameterTypes[i], fieldAnnotations, visited);
            }
            indexType(method.getGenericReturnType(), fieldAnnotations, visited);
            if (operation != null || !pathParams.isEmpty()) {
                methods.put(method.getName(), new MethodAnnotations(
                        operation == null ? null : text(operation.description()),
                        operation == null ? null : text(operation.example()),
                        pathParams));
            }
        }
        if (service != null || !methods.isEmpty()) {
            serviceAnnotations.put(serviceInterface.getSimpleName(),
                    new ServiceAnnotations(service == null ? null : text(service.description()), methods));
        }
    }

    private void indexType(Type type, Map<String, Map<String, FieldAnnotations>> fieldAnnotations, Set<Class<?>> visited) {
        if (type == null || type == void.class) {
            return;
        }
        if (GenericTypes.isList(type)) {
            indexType(GenericTypes.listValueClass(type), fieldAnnotations, visited);
            return;
        }
        if (GenericTypes.isMap(type)) {
            indexType(GenericTypes.mapKeyClass(type), fieldAnnotations, visited);
            indexType(GenericTypes.mapValueType(type), fieldAnnotations, visited);
            return;
        }
        if (GenericTypes.isOptional(type)) {
            indexType(GenericTypes.optionalValueClass(type), fieldAnnotations, visited);
            return;
        }
        Class<?> rawClass = GenericTypes.rawClass(type);
        if (VALUE_CLASSES.contains(rawClass) || rawClass.isEnum() || !visited.add(rawClass)) {
            return;
        }
        var fields = fieldAnnotations.computeIfAbsent(Classes.className(rawClass), key -> new HashMap<>());
        for (Field field : Classes.instanceFields(rawClass)) {
            var aiField = field.getDeclaredAnnotation(AiField.class);
            if (aiField != null) {
                fields.put(fieldName(field), new FieldAnnotations(text(aiField.description()), text(aiField.example())));
            }
            indexType(field.getGenericType(), fieldAnnotations, visited);
        }
    }

    private String fieldName(Field field) {
        var property = field.getDeclaredAnnotation(Property.class);
        if (property != null) {
            return property.name();
        }
        var queryParam = field.getDeclaredAnnotation(QueryParam.class);
        if (queryParam != null) {
            return queryParam.name();
        }
        return field.getName();
    }

    private void applyServiceAnnotations(AiApiDefinition definition, Map<String, ServiceAnnotations> annotations) {
        for (var service : definition.services) {
            var serviceAnnotation = annotations.get(service.name);
            if (serviceAnnotation == null) {
                continue;
            }
            if (serviceAnnotation.description() != null) {
                service.description = serviceAnnotation.description();
            }
            for (var operation : service.operations) {
                var methodAnnotation = serviceAnnotation.methods().get(operation.name);
                if (methodAnnotation == null) {
                    continue;
                }
                if (methodAnnotation.description() != null) {
                    operation.description = methodAnnotation.description();
                }
                if (methodAnnotation.example() != null) {
                    operation.example = methodAnnotation.example();
                }
                applyPathParamAnnotations(operation, methodAnnotation);
            }
        }
    }

    private void applyPathParamAnnotations(AiApiDefinition.Operation operation, MethodAnnotations methodAnnotation) {
        for (var pathParam : operation.pathParams) {
            var paramAnnotation = methodAnnotation.pathParams().get(pathParam.name);
            if (paramAnnotation == null) {
                continue;
            }
            if (paramAnnotation.description() != null) {
                pathParam.description = paramAnnotation.description();
            }
            if (paramAnnotation.example() != null) {
                pathParam.example = paramAnnotation.example();
            }
        }
    }

    private void applyFieldAnnotations(AiApiDefinition definition, Map<String, Map<String, FieldAnnotations>> annotations) {
        for (var type : definition.types) {
            var fields = annotations.get(type.name);
            if (fields == null || type.fields == null) {
                continue;
            }
            for (var field : type.fields) {
                var fieldAnnotation = fields.get(field.name);
                if (fieldAnnotation == null) {
                    continue;
                }
                if (fieldAnnotation.description() != null) {
                    field.description = fieldAnnotation.description();
                }
                if (fieldAnnotation.example() != null) {
                    field.example = fieldAnnotation.example();
                }
            }
        }
    }

    private AiApiDefinition map(APIDefinitionResponse base) {
        var definition = new AiApiDefinition();
        definition.app = base.app;
        definition.version = base.version;
        if (base.services != null) {
            definition.services = base.services.stream().map(this::mapService).toList();
        }
        if (base.types != null) {
            definition.types = base.types.stream().map(this::mapType).toList();
        }
        return definition;
    }

    private AiApiDefinition.Service mapService(APIDefinitionResponse.Service base) {
        var service = new AiApiDefinition.Service();
        service.name = base.name;
        if (base.operations != null) {
            service.operations = base.operations.stream().map(this::mapOperation).toList();
        }
        return service;
    }

    private AiApiDefinition.Operation mapOperation(APIDefinitionResponse.Operation base) {
        var operation = new AiApiDefinition.Operation();
        operation.name = base.name;
        operation.method = base.method;
        operation.path = base.path;
        operation.requestType = base.requestType;
        operation.responseType = base.responseType;
        operation.optional = base.optional;
        operation.deprecated = base.deprecated;
        if (base.pathParams != null) {
            operation.pathParams = base.pathParams.stream().map(this::mapPathParam).toList();
        }
        return operation;
    }

    private AiApiDefinition.PathParam mapPathParam(APIDefinitionResponse.PathParam base) {
        var pathParam = new AiApiDefinition.PathParam();
        pathParam.name = base.name;
        pathParam.type = base.type;
        return pathParam;
    }

    private AiApiDefinition.Type mapType(APIType base) {
        var type = new AiApiDefinition.Type();
        type.name = base.name;
        type.type = base.type;
        type.fields = base.fields == null ? null : base.fields.stream().map(this::mapField).toList();
        type.enumConstants = base.enumConstants == null ? null : base.enumConstants.stream().map(this::mapEnumConstant).toList();
        return type;
    }

    private AiApiDefinition.Field mapField(APIType.Field base) {
        var field = new AiApiDefinition.Field();
        field.name = base.name;
        field.type = base.type;
        field.typeParams = base.typeParams;
        field.constraints = mapConstraints(base.constraints);
        return field;
    }

    private AiApiDefinition.Constraints mapConstraints(APIType.Constraints base) {
        var constraints = new AiApiDefinition.Constraints();
        if (base == null) {
            return constraints;
        }
        constraints.notNull = base.notNull;
        constraints.notBlank = base.notBlank;
        constraints.min = base.min;
        constraints.max = base.max;
        constraints.pattern = base.pattern;
        if (base.size != null) {
            var size = new AiApiDefinition.Size();
            size.min = base.size.min;
            size.max = base.size.max;
            constraints.size = size;
        }
        return constraints;
    }

    private AiApiDefinition.EnumConstant mapEnumConstant(APIType.EnumConstant base) {
        var enumConstant = new AiApiDefinition.EnumConstant();
        enumConstant.name = base.name;
        enumConstant.value = base.value;
        return enumConstant;
    }

    private record ServiceAnnotations(String description, Map<String, MethodAnnotations> methods) {
    }

    private record MethodAnnotations(String description, String example, Map<String, ParamAnnotations> pathParams) {
    }

    private record ParamAnnotations(String description, String example) {
    }

    private record FieldAnnotations(String description, String example) {
    }
}
