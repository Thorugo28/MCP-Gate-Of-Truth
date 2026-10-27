package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.time.Clock;
import java.time.Instant;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Tabela de roteamento: nome exposto no /mcp → (cliente do upstream, nome original da tool).
 * Repassa o callTool para o cliente certo com o nome original e devolve o resultado sem alterar.
 * Upstream que não responde em {@code gate.upstreams.call-timeout}, ou que falha, vira erro de tool, nunca exceção.
 * <p>
 * Toda chamada publica um {@link ToolCalledEvent} (quem chamou, por onde, quanto demorou e como terminou), que a
 * auditoria escuta; o router não conhece quem escuta.
 */
@Component
public class ToolRouter {

	private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

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

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public ToolRouter(UpstreamExecutor executor, UpstreamProperties properties, UpstreamRegistry registry,
			ApplicationEventPublisher events, Clock clock) {
		this.executor = executor;
		this.properties = properties;
		this.registry = registry;
		this.events = events;
		this.clock = clock;
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

	/**
	 * Chama a tool e publica o {@link ToolCalledEvent}.
	 * @param caller nome da chave de acesso que chamou
	 */
	public McpSchema.CallToolResult call(String caller, Via via, McpSchema.CallToolRequest request) {
		Instant at = clock.instant();
		long start = System.nanoTime();
		Route route = routes.get(request.name());
		Result result = call(route, request);
		long durationMs = (System.nanoTime() - start) / 1_000_000;
		publish(new ToolCalledEvent(at, caller, via, request.name(), route == null ? null : route.upstreamKey(),
				durationMs, result.outcome(), result.outcome() == Outcome.TOOL_ERROR ? null : result.error()));
		return result.value();
	}

	/**
	 * O resultado para o cliente, como ele terminou e, se falhou no gateway, a mensagem (a mesma do resultado).
	 */
	private record Result(McpSchema.CallToolResult value, Outcome outcome, String error) {

		static Result of(McpSchema.CallToolResult value) {
			return new Result(value, Boolean.TRUE.equals(value.isError()) ? Outcome.TOOL_ERROR : Outcome.OK, null);
		}

		static Result failed(Outcome outcome, String message) {
			return new Result(ToolRouter.error(message), outcome, message);
		}

	}

	private Result call(Route route, McpSchema.CallToolRequest request) {
		// rota ausente: o upstream caiu entre o cliente ver a tool e chamar (o /mcp e o /admin só chamam tools que existem)
		if (route == null) {
			return Result.failed(Outcome.UNAVAILABLE, "Tool desconhecida no gateway: " + request.name());
		}
		// a rota pode sobrar por um instante depois de o upstream cair, até o UpstreamDownEvent tirá-la
		if (!registry.isConnected(route.upstreamKey())) {
			return Result.failed(Outcome.UNAVAILABLE, "Upstream '" + route.upstreamKey() + "' indisponível");
		}
		McpSchema.CallToolRequest upstreamRequest = McpSchema.CallToolRequest.builder()
			.name(route.originalName())
			.arguments(request.arguments())
			.meta(request.meta())
			.build();
		try {
			return Result.of(executor.call(() -> route.client().callTool(upstreamRequest), properties.callTimeout()));
		}
		catch (TimeoutException ex) {
			return Result.failed(Outcome.TIMEOUT, "Upstream '" + route.upstreamKey() + "' não respondeu em "
					+ UpstreamExecutor.format(properties.callTimeout()));
		}
		catch (RuntimeException ex) {
			return Result.failed(Outcome.FAILURE,
					"Falha ao chamar o upstream '" + route.upstreamKey() + "': " + ex.getMessage());
		}
	}

	/**
	 * Quem escuta nunca pode quebrar a chamada: o resultado já está pronto e vai para o cliente de qualquer jeito.
	 */
	private void publish(ToolCalledEvent event) {
		try {
			events.publishEvent(event);
		}
		catch (RuntimeException ex) {
			log.warn("Falha ao publicar a chamada de '{}' por '{}'", event.tool(), event.caller(), ex);
		}
	}

	private static McpSchema.CallToolResult error(String message) {
		return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
	}

}
