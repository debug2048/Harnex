package com.harnex.web.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.harnex.execution.ExecutionSpec;
import com.harnex.contract.run.RunEventPayloads;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

/** Describe Java's sealed union directly; an interface is not a mutually recursive object inheritance hierarchy. */
@Configuration(proxyBeanMethods = false)
public class WorkbenchContractConfiguration {
    @Bean public OpenApiCustomizer workbenchDiscriminators() {
        return document -> {
            if (document.getComponents() == null || document.getComponents().getSchemas() == null) return;
            var schemas = document.getComponents().getSchemas();
            Map<String, String> events = new TreeMap<>();
            RunEventPayloads.schemas().forEach((name, type) -> {
                var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type));
                resolved.referencedSchemas.forEach(schemas::putIfAbsent);
                events.put(name, "#/components/schemas/" + type.getSimpleName());
            });
            document.addExtension("x-harnex-event-payloads", events);
            if (!schemas.containsKey("ExecutionSpec")) return;
            Schema<Object> union = new Schema<>();
            var discriminator = new Discriminator().propertyName("kind");
            for (var subtype : ExecutionSpec.class.getAnnotation(JsonSubTypes.class).value()) {
                String name = subtype.value().getSimpleName();
                Schema<?> current = schemas.get(name);
                if (current == null) continue;
                Schema<Object> concrete = new Schema<>(); concrete.setType("object");
                if (current.getProperties() != null) concrete.setProperties(new LinkedHashMap<>(current.getProperties()));
                if (current.getRequired() != null) concrete.setRequired(new ArrayList<>(current.getRequired()));
                if (current.getAllOf() != null) for (Object raw : current.getAllOf()) {
                    Schema<?> item = (Schema<?>) raw;
                    if (item.getProperties() != null) item.getProperties().forEach((key, value) -> concrete.addProperty(key, value));
                    if (item.getRequired() != null) item.getRequired().forEach(concrete::addRequiredItem);
                }
                concrete.addProperty("kind", new StringSchema()._enum(List.of(subtype.name()))); concrete.addRequiredItem("kind");
                if (name.equals("ConversationSpec")) concrete.addRequiredItem("action");
                concrete.setAdditionalProperties(false); schemas.put(name, concrete);
                String ref = "#/components/schemas/" + name;
                union.addOneOfItem(new Schema<>().$ref(ref)); discriminator.mapping(subtype.name(), ref);
            }
            union.setDiscriminator(discriminator); schemas.put("ExecutionSpec", union);
            var visited = Collections.newSetFromMap(new IdentityHashMap<Schema<?>, Boolean>());
            schemas.values().forEach(schema -> describeJsonValues(schema, visited));
        };
    }
    private static void describeJsonValues(Schema<?> schema, Set<Schema<?>> visited) {
        if (schema == null || !visited.add(schema)) return;
        // Swagger describes Object-valued maps as maps of empty objects. Their Java values are arbitrary JSON.
        if (schema.getAdditionalProperties() instanceof Schema<?> value) {
            if ("object".equals(value.getType()) && value.getProperties() == null && value.get$ref() == null
                && value.getAdditionalProperties() == null && value.getAllOf() == null && value.getOneOf() == null) {
                schema.setAdditionalProperties(true);
            } else describeJsonValues(value, visited);
        }
        if (schema.getProperties() != null) schema.getProperties().values().forEach(value -> describeJsonValues(value, visited));
        describeJsonValues(schema.getItems(), visited);
        if (schema.getAllOf() != null) schema.getAllOf().forEach(value -> describeJsonValues((Schema<?>) value, visited));
        if (schema.getOneOf() != null) schema.getOneOf().forEach(value -> describeJsonValues((Schema<?>) value, visited));
    }
}
