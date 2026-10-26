package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Quando a aplicação sobe, lista as tools de cada upstream conectado e loga o nome de cada uma.
 */
@Component
public class UpstreamToolsLogger {

	private static final Logger log = LoggerFactory.getLogger(UpstreamToolsLogger.class);

	private final UpstreamRegistry registry;

	public UpstreamToolsLogger(UpstreamRegistry registry) {
		this.registry = registry;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logTools() {
		log.info("{} de {} upstream(s) conectado(s)", registry.connected().size(), registry.clients().size());
		registry.clients()
			.keySet()
			.stream()
			.filter(key -> !registry.connected().containsKey(key))
			.forEach(key -> log.warn("Upstream '{}' DOWN, sem tools", key));
		for (Map.Entry<String, McpSyncClient> entry : registry.connected().entrySet()) {
			McpSyncClient client = entry.getValue();
			List<McpSchema.Tool> tools = client.listTools().tools();
			log.info("Upstream '{}' (clientInfo '{}', servidor '{}'): {} tool(s)", entry.getKey(),
					client.getClientInfo().name(), client.getServerInfo().name(), tools.size());
			tools.forEach(tool -> log.info("  - {}", tool.name()));
		}
	}

}
