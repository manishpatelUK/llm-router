package com.manishpateluk.llmrouter.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks {@link ToolDefinition}s against the rules every supported provider enforces — see
 * {@code LIBRARY_SPEC.md} §3.1 — so a bad definition fails fast, before any network call,
 * instead of surfacing as a provider 400 on every candidate in turn.
 */
public final class ToolDefinitionValidator {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private ToolDefinitionValidator() {
    }

    /**
     * @return one human-readable problem per violation, in tool order; empty if {@code tools} is
     *         valid (or {@code null}/empty). Reports every problem at once rather than stopping
     *         at the first, so a caller with several bad tools can fix them in one pass.
     */
    public static List<String> validate(List<ToolDefinition> tools) {
        List<String> problems = new ArrayList<>();
        if (tools == null) {
            return problems;
        }
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            ToolDefinition tool = tools.get(i);
            if (tool == null) {
                problems.add("tools[" + i + "] is null");
                continue;
            }
            String label = "tools[" + i + "]" + (tool.getName() != null ? " '" + tool.getName() + "'" : "");

            if (tool.getName() == null || !NAME.matcher(tool.getName()).matches()) {
                problems.add(label + ": name must match " + NAME.pattern());
            } else if (!seenNames.add(tool.getName())) {
                problems.add(label + ": duplicate tool name");
            }

            Map<String, Object> parameters = tool.getParameters();
            if (parameters == null) {
                problems.add(label + ": parameters must be a JSON Schema object, e.g. {\"type\": \"object\"} for a tool with no arguments");
            } else if (!"object".equals(parameters.get("type"))) {
                problems.add(label + ": parameters must have \"type\": \"object\" (was " + parameters.get("type") + ")");
            }
        }
        return problems;
    }
}
