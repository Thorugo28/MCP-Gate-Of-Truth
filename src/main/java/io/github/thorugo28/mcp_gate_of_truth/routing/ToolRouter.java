package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

/**
 * Tabela de roteamento: nome exposto no /mcp → (cliente do upstream, nome original da tool).
 * Repassa o callTool para o cliente certo com o nome original e devolve o resultado sem alterar.
 */
@Component
public class ToolRouter {

	private record Route(McpSyncClient client, String originalName) {
	}

	private final Map<String, Route> routes = new ConcurrentHashMap<>();

	public void register(String exposedName, McpSyncClient client, String originalName) {
		Route previous = routes.putIfAbsent(exposedName, new Route(client, originalName));
		if (previous != null) {
			throw new IllegalStateException("Tool '" + exposedName + "' já está registrada na tabela de roteamento");
		}
	}

	public McpSchema.CallToolResult call(McpSchema.CallToolRequest request) {
		Route route = routes.get(request.name());
		if (route == null) {
			return McpSchema.CallToolResult.builder()
				.addTextContent("Tool desconhecida no gateway: " + request.name())
				.isError(true)
				.build();
		}
		return route.client().callTool(McpSchema.CallToolRequest.builder()
			.name(route.originalName())
			.arguments(request.arguments())
			.meta(request.meta())
			.build());
	}

}
