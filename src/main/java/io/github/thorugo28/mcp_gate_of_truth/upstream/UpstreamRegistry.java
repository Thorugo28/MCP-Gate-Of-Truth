package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Guarda os {@link McpSyncClient} dos upstreams indexados pela chave da conexão no yml (ex.: {@code notion}, {@code filesystem}).
 * O Spring AI monta o clientInfo de cada cliente como {@code "<spring.ai.mcp.client.name> - <chave>"}; a chave sai desse nome.
 * <p>
 * Os clientes chegam sem inicializar ({@code spring.ai.mcp.client.initialized: false}). Cada um é inicializado aqui,
 * separadamente: se falhar, o upstream fica {@link UpstreamStatus#DOWN} e o gateway sobe com os outros.
 * <p>
 * É o único lugar do status: UP é estar em {@link #connected()}. O {@link UpstreamHealthCheck} derruba quem parar de
 * responder com {@link #markDown(String)}. Cliente DOWN não pode ser usado: qualquer chamada nele tentaria inicializar de
 * novo num transporte que já fechou.
 */
@Component
public class UpstreamRegistry {

	private static final Logger log = LoggerFactory.getLogger(UpstreamRegistry.class);

	private final Map<String, McpSyncClient> clients;

	private final Map<String, McpSyncClient> connected = new ConcurrentHashMap<>();

	private final ApplicationEventPublisher events;

	public UpstreamRegistry(List<McpSyncClient> clients, McpClientCommonProperties properties,
			ApplicationEventPublisher events) {
		this.events = events;
		String prefix = properties.getName() + " - ";
		Map<String, McpSyncClient> byKey = new LinkedHashMap<>();
		for (McpSyncClient client : clients) {
			String clientName = client.getClientInfo().name();
			if (!clientName.startsWith(prefix)) {
				throw new IllegalStateException(
						"Não deu para extrair a chave do upstream do clientInfo '" + clientName + "' (esperado '" + prefix + "<chave>')");
			}
			String key = clientName.substring(prefix.length());
			byKey.put(key, client);
			if (initialize(key, client)) {
				connected.put(key, client);
			}
		}
		this.clients = Collections.unmodifiableMap(byKey);
	}

	private static boolean initialize(String key, McpSyncClient client) {
		try {
			client.initialize();
			return true;
		}
		catch (RuntimeException ex) {
			log.warn("Upstream '{}' não inicializou e fica DOWN: {}", key, ex.getMessage());
			// fecha o transporte para não deixar o processo do upstream para trás
			client.closeGracefully();
			return false;
		}
	}

	/**
	 * Todos os upstreams do yml, inclusive os DOWN.
	 */
	public Map<String, McpSyncClient> clients() {
		return clients;
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

	/**
	 * Transição UP → DOWN: tira o upstream de {@link #connected()}, publica {@link UpstreamDownEvent} (para as tools
	 * saírem do /mcp) e fecha o cliente, matando o processo. Só a primeira chamada para a mesma chave faz algo.
	 */
	public void markDown(String key) {
		McpSyncClient client = connected.remove(key);
		if (client == null) {
			return;
		}
		log.warn("Upstream '{}' caiu e fica DOWN", key);
		events.publishEvent(new UpstreamDownEvent(key));
		client.closeGracefully();
	}

}
