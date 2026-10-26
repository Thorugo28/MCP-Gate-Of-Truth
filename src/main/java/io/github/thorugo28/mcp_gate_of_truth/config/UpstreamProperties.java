package io.github.thorugo28.mcp_gate_of_truth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timeouts e ritmo do gateway com os upstreams. Handshake (20s, o padrão do SDK) e chamada (60s, o {@code request-timeout} do yml)
 * repetem o comportamento de antes. O ping é curto porque um upstream saudável responde em milissegundos e o SDK não percebe
 * processo morto: o ping só falha quando estoura o prazo. Detecção de queda = até {@code healthInterval} + {@code pingTimeout}.
 * {@code healthInterval}: intervalo entre o fim de um health check e o começo do próximo.
 * Reconexão: a primeira tentativa sai {@code reconnectInitialDelay} depois da queda, e cada falha dobra a espera até
 * {@code reconnectMaxDelay}. As tentativas rodam dentro do health check, então a espera real arredonda para cima no intervalo.
 */
@ConfigurationProperties("gate.upstreams")
public record UpstreamProperties(@DefaultValue("20s") Duration initTimeout, @DefaultValue("3s") Duration pingTimeout,
		@DefaultValue("60s") Duration callTimeout, @DefaultValue("10s") Duration healthInterval,
		@DefaultValue("15s") Duration reconnectInitialDelay, @DefaultValue("5m") Duration reconnectMaxDelay) {
}
