package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	/**
	 * Uma tool exposta: de qual upstream (chave do yml) ela vem e a {@link McpSchema.Tool} como o upstream anunciou.
	 */
	public record Route(String exposedName, String upstreamKey, McpSyncClient client, McpSchema.Tool tool) {

		public String originalName() {
			return tool.name();
		}

	}

	private final Map<String, Route> routes = new ConcurrentHashMap<>();

	public void register(String exposedName, String upstreamKey, McpSyncClient client, McpSchema.Tool tool) {
		Route previous = routes.putIfAbsent(exposedName, new Route(exposedName, upstreamKey, client, tool));
		if (previous != null) {
			throw new IllegalStateException("Tool '" + exposedName + "' já está registrada na tabela de roteamento");
		}
	}

	public Optional<Route> find(String exposedName) {
		return Optional.ofNullable(routes.get(exposedName));
	}

	/**
	 * Rotas de um upstream, ordenadas pelo nome exposto.
	 */
	public List<Route> routesOf(String upstreamKey) {
		return routes.values()
			.stream()
			.filter(route -> route.upstreamKey().equals(upstreamKey))
			.sorted(Comparator.comparing(Route::exposedName))
			.toList();
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
