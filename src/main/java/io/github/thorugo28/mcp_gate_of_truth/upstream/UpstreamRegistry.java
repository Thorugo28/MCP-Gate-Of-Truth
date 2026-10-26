package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Upstreams do yml indexados pela chave da conexão (ex.: {@code notion}, {@code filesystem}) e o único lugar do status:
 * UP é ter um cliente em {@link #connected()}; DOWN é ter uma reconexão agendada.
 * <p>
 * Na subida, cada upstream ganha um cliente da {@link UpstreamClientFactory} e é inicializado separadamente; quem falha
 * fica DOWN e o gateway sobe com os outros. Depois, só o {@link UpstreamHealthCheck} muda o estado: {@link #markDown(String)}
 * quando o ping falha e {@link #reconnect(String)} quando chega a hora de tentar de novo. Como cada rodada do health check
 * trata cada upstream numa tarefa só e as rodadas não se sobrepõem, as transições de um mesmo upstream nunca correm juntas.
 * <p>
 * Cliente de upstream DOWN é sempre descartado: o transporte STDIO não reabre, e qualquer chamada nele tentaria
 * inicializar de novo. Reconectar é criar um cliente novo.
 */
@Component
public class UpstreamRegistry {

	private static final Logger log = LoggerFactory.getLogger(UpstreamRegistry.class);

	private record Retry(int attempts, Instant nextAt) {
	}

	private final UpstreamClientFactory factory;

	private final ApplicationEventPublisher events;

	private final UpstreamProperties properties;

	private final Clock clock;

	private final List<String> keys;

	private final Map<String, McpSyncClient> connected = new ConcurrentHashMap<>();

	private final Map<String, Retry> retries = new ConcurrentHashMap<>();

	public UpstreamRegistry(UpstreamClientFactory factory, ApplicationEventPublisher events, UpstreamProperties properties,
			Clock clock) {
		this.factory = factory;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
		this.keys = factory.keys();
		for (String key : keys) {
			McpSyncClient client = connect(key);
			if (client != null) {
				connected.put(key, client);
			}
			else {
				retries.put(key, new Retry(0, clock.instant().plus(delayAfter(0))));
			}
		}
	}

	/**
	 * Cliente novo e inicializado, ou {@code null} se o handshake falhar (aí o cliente é fechado, matando o processo).
	 */
	private McpSyncClient connect(String key) {
		McpSyncClient client = factory.create(key);
		try {
			client.initialize();
			return client;
		}
		catch (RuntimeException ex) {
			log.warn("Upstream '{}' não inicializou: {}", key, ex.getMessage());
			client.closeGracefully();
			return null;
		}
	}

	/**
	 * Espera antes da próxima tentativa, já tendo feito {@code attempts}: inicial, dobrando a cada falha, com teto.
	 */
	private Duration delayAfter(int attempts) {
		Duration delay = properties.reconnectInitialDelay().multipliedBy(1L << Math.min(attempts, 20));
		return delay.compareTo(properties.reconnectMaxDelay()) > 0 ? properties.reconnectMaxDelay() : delay;
	}

	/**
	 * Chaves de todos os upstreams do yml, UP ou DOWN.
	 */
	public List<String> keys() {
		return keys;
	}

	/**
	 * Só os upstreams UP; os únicos em que dá para chamar algo.
	 */
	public Map<String, McpSyncClient> connected() {
		return Collections.unmodifiableMap(connected);
	}

	public boolean isConnected(String key) {
		return connected.containsKey(key);
	}

	/**
	 * Status guardado, sem falar com o upstream.
	 */
	public UpstreamStatus status(String key) {
		return isConnected(key) ? UpstreamStatus.UP : UpstreamStatus.DOWN;
	}

	public List<UpstreamState> states() {
		return keys.stream().map(this::state).toList();
	}

	private UpstreamState state(String key) {
		McpSyncClient client = connected.get(key);
		if (client != null) {
			return new UpstreamState(key, UpstreamStatus.UP, client.getServerInfo(), 0, null);
		}
		Retry retry = retries.get(key);
		return new UpstreamState(key, UpstreamStatus.DOWN, null, retry != null ? retry.attempts() : 0,
				retry != null ? retry.nextAt() : null);
	}

	/**
	 * Upstreams DOWN cuja próxima tentativa de reconexão já venceu.
	 */
	public List<String> dueForReconnect() {
		Instant now = clock.instant();
		return retries.entrySet()
			.stream()
			.filter(entry -> !entry.getValue().nextAt().isAfter(now))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
	}

	/**
	 * Transição UP → DOWN: tira o upstream de {@link #connected()}, agenda a reconexão, publica {@link UpstreamDownEvent}
	 * (para as tools saírem do /mcp) e fecha o cliente, matando o processo. Só a primeira chamada para a mesma chave faz algo.
	 */
	public void markDown(String key) {
		McpSyncClient client = connected.remove(key);
		if (client == null) {
			return;
		}
		Retry retry = new Retry(0, clock.instant().plus(delayAfter(0)));
		retries.put(key, retry);
		log.warn("Upstream '{}' caiu e fica DOWN; reconexão às {}", key, retry.nextAt());
		events.publishEvent(new UpstreamDownEvent(key));
		client.closeGracefully();
	}

	/**
	 * Uma tentativa de reconexão (DOWN → UP) com um cliente novo. Deu certo: o upstream volta para {@link #connected()} e sai
	 * {@link UpstreamUpEvent} (para as tools voltarem ao /mcp). Falhou: a próxima tentativa fica para depois, com a espera
	 * dobrada. Upstream que não está DOWN é ignorado.
	 */
	public void reconnect(String key) {
		Retry retry = retries.get(key);
		if (retry == null) {
			return;
		}
		McpSyncClient client = connect(key);
		if (client == null) {
			Retry next = new Retry(retry.attempts() + 1, clock.instant().plus(delayAfter(retry.attempts() + 1)));
			retries.put(key, next);
			log.warn("Reconexão {} do upstream '{}' falhou; próxima às {}", next.attempts(), key, next.nextAt());
			return;
		}
		retries.remove(key);
		connected.put(key, client);
		log.info("Upstream '{}' reconectou depois de {} tentativa(s)", key, retry.attempts() + 1);
		try {
			events.publishEvent(new UpstreamUpEvent(key));
		}
		catch (RuntimeException ex) {
			// ex.: o tools/list falhou logo depois do handshake; derruba de novo e as tools que entraram saem
			log.warn("Upstream '{}' reconectou mas as tools não foram registradas: {}", key, ex.getMessage());
			markDown(key);
		}
	}

}
