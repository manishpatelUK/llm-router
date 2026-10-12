package io.github.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnthropicSchemasTest {

    /** Plain objects at every depth Anthropic looks: properties, items, anyOf, $defs, nullable object. */
    static Map<String, Object> nestedSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("name", "address"),
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "address", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))),
                        "tags", Map.of("type", "array", "items", Map.of("type", "object",
                                "properties", Map.of("label", Map.of("type", "string")))),
                        "contact", Map.of("anyOf", List.of(
                                Map.of("type", "object", "properties", Map.of("email", Map.of("type", "string"))),
                                Map.of("type", "null"))),
                        "manager", Map.of("type", List.of("object", "null"), "properties", Map.of("id", Map.of("type", "string"))),
                        "owner", Map.of("$ref", "#/$defs/person")),
                "$defs", Map.of("person", Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")))));
    }

    @Test
    void everyObjectGetsAdditionalPropertiesFalse() {
        AnthropicSchemas.Conversion conversion = AnthropicSchemas.convert(nestedSchema());

        assertThat(conversion.problem()).isNull();
        Map<String, Object> schema = conversion.schema();
        assertThat(schema).containsEntry("additionalProperties", false);
        Map<Object, Object> properties = (Map<Object, Object>) schema.get("properties");
        assertThat((Map<Object, Object>) properties.get("address")).containsEntry("additionalProperties", false);
        assertThat((Map<Object, Object>) ((Map<Object, Object>) properties.get("tags")).get("items")).containsEntry("additionalProperties", false);
        assertThat((Map<Object, Object>) ((List<?>) ((Map<Object, Object>) properties.get("contact")).get("anyOf")).get(0))
                .containsEntry("additionalProperties", false);
        assertThat((Map<Object, Object>) ((List<?>) ((Map<Object, Object>) properties.get("contact")).get("anyOf")).get(1))
                .doesNotContainKey("additionalProperties");
        assertThat((Map<Object, Object>) properties.get("manager")).containsEntry("additionalProperties", false);
        assertThat((Map<Object, Object>) properties.get("name")).doesNotContainKey("additionalProperties");
        assertThat((Map<Object, Object>) ((Map<Object, Object>) schema.get("$defs")).get("person")).containsEntry("additionalProperties", false);
        assertThat((Map<Object, Object>) properties.get("owner")).isEqualTo(Map.of("$ref", "#/$defs/person"));
    }

    @Test
    void theCallersSchemaIsNotChanged() {
        Map<String, Object> address = new LinkedHashMap<>(Map.of("type", "object",
                "properties", Map.of("city", Map.of("type", "string", "maxLength", 40))));
        Map<String, Object> callers = new LinkedHashMap<>(Map.of("type", "object", "properties",
                new LinkedHashMap<>(Map.of("address", address))));
        Map<String, Object> before = Map.copyOf(callers);

        Map<String, Object> converted = AnthropicSchemas.convert(callers).schema();

        assertThat(callers).isEqualTo(before).doesNotContainKey("additionalProperties");
        assertThat(address).doesNotContainKey("additionalProperties");
        assertThat(converted).isNotSameAs(callers);
        assertThat(converted.get("properties")).isNotSameAs(callers.get("properties"));
    }

    @Test
    void explicitFalseIsKeptButTrueOrASchemaIsReportedWithItsPath() {
        assertThat(AnthropicSchemas.convert(Map.of("type", "object", "additionalProperties", false)).schema())
                .containsEntry("additionalProperties", false);

        AnthropicSchemas.Conversion open = AnthropicSchemas.convert(Map.of("type", "object", "properties",
                Map.of("meta", Map.of("type", "object", "additionalProperties", true))));
        assertThat(open.schema()).isNull();
        assertThat(open.problem()).isEqualTo("$.properties.meta.additionalProperties is true; Anthropic only accepts false");

        AnthropicSchemas.Conversion map = AnthropicSchemas.convert(Map.of("type", "object",
                "additionalProperties", Map.of("type", "string")));
        assertThat(map.problem()).contains("$.additionalProperties is a schema");
    }

    @Test
    void constraintsAnthropicCantEnforceMoveIntoTheDescription() {
        Map<String, Object> converted = AnthropicSchemas.convert(Map.of("type", "object", "properties", Map.of(
                "age", Map.of("type", "integer", "minimum", 18, "description", "Age in years."),
                "code", Map.of("type", "string", "maxLength", 5, "format", "iso-4217"),
                "when", Map.of("type", "string", "format", "date"),
                "items", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 3),
                "some", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1)))).schema();

        Map<Object, Object> properties = (Map<Object, Object>) converted.get("properties");
        assertThat((Map<Object, Object>) properties.get("age")).doesNotContainKey("minimum")
                .containsEntry("description", "Age in years. {minimum: 18}");
        assertThat((Map<Object, Object>) properties.get("code")).doesNotContainKeys("maxLength", "format")
                .extractingByKey("description").asString().contains("maxLength: 5", "format: iso-4217").startsWith("{");
        assertThat((Map<Object, Object>) properties.get("when")).containsEntry("format", "date");
        assertThat((Map<Object, Object>) properties.get("items")).doesNotContainKey("minItems").containsEntry("description", "{minItems: 3}");
        assertThat((Map<Object, Object>) properties.get("some")).containsEntry("minItems", 1);
    }

    @Test
    void featuresAnthropicCantExpressAreReportedWithPaths() {
        assertThat(AnthropicSchemas.convert(Map.of("type", "object", "properties",
                Map.of("shape", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer")))))).problem())
                .isEqualTo("$.properties.shape.oneOf: \"oneOf\" isn't supported");
        assertThat(AnthropicSchemas.convert(Map.of("type", "object", "properties", Map.of("children",
                        Map.of("type", "array", "items", Map.of("$ref", "#/$defs/node"))),
                "$defs", Map.of("node", Map.of("type", "object", "properties",
                        Map.of("children", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/node")))))))
                .problem()).contains("recursive $ref #/$defs/node");
        assertThat(AnthropicSchemas.convert(Map.of("$ref", "#")).problem()).contains("recursive $ref #");
        assertThat(AnthropicSchemas.convert(Map.of("$ref", "https://example.com/schema.json")).problem())
                .contains("$.$ref: external $ref");
        assertThat(AnthropicSchemas.convert(Map.of("type", "array", "items", List.of(Map.of("type", "string")))).problem())
                .contains("$.items: tuple-style items");
        assertThat(AnthropicSchemas.convert(Map.of("enum", List.of(Map.of("a", 1)))).problem())
                .contains("$.enum: enum values must be");
    }

    @Test
    void tooManyOptionalPropertiesIsReported() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < AnthropicSchemas.MAX_OPTIONAL_PROPERTIES + 1; i++) {
            properties.put("p" + i, Map.of("type", "string"));
        }

        assertThat(AnthropicSchemas.convert(Map.of("type", "object", "properties", properties)).problem())
                .contains("25 optional properties");
        assertThat(AnthropicSchemas.convert(Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()))).problem()).isNull();
    }
}
