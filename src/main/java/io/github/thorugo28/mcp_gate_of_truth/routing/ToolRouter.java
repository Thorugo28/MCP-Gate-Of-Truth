package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamExecutor;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

/**
 * Tabela de roteamento: nome exposto no /mcp → (cliente do upstream, nome original da tool).
 * Repassa o callTool para o cliente certo com o nome original e devolve o resultado sem alterar.
 * Upstream que não responde em {@code gate.upstreams.call-timeout}, ou que falha, vira erro de tool, nunca exceção.
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

	private final UpstreamExecutor executor;

	private final UpstreamProperties properties;

	private final UpstreamRegistry registry;

	public ToolRouter(UpstreamExecutor executor, UpstreamProperties properties, UpstreamRegistry registry) {
		this.executor = executor;
		this.properties = properties;
		this.registry = registry;
	}

	public void register(String exposedName, String upstreamKey, McpSyncClient client, McpSchema.Tool tool) {
		Route previous = routes.putIfAbsent(exposedName, new Route(exposedName, upstreamKey, client, tool));
		if (previous != null) {
			throw new IllegalStateException("Tool '" + exposedName + "' já está registrada na tabela de roteamento");
		}
	}

	/**
	 * Tira da tabela todas as rotas de um upstream e devolve os nomes expostos que saíram.
	 */
	public List<String> unregister(String upstreamKey) {
		List<String> removed = routesOf(upstreamKey).stream().map(Route::exposedName).toList();
		removed.forEach(routes::remove);
		return removed;
	}

	/**
	 * Tira uma rota da tabela; devolve se ela existia.
	 */
	public boolean remove(String exposedName) {
		return routes.remove(exposedName) != null;
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
			return error("Tool desconhecida no gateway: " + request.name());
		}
		// a rota pode sobrar por um instante depois de o upstream cair, até o UpstreamDownEvent tirá-la
		if (!registry.isConnected(route.upstreamKey())) {
			return error("Upstream '" + route.upstreamKey() + "' indisponível");
		}
		McpSchema.CallToolRequest upstreamRequest = McpSchema.CallToolRequest.builder()
			.name(route.originalName())
			.arguments(request.arguments())
			.meta(request.meta())
			.build();
		try {
			return executor.call(() -> route.client().callTool(upstreamRequest), properties.callTimeout());
		}
		catch (TimeoutException ex) {
			return error("Upstream '" + route.upstreamKey() + "' não respondeu em "
					+ UpstreamExecutor.format(properties.callTimeout()));
		}
		catch (RuntimeException ex) {
			return error("Falha ao chamar o upstream '" + route.upstreamKey() + "': " + ex.getMessage());
		}
	}

	private static McpSchema.CallToolResult error(String message) {
		return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
	}

}
