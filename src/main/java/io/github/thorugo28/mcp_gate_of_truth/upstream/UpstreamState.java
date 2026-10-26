package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.time.Instant;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Foto de um upstream no {@link UpstreamRegistry}. Em UP, {@code serverInfo} vem do handshake, {@code attempts} é 0 e
 * {@code nextRetryAt} é {@code null}. Em DOWN, {@code serverInfo} é {@code null}, {@code attempts} conta as tentativas de
 * reconexão feitas desde a queda e {@code nextRetryAt} diz quando sai a próxima.
 */
public record UpstreamState(String key, UpstreamStatus status, McpSchema.Implementation serverInfo, int attempts,
		Instant nextRetryAt) {
}
