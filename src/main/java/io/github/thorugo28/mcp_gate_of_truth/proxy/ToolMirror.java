package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
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
 * Espelha no servidor do gateway as tools de cada upstream, com o nome prefixado pela chave do upstream
 * ({@code <chave>__<nome original>}); descrição, inputSchema e o resto ficam iguais.
 * Cada tool entra na tabela do {@link ToolRouter}, e o handler delega a chamada para ele.
 */
@Component
public class ToolMirror {

	private static final String SEPARATOR = "__";

	private static final Logger log = LoggerFactory.getLogger(ToolMirror.class);

	private final McpSyncServer server;

	private final UpstreamRegistry registry;

	private final ToolRouter router;

	public ToolMirror(McpSyncServer server, UpstreamRegistry registry, ToolRouter router) {
		this.server = server;
		this.registry = registry;
		this.router = router;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void mirrorTools() {
		for (Map.Entry<String, McpSyncClient> entry : registry.clients().entrySet()) {
			McpSyncClient client = entry.getValue();
			for (McpSchema.Tool tool : client.listTools().tools()) {
				String exposedName = entry.getKey() + SEPARATOR + tool.name();
				router.register(exposedName, client, tool.name());
				server.addTool(McpServerFeatures.SyncToolSpecification.builder()
					.tool(withName(tool, exposedName))
					.callHandler((exchange, request) -> router.call(request))
					.build());
			}
		}
		log.info("{} tool(s) espelhada(s) no /mcp", server.listTools().size());
	}

	private static McpSchema.Tool withName(McpSchema.Tool tool, String name) {
		return new McpSchema.Tool(name, tool.title(), tool.description(), tool.inputSchema(), tool.outputSchema(),
				tool.annotations(), tool.meta(), tool.icons());
	}

}
