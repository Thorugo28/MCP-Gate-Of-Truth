package io.github.thorugo28.mcp_gate_of_truth.admin;

/**
 * Resumo de um upstream para o painel. {@code status} é {@code "UP"} ou {@code "DOWN"}.
 */
public record UpstreamDto(String key, String serverName, String serverVersion, String status, int toolCount) {
}
