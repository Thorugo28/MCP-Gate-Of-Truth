package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamDownEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamToolsChangedEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamUpEvent;
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
 * Espelha no servidor do gateway as tools de cada upstream conectado, com o nome prefixado pela chave do upstream
 * ({@code <chave>__<nome original>}); descrição, inputSchema e o resto ficam iguais.
 * Cada tool entra na tabela do {@link ToolRouter}, e o handler delega a chamada para ele.
 * Quando um upstream cai, as tools dele saem do servidor e da tabela; quando reconecta, entram de novo; quando avisa
 * que a lista mudou, só a diferença é aplicada ({@link ToolDiff}). Cada {@code addTool}/{@code removeTool} avisa os clientes
 * com {@code notifications/tools/list_changed}.
 * <p>
 * Os eventos chegam de threads diferentes (health check e threads dos clientes), então toda mudança passa por aqui com
 * {@code synchronized}: as tools de um upstream nunca são mexidas por dois eventos ao mesmo tempo.
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
	public synchronized void mirrorTools() {
		registry.connected().forEach((key, client) -> apply(key, client, client.listTools().tools()));
		log.info("{} tool(s) espelhada(s) no /mcp", server.listTools().size());
	}

	@EventListener
	public synchronized void addTools(UpstreamUpEvent event) {
		McpSyncClient client = registry.connected().get(event.key());
		if (client == null) {
			return;
		}
		apply(event.key(), client, client.listTools().tools());
		log.info("Tools do upstream '{}' de volta ao /mcp ({}); total {}", event.key(),
				router.routesOf(event.key()).size(), server.listTools().size());
	}

	@EventListener
	public synchronized void syncTools(UpstreamToolsChangedEvent event) {
		McpSyncClient client = registry.connected().get(event.key());
		// caiu antes de o aviso ser tratado: as tools já saíram e voltam inteiras na reconexão
		if (client == null) {
			return;
		}
		ToolDiff diff = apply(event.key(), client, event.tools());
		if (!diff.isEmpty()) {
			log.info("Upstream '{}' mudou a lista de tools: -{} +{} ~{}; total {}", event.key(), diff.removed().size(),
					diff.added().size(), diff.changed().size(), server.listTools().size());
		}
	}

	/**
	 * Deixa as tools expostas de um upstream iguais a {@code tools}, mexendo só na diferença. Serve para a subida, a
	 * reconexão e o list_changed, e é idempotente: um aviso que chegue no meio de uma reconexão não registra nada duas vezes.
	 */
	private ToolDiff apply(String key, McpSyncClient client, List<McpSchema.Tool> tools) {
		List<McpSchema.Tool> current = router.routesOf(key).stream().map(ToolRouter.Route::tool).toList();
		ToolDiff diff = ToolDiff.of(current, tools);
		for (String name : diff.removed()) {
			String exposedName = exposedName(key, name);
			router.remove(exposedName);
			server.removeTool(exposedName);
		}
		for (McpSchema.Tool tool : diff.changed()) {
			// a rota guarda a Tool; o addTool do servidor substitui a de mesmo nome
			router.remove(exposedName(key, tool.name()));
			expose(key, client, tool);
		}
		diff.added().forEach(tool -> expose(key, client, tool));
		return diff;
	}

	private void expose(String key, McpSyncClient client, McpSchema.Tool tool) {
		String exposedName = exposedName(key, tool.name());
		router.register(exposedName, key, client, tool);
		server.addTool(McpServerFeatures.SyncToolSpecification.builder()
			.tool(withName(tool, exposedName))
			.callHandler((exchange, request) -> router.call(request))
			.build());
	}

	private static String exposedName(String key, String originalName) {
		return key + SEPARATOR + originalName;
	}

	@EventListener
	public synchronized void removeTools(UpstreamDownEvent event) {
		List<String> names = router.unregister(event.key());
		names.forEach(server::removeTool);
		log.info("{} tool(s) do upstream '{}' retirada(s) do /mcp; sobram {}", names.size(), event.key(),
				server.listTools().size());
	}

	private static McpSchema.Tool withName(McpSchema.Tool tool, String name) {
		return new McpSchema.Tool(name, tool.title(), tool.description(), tool.inputSchema(), tool.outputSchema(),
				tool.annotations(), tool.meta(), tool.icons());
	}

}
