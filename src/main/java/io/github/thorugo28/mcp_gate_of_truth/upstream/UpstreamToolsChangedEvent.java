package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Publicado quando um upstream avisa {@code notifications/tools/list_changed}. O SDK já buscou a lista de novo:
 * {@code tools} é a lista completa e atual, com os nomes originais. Chega numa thread do cliente, não na do health check.
 */
public record UpstreamToolsChangedEvent(String key, List<McpSchema.Tool> tools) {
}
