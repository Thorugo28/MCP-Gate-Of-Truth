package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cria um {@link McpSyncClient} novo, sem inicializar, para uma conexão STDIO do yml. É o único jeito de reconectar:
 * o transporte STDIO do SDK não reabre depois que o processo morre.
 * A receita repete a do {@code McpClientAutoConfiguration} do Spring AI (clientInfo {@code "<name> - <chave>"}, title = chave,
 * request-timeout), com o handshake limitado a {@code gate.upstreams.init-timeout}. Cada cliente publica
 * {@link UpstreamToolsChangedEvent} quando o upstream avisa que a lista de tools mudou.
 */
@Component
public class UpstreamClientFactory {

	private final Map<String, ServerParameters> connections;

	private final McpClientCommonProperties common;

	private final UpstreamProperties properties;

	private final ApplicationEventPublisher events;

	public UpstreamClientFactory(McpStdioClientProperties stdio, McpClientCommonProperties common,
			UpstreamProperties properties, ApplicationEventPublisher events) {
		this.events = events;
		// ordenado pela chave: o yml vira HashMap no Spring AI
		this.connections = new TreeMap<>(stdio.toServerParameters());
		this.common = common;
		this.properties = properties;
	}

	public List<String> keys() {
		return List.copyOf(connections.keySet());
	}

	public McpSyncClient create(String key) {
		ServerParameters parameters = connections.get(key);
		if (parameters == null) {
			throw new IllegalArgumentException("Upstream desconhecido: " + key);
		}
		return McpClient.sync(new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JsonMapper.shared())))
			.clientInfo(McpSchema.Implementation.builder(common.getName() + " - " + key, common.getVersion())
				.title(key)
				.build())
			.requestTimeout(common.getRequestTimeout())
			.initializationTimeout(properties.initTimeout())
			.toolsChangeConsumer(tools -> events.publishEvent(new UpstreamToolsChangedEvent(key, tools)))
			.build();
	}

}
