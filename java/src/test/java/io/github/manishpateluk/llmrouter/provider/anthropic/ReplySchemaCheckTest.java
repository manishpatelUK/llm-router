package io.github.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReplySchemaCheckTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("status", "count", "owner"),
            "properties", Map.of(
                    "status", Map.of("type", "string", "enum", List.of("open", "closed")),
                    "count", Map.of("type", "integer", "minimum", 0),
                    "owner", Map.of("$ref", "#/$defs/person"),
                    "note", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null")))),
            "$defs", Map.of("person", Map.of(
                    "type", "object",
                    "required", List.of("name"),
                    "properties", Map.of("name", Map.of("type", "string")))));

    @Test
    void acceptsAReplyThatFits() {
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("open", 3.0, Map.of("name", "Ada"), null))).isNull();
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("closed", 0, Map.of("name", "Ada", "age", 36), "x"))).isNull();
    }

    @Test
    void namesThePathOfTheFirstProblem() {
        assertThat(ReplySchemaCheck.problem(SCHEMA, List.of())).isEqualTo("$ should be object but is an array");
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("pending", 1, Map.of("name", "Ada"), null)))
                .isEqualTo("$.status is \"pending\", not one of [open, closed]");
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("open", 1.5, Map.of("name", "Ada"), null)))
                .isEqualTo("$.count should be integer but is 1.5");
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("open", 1, Map.of(), null))).isEqualTo("$.owner.name is missing");
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply("open", 1, Map.of("name", "Ada"), 7)))
                .isEqualTo("$.note matches none of its anyOf schemas");
        assertThat(ReplySchemaCheck.problem(SCHEMA, Map.of("status", "open", "count", 1)))
                .isEqualTo("$.owner is missing");
    }

    @Test
    void rejectsPropertiesTheSchemaClosesOff() {
        Map<String, Object> reply = new HashMap<>(reply("open", 1, Map.of("name", "Ada"), null));
        reply.put("extra", true);
        assertThat(ReplySchemaCheck.problem(SCHEMA, reply)).isEqualTo("$.extra isn't in the schema");
    }

    @Test
    void checksEveryArrayItem() {
        Map<String, Object> schema = Map.of("type", "array", "items", Map.of("type", "string"));
        assertThat(ReplySchemaCheck.problem(schema, Arrays.asList("a", "b"))).isNull();
        assertThat(ReplySchemaCheck.problem(schema, Arrays.asList("a", 2))).isEqualTo("$[1] should be string but is 2");
    }

    private static Map<String, Object> reply(String status, Number count, Map<String, Object> owner, Object note) {
        Map<String, Object> reply = new HashMap<>();
        reply.put("status", status);
        reply.put("count", count);
        reply.put("owner", owner);
        reply.put("note", note);
        return reply;
    }
}
