package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.time.Instant;

/**
 * Publicado pelo {@link ToolRouter} a cada chamada de tool, inclusive as que falham. Os argumentos nunca entram.
 *
 * @param at quando a chamada começou
 * @param caller nome da chave de acesso que chamou (nunca o valor)
 * @param tool nome exposto
 * @param upstream chave do upstream no yml; {@code null} se a rota não existia mais
 * @param error mensagem curta do gateway em {@link Outcome#TIMEOUT}, {@link Outcome#UNAVAILABLE} e {@link Outcome#FAILURE};
 * {@code null} em {@link Outcome#OK} e {@link Outcome#TOOL_ERROR} (o conteúdo do erro da tool vem do upstream e pode
 * repetir os argumentos)
 */
public record ToolCalledEvent(Instant at, String caller, Via via, String tool, String upstream, long durationMs,
		Outcome outcome, String error) {
}
