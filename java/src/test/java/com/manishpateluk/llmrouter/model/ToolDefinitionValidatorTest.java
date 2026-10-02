package com.manishpateluk.llmrouter.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ToolDefinitionValidatorTest {

    private static final Map<String, Object> OBJECT_SCHEMA = Map.of("type", "object");

    private static ToolDefinition tool(String name, Map<String, Object> parameters) {
        return ToolDefinition.builder().name(name).description("d").parameters(parameters).build();
    }

    @Test
    void validToolsAndEmptyOrNullListsHaveNoProblems() {
        assertThat(ToolDefinitionValidator.validate(List.of(
                tool("get_weather", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string")))),
                tool("Search-2", OBJECT_SCHEMA),
                tool("a".repeat(64), OBJECT_SCHEMA)))).isEmpty();
        assertThat(ToolDefinitionValidator.validate(List.of())).isEmpty();
        assertThat(ToolDefinitionValidator.validate(null)).isEmpty();
    }

    @Test
    void namesOutsideTheProviderSafePatternAreRejected() {
        for (String badName : List.of("", "has space", "dotted.name", "slash/name", "a".repeat(65), "naïve")) {
            assertThat(ToolDefinitionValidator.validate(List.of(tool(badName, OBJECT_SCHEMA))))
                    .as(badName)
                    .singleElement().asString().contains("name must match ^[a-zA-Z0-9_-]{1,64}$");
        }
        assertThat(ToolDefinitionValidator.validate(List.of(tool(null, OBJECT_SCHEMA))))
                .containsExactly("tools[0]: name must match ^[a-zA-Z0-9_-]{1,64}$");
    }

    @Test
    void duplicateNamesAreRejectedOnTheSecondOccurrence() {
        assertThat(ToolDefinitionValidator.validate(List.of(
                tool("lookup", OBJECT_SCHEMA), tool("other", OBJECT_SCHEMA), tool("lookup", OBJECT_SCHEMA))))
                .containsExactly("tools[2] 'lookup': duplicate tool name");
    }

    @Test
    void parametersMustBeAnObjectSchema() {
        assertThat(ToolDefinitionValidator.validate(List.of(tool("a", null))))
                .singleElement().asString().contains("parameters must be a JSON Schema object");
        assertThat(ToolDefinitionValidator.validate(List.of(tool("b", Map.of()))))
                .containsExactly("tools[0] 'b': parameters must have \"type\": \"object\" (was null)");
        assertThat(ToolDefinitionValidator.validate(List.of(tool("c", Map.of("type", "array")))))
                .containsExactly("tools[0] 'c': parameters must have \"type\": \"object\" (was array)");
    }

    @Test
    void nullEntryIsReportedAndEveryProblemIsCollected() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(null);
        tools.add(tool("bad name", Map.of()));

        assertThat(ToolDefinitionValidator.validate(tools)).containsExactly(
                "tools[0] is null",
                "tools[1] 'bad name': name must match ^[a-zA-Z0-9_-]{1,64}$",
                "tools[1] 'bad name': parameters must have \"type\": \"object\" (was null)");
    }
}
