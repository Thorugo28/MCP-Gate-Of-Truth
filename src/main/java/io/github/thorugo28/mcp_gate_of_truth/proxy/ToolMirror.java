package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Espelha no servidor do gateway as tools de cada upstream: mesmo nome, descrição e inputSchema.
 * O handler repassa o callTool para o upstream de origem e devolve o resultado sem alterar.
 */
@Component
public class ToolMirror {

	private static final Logger log = LoggerFactory.getLogger(ToolMirror.class);

	private final McpSyncServer server;

	private final List<McpSyncClient> clients;

	public ToolMirror(McpSyncServer server, List<McpSyncClient> clients) {
		this.server = server;
		this.clients = clients;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void mirrorTools() {
		for (McpSyncClient client : clients) {
			for (McpSchema.Tool tool : client.listTools().tools()) {
				server.addTool(McpServerFeatures.SyncToolSpecification.builder()
					.tool(tool)
					.callHandler((exchange, request) -> client.callTool(request))
					.build());
			}
		}
		log.info("{} tool(s) espelhada(s) no /mcp", server.listTools().size());
	}

}
