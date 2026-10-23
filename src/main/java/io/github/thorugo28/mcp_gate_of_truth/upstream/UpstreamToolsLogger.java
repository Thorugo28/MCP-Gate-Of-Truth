package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Quando a aplicação sobe, lista as tools de cada upstream e loga o nome de cada uma.
 */
@Component
public class UpstreamToolsLogger {

	private static final Logger log = LoggerFactory.getLogger(UpstreamToolsLogger.class);

	private final List<McpSyncClient> clients;

	public UpstreamToolsLogger(List<McpSyncClient> clients) {
		this.clients = clients;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logTools() {
		log.info("{} upstream(s) conectado(s)", clients.size());
		for (McpSyncClient client : clients) {
			String server = client.getServerInfo().name();
			List<McpSchema.Tool> tools = client.listTools().tools();
			log.info("Upstream '{}': {} tool(s)", server, tools.size());
			tools.forEach(tool -> log.info("  - {}", tool.name()));
		}
	}

}
