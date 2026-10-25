package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.Map;

/**
 * Tool exposta pelo gateway: {@code name} com prefixo, {@code originalName} como o upstream anuncia.
 * {@code inputSchema} vai como o upstream mandou (JSON Schema).
 */
public record ToolDto(String name, String originalName, String description, Map<String, Object> inputSchema) {
}
