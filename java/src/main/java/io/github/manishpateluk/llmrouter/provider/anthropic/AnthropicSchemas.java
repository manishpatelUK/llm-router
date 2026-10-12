package io.github.manishpateluk.llmrouter.provider.anthropic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Turns a caller's JSON schema into one Anthropic's structured outputs ({@code output_config.format})
 * accept, without touching the caller's map. Anthropic compiles the schema into a grammar and
 * rejects (400) anything outside a JSON Schema subset, so:
 *
 * <ul>
 *   <li><b>Added:</b> {@code "additionalProperties": false} on every object that doesn't set it —
 *       Anthropic requires it explicitly.</li>
 *   <li><b>Relaxed:</b> constraints Anthropic can't enforce — {@code minimum}, {@code maximum},
 *       {@code exclusiveMinimum}, {@code exclusiveMaximum}, {@code multipleOf}, {@code minLength},
 *       {@code maxLength}, {@code minItems} above 1, {@code maxItems}, {@code uniqueItems},
 *       {@code minProperties}, {@code maxProperties}, and {@code format}s outside its list — are
 *       removed and appended to the property's {@code description} (e.g. {@code {minimum: 100}}),
 *       as Anthropic's own SDKs do. The model still sees them; the grammar just doesn't enforce them.</li>
 *   <li><b>Unsupported</b> (see {@link Conversion#problem()}): {@code additionalProperties} set to
 *       {@code true} or a schema (forcing it to {@code false} would change what the caller asked
 *       for, e.g. a free-form map), recursive or external {@code $ref}s, {@code allOf} with a
 *       {@code $ref}, enums of objects or arrays, tuple {@code items}/{@code prefixItems},
 *       {@code oneOf}, {@code not}, {@code if}/{@code then}/{@code else}, {@code patternProperties},
 *       {@code propertyNames}, {@code dependentSchemas}/{@code dependencies}, {@code contains},
 *       {@code unevaluatedProperties}/{@code unevaluatedItems}, and more than
 *       {@value #MAX_OPTIONAL_PROPERTIES} optional properties or {@value #MAX_UNION_PARAMETERS}
 *       union-typed ones in total. Each problem names its path, e.g.
 *       {@code $.properties.meta.additionalProperties}.</li>
 * </ul>
 */
final class AnthropicSchemas {

    /** Anthropic's limit on properties not listed in {@code required}, across the whole schema. */
    static final int MAX_OPTIONAL_PROPERTIES = 24;

    /** Anthropic's limit on properties using {@code anyOf} or a type list, across the whole schema. */
    static final int MAX_UNION_PARAMETERS = 16;

    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid");

    /** Constraints removed from the sent schema and described instead. */
    private static final List<String> RELAXED = List.of(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "maxItems", "uniqueItems", "minProperties", "maxProperties");

    private static final List<String> UNSUPPORTED = List.of(
            "oneOf", "not", "if", "then", "else", "patternProperties", "propertyNames", "dependentSchemas",
            "dependencies", "dependentRequired", "contains", "minContains", "maxContains",
            "unevaluatedProperties", "unevaluatedItems", "prefixItems");

    private AnthropicSchemas() {
    }

    /** The converted schema, or why it can't be sent ({@code schema} is then {@code null}). */
    record Conversion(Map<String, Object> schema, String problem) {
    }

    /** {@code schema} as Anthropic will accept it — a deep copy, never the caller's map. */
    static Conversion convert(Map<String, Object> schema) {
        Walk walk = new Walk(schema);
        Map<String, Object> converted = walk.schema(schema, "$");
        walk.checkRefs();
        if (walk.optionalProperties > MAX_OPTIONAL_PROPERTIES) {
            walk.problems.add("$: " + walk.optionalProperties + " optional properties (not in \"required\"); "
                    + "Anthropic allows at most " + MAX_OPTIONAL_PROPERTIES);
        }
        if (walk.unionParameters > MAX_UNION_PARAMETERS) {
            walk.problems.add("$: " + walk.unionParameters + " union-typed properties (anyOf or a type list); "
                    + "Anthropic allows at most " + MAX_UNION_PARAMETERS);
        }
        return walk.problems.isEmpty()
                ? new Conversion(converted, null)
                : new Conversion(null, String.join("; ", walk.problems));
    }

    private static final class Walk {
        final Map<String, Object> root;
        final List<String> problems = new ArrayList<>();
        /** Local {@code $ref}s seen, with where: checked for cycles once the walk is done. */
        final Map<String, String> refs = new LinkedHashMap<>();
        int optionalProperties;
        int unionParameters;

        Walk(Map<String, Object> root) {
            this.root = root;
        }

        Map<String, Object> schema(Map<?, ?> node, String path) {
            Map<String, Object> out = new LinkedHashMap<>();
            StringJoiner relaxed = new StringJoiner(", ", "{", "}");
            boolean anyRelaxed = false;
            for (Map.Entry<?, ?> entry : node.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                String at = path + "." + key;
                switch (key) {
                    case "properties", "$defs", "definitions" -> out.put(key, schemaMap(value, at));
                    case "items" -> {
                        if (value instanceof List<?>) {
                            problems.add(at + ": tuple-style items (a list of schemas) isn't supported");
                        } else {
                            out.put(key, subschema(value, at));
                        }
                    }
                    case "anyOf", "allOf" -> out.put(key, schemaList(value, at, key.equals("allOf")));
                    case "additionalProperties" -> {
                        if (Boolean.FALSE.equals(value)) {
                            out.put(key, false);
                        } else {
                            problems.add(at + " is " + (Boolean.TRUE.equals(value) ? "true" : "a schema")
                                    + "; Anthropic only accepts false");
                        }
                    }
                    case "$ref" -> {
                        String ref = String.valueOf(value);
                        if (!ref.startsWith("#")) {
                            problems.add(at + ": external $ref " + ref + " isn't supported");
                        } else {
                            refs.putIfAbsent(ref, at);
                        }
                        out.put(key, value);
                    }
                    case "enum" -> {
                        if (value instanceof List<?> values && values.stream().anyMatch(v -> v instanceof Map<?, ?> || v instanceof List<?>)) {
                            problems.add(at + ": enum values must be strings, numbers, booleans or null");
                        }
                        out.put(key, copy(value));
                    }
                    case "format" -> {
                        if (SUPPORTED_FORMATS.contains(String.valueOf(value))) {
                            out.put(key, value);
                        } else {
                            relaxed.add("format: " + value);
                            anyRelaxed = true;
                        }
                    }
                    case "minItems" -> {
                        if (value instanceof Number n && n.longValue() > 1) {
                            relaxed.add("minItems: " + value);
                            anyRelaxed = true;
                        } else {
                            out.put(key, value);
                        }
                    }
                    default -> {
                        if (UNSUPPORTED.contains(key)) {
                            problems.add(at + ": \"" + key + "\" isn't supported");
                        } else if (RELAXED.contains(key)) {
                            relaxed.add(key + ": " + value);
                            anyRelaxed = true;
                        } else {
                            out.put(key, copy(value));
                        }
                    }
                }
            }
            if (anyRelaxed) {
                Object description = out.get("description");
                out.put("description", description == null || String.valueOf(description).isBlank()
                        ? relaxed.toString()
                        : description + " " + relaxed);
            }
            if (isObject(node) && !node.containsKey("additionalProperties")) {
                out.put("additionalProperties", false);
            }
            countParameters(node);
            return out;
        }

        private void countParameters(Map<?, ?> node) {
            if (!(node.get("properties") instanceof Map<?, ?> properties)) {
                return;
            }
            List<?> required = node.get("required") instanceof List<?> r ? r : List.of();
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                if (!required.contains(property.getKey())) {
                    optionalProperties++;
                }
                if (property.getValue() instanceof Map<?, ?> schema
                        && (schema.containsKey("anyOf") || schema.get("type") instanceof List<?> types && types.size() > 1)) {
                    unionParameters++;
                }
            }
        }

        private Object subschema(Object value, String path) {
            if (value instanceof Map<?, ?> map) {
                return schema(map, path);
            }
            return copy(value); // true/false schemas and anything malformed pass through for Anthropic to judge
        }

        private Map<String, Object> schemaMap(Object value, String path) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    out.put(name, subschema(entry.getValue(), path + "." + name));
                }
            }
            return out;
        }

        private List<Object> schemaList(Object value, String path, boolean allOf) {
            List<Object> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (allOf && item instanceof Map<?, ?> map && map.containsKey("$ref")) {
                        problems.add(path + "[" + i + "]: allOf with a $ref isn't supported");
                    }
                    out.add(subschema(item, path + "[" + i + "]"));
                }
            }
            return out;
        }

        /** Recursive schemas can't be compiled: report any local {@code $ref} reachable from itself. */
        void checkRefs() {
            for (Map.Entry<String, String> ref : refs.entrySet()) {
                if (reaches(ref.getKey(), ref.getKey(), new ArrayDeque<>())) {
                    problems.add(ref.getValue() + ": recursive $ref " + ref.getKey() + " isn't supported");
                }
            }
        }

        private boolean reaches(String from, String target, Deque<String> seen) {
            if (seen.contains(from)) {
                return false;
            }
            seen.push(from);
            Object resolved = resolve(from);
            List<String> next = new ArrayList<>();
            collectRefs(resolved, next);
            for (String ref : next) {
                if (ref.equals(target) || reaches(ref, target, seen)) {
                    return true;
                }
            }
            return false;
        }

        private Object resolve(String ref) {
            Object node = root;
            String pointer = ref.substring(1);
            if (pointer.isEmpty()) {
                return root;
            }
            for (String segment : pointer.substring(pointer.startsWith("/") ? 1 : 0).split("/")) {
                String key = segment.replace("~1", "/").replace("~0", "~");
                node = node instanceof Map<?, ?> map ? map.get(key) : null;
            }
            return node;
        }

        private static void collectRefs(Object node, List<String> into) {
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref && ref.startsWith("#")) {
                        into.add(ref);
                    } else {
                        collectRefs(entry.getValue(), into);
                    }
                }
            } else if (node instanceof List<?> list) {
                list.forEach(item -> collectRefs(item, into));
            }
        }

        private static boolean isObject(Map<?, ?> node) {
            Object type = node.get("type");
            return "object".equals(type)
                    || type instanceof List<?> types && types.contains("object")
                    || type == null && node.get("properties") instanceof Map<?, ?>;
        }

        /** A deep copy of a non-schema value (enum, const, default, examples...). */
        private static Object copy(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
                return out;
            }
            if (value instanceof List<?> list) {
                List<Object> out = new ArrayList<>(list.size());
                list.forEach(item -> out.add(copy(item)));
                return out;
            }
            return value;
        }
    }
}
