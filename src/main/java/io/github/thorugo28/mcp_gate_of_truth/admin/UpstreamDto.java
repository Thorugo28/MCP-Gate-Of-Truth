package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.time.Instant;

/**
 * Resumo de um upstream para o painel. {@code status} é {@code "UP"} ou {@code "DOWN"}.
 * Em DOWN, {@code serverName} e {@code serverVersion} vêm {@code null}, {@code attempts} conta as tentativas de reconexão
 * desde a queda e {@code nextRetryAt} (ISO-8601, UTC) diz quando sai a próxima. Em UP, {@code attempts} é 0 e
 * {@code nextRetryAt} é {@code null}.
 */
public record UpstreamDto(String key, String serverName, String serverVersion, String status, int toolCount, int attempts,
		Instant nextRetryAt) {
}
