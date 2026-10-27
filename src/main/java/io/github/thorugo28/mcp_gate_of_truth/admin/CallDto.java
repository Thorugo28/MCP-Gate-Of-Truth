package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.time.Instant;

/**
 * Uma chamada de tool para o painel, com os mesmos campos da linha de auditoria. {@code via} é {@code "mcp"} ou
 * {@code "admin"}; {@code outcome} é {@code OK}, {@code TOOL_ERROR}, {@code TIMEOUT}, {@code UNAVAILABLE} ou
 * {@code FAILURE}; {@code upstream} vem {@code null} se a rota já tinha saído; {@code error} só nas falhas do gateway.
 */
public record CallDto(Instant at, String caller, String via, String tool, String upstream, long durationMs,
		String outcome, String error) {
}
