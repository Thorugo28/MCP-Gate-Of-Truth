package io.github.thorugo28.mcp_gate_of_truth.upstream;

/**
 * Publicado quando um upstream DOWN reconecta. Quem expõe as tools (o {@code proxy}) escuta e as registra de novo.
 * Na subida não sai este evento: o {@code proxy} espelha os conectados no {@code ApplicationReadyEvent}.
 */
public record UpstreamUpEvent(String key) {
}
