package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.time.Duration;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Métricas do gateway no Micrometer (aparecem em {@code /actuator/metrics}):
 * <ul>
 * <li>{@code gate.tool.calls}: um {@link Timer} por chamada de tool (contagem e tempo de uma vez), com as tags
 * {@code caller}, {@code via}, {@code upstream}, {@code tool} e {@code outcome};</li>
 * <li>{@code gate.upstream.up} (1 ou 0) e {@code gate.upstream.reconnect.attempts}, por upstream, lidos do
 * {@link UpstreamRegistry} na hora da leitura.</li>
 * </ul>
 * Tags só com valores de conjunto fechado (chaves de acesso, upstreams, tools e resultados): nunca argumento, sessão,
 * nome do cliente MCP ou mensagem de erro. Igual ao {@link AuditLog}, uma falha aqui só vira WARN.
 */
@Component
public class CallMetrics {

	static final String CALLS = "gate.tool.calls";

	static final String UP = "gate.upstream.up";

	static final String ATTEMPTS = "gate.upstream.reconnect.attempts";

	/** Valor da tag {@code upstream} quando a rota já tinha saído (o evento vem com {@code upstream} nulo). */
	static final String NO_UPSTREAM = "none";

	private static final Logger log = LoggerFactory.getLogger(CallMetrics.class);

	private final MeterRegistry meters;

	public CallMetrics(MeterRegistry meters, UpstreamRegistry upstreams) {
		this.meters = meters;
		for (String key : upstreams.keys()) {
			Gauge.builder(UP, upstreams, registry -> registry.isConnected(key) ? 1 : 0)
				.description("1 se o upstream está UP, 0 se está DOWN")
				.tag("upstream", key)
				.register(meters);
			Gauge.builder(ATTEMPTS, upstreams, registry -> registry.state(key).attempts())
				.description("Tentativas de reconexão desde a queda (0 em UP)")
				.tag("upstream", key)
				.register(meters);
		}
	}

	@EventListener
	public void onToolCalled(ToolCalledEvent event) {
		try {
			Timer.builder(CALLS)
				.description("Chamadas de tool pelo gateway")
				.tag("caller", event.caller())
				.tag("via", event.via().name().toLowerCase())
				.tag("upstream", event.upstream() != null ? event.upstream() : NO_UPSTREAM)
				.tag("tool", event.tool())
				.tag("outcome", event.outcome().name())
				.register(meters)
				.record(Duration.ofMillis(event.durationMs()));
		}
		catch (RuntimeException ex) {
			log.warn("Falha ao medir a chamada de '{}' por '{}'", event.tool(), event.caller(), ex);
		}
	}

}
