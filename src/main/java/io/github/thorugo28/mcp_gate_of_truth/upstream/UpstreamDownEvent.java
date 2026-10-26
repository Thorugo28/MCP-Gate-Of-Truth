package io.github.thorugo28.mcp_gate_of_truth.upstream;

/**
 * Publicado quando um upstream que estava UP cai. Quem expôs as tools dele (o {@code proxy}) escuta e as retira.
 * Chega antes de o cliente ser fechado.
 */
public record UpstreamDownEvent(String key) {
}
