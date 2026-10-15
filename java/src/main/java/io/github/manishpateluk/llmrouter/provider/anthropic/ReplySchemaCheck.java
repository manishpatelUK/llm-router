package io.github.manishpateluk.llmrouter.provider.anthropic;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks a parsed JSON reply against the caller's {@code responseSchema}, for replies the provider
 * didn't enforce the schema on. Covers the keywords that decide a reply's shape: {@code type},
 * {@code enum}, {@code const}, {@code properties}, {@code required}, {@code additionalProperties:
 * false}, {@code items}, {@code anyOf}, {@code allOf} and local {@code $ref}s into
 * {@code $defs}/{@code definitions}. Value constraints ({@code minimum}, {@code pattern},
 * {@code format}...) aren't checked; Anthropic doesn't enforce them natively either.
 */
final class ReplySchemaCheck {

    private ReplySchemaCheck() {
    }

    /** The first way {@code value} breaks {@code schema}, naming its JSON path, or {@code null} if it fits. */
    static String problem(Map<String, Object> schema, Object value) {
        return problem(schema, schema, value, "$");
    }

    @SuppressWarnings("unchecked")
    private static String problem(Map<String, Object> root, Map<?, ?> schema, Object value, String path) {
        if (schema.get("$ref") instanceof String ref) {
            Map<?, ?> target = resolve(root, ref);
            if (target == null) {
                return null; // not ours to judge: AnthropicSchemas reports unresolvable refs
            }
            String problem = problem(root, target, value, path);
            if (problem != null) {
                return problem;
            }
        }
        if (schema.containsKey("type") && !matchesType(schema.get("type"), value)) {
            return path + " should be " + schema.get("type") + " but is " + describe(value);
        }
        if (schema.get("enum") instanceof List<?> allowed && allowed.stream().noneMatch(a -> sameJson(a, value))) {
            return path + " is " + describe(value) + ", not one of " + allowed;
        }
        if (schema.containsKey("const") && !sameJson(schema.get("const"), value)) {
            return path + " should be " + schema.get("const");
        }
        if (schema.get("allOf") instanceof List<?> all) {
            for (Object branch : all) {
                if (branch instanceof Map<?, ?> branchSchema) {
                    String problem = problem(root, branchSchema, value, path);
                    if (problem != null) {
                        return problem;
                    }
                }
            }
        }
        if (schema.get("anyOf") instanceof List<?> any && !any.isEmpty()
                && any.stream().noneMatch(b -> b instanceof Map<?, ?> m && problem(root, m, value, path) == null)) {
            return path + " matches none of its anyOf schemas";
        }
        if (value instanceof Map<?, ?> object) {
            return objectProblem(root, schema, (Map<String, Object>) object, path);
        }
        if (value instanceof List<?> array && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < array.size(); i++) {
                String problem = problem(root, items, array.get(i), path + "[" + i + "]");
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    private static String objectProblem(Map<String, Object> root, Map<?, ?> schema, Map<String, Object> object, String path) {
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                if (!object.containsKey(String.valueOf(name))) {
                    return path + "." + name + " is missing";
                }
            }
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String childPath = path + "." + entry.getKey();
            if (properties.get(entry.getKey()) instanceof Map<?, ?> propertySchema) {
                String problem = problem(root, propertySchema, entry.getValue(), childPath);
                if (problem != null) {
                    return problem;
                }
            } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                return childPath + " isn't in the schema";
            }
        }
        return null;
    }

    private static Map<?, ?> resolve(Map<String, Object> root, String ref) {
        for (String prefix : List.of("#/$defs/", "#/definitions/")) {
            if (ref.startsWith(prefix) && root.get(prefix.substring(2, prefix.length() - 1)) instanceof Map<?, ?> defs
                    && defs.get(ref.substring(prefix.length())) instanceof Map<?, ?> target) {
                return target;
            }
        }
        return ref.equals("#") ? root : null;
    }

    private static boolean matchesType(Object type, Object value) {
        if (type instanceof List<?> types) {
            return types.stream().anyMatch(t -> matchesType(t, value));
        }
        return switch (String.valueOf(type)) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isWhole(n);
            default -> true;
        };
    }

    private static boolean isWhole(Number n) {
        double d = n.doubleValue();
        return !Double.isInfinite(d) && d == Math.rint(d);
    }

    /** JSON equality: numbers compare by value, so an enum's {@code 1} matches a parsed {@code 1.0}. */
    private static boolean sameJson(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "an object";
        }
        if (value instanceof List) {
            return "an array";
        }
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }
}
