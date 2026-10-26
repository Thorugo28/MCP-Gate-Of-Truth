package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * PROTÓTIPO da fase 4, passo 1 (descartável): um McpSyncServer por chave atrás de um RouterFunction só em /mcp-proto.
 * Liga com {@code --gate.prototype.router.enabled=true} e {@code --gate.prototype.router.keys.<nome>=<valor>} (duas ou mais). Sem autenticação de verdade: o valor do
 * {@code Authorization: Bearer} só escolhe o servidor. {@code POST /mcp-proto/_toggle/<nome>} liga/desliga uma tool extra
 * no servidor daquela chave, para provocar o list_changed.
 */
@Configuration
@ConditionalOnProperty("gate.prototype.router.enabled")
class RouterPrototype {

	private static final String ENDPOINT = "/mcp-proto";

	private record KeyServer(String name, McpSyncServer server, WebMvcStreamableServerTransportProvider transport) {
	}

	/** valor da chave → servidor dela */
	private final Map<String, KeyServer> byValue = new TreeMap<>();

	RouterPrototype(Environment environment) {
		Map<String, String> keys = Binder.get(environment)
			.bind("gate.prototype.router.keys", Bindable.mapOf(String.class, String.class))
			.get();
		keys.forEach((name, value) -> {
			// o transporte nunca é registrado como rota: só o RouterFunction abaixo chama a rota dele
			var transport = WebMvcStreamableServerTransportProvider.builder()
				.jsonMapper(new JacksonMcpJsonMapper(JsonMapper.shared()))
				.mcpEndpoint(ENDPOINT)
				.build();
			McpSyncServer server = McpServer.sync(transport)
				.serverInfo("MCP Gate of Truth (" + name + ")", "0.0.1")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.immediateExecution(true)
				.build();
			server.addTool(tool("echo_" + name));
			byValue.put(value, new KeyServer(name, server, transport));
		});
	}

	@Bean
	RouterFunction<ServerResponse> prototypeRouter() {
		return RouterFunctions.route()
			.POST(ENDPOINT + "/_toggle/{name}", this::toggle)
			.route(request -> request.path().equals(ENDPOINT), this::dispatch)
			.build();
	}

	private ServerResponse dispatch(ServerRequest request) throws Exception {
		Optional<KeyServer> keyServer = request.headers()
			.header("Authorization")
			.stream()
			.findFirst()
			.filter(header -> header.startsWith("Bearer "))
			.map(header -> byValue.get(header.substring("Bearer ".length())));
		if (keyServer.isEmpty()) {
			return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
		}
		var handler = keyServer.get().transport().getRouterFunction().route(request);
		return handler.isPresent() ? handler.get().handle(request) : ServerResponse.notFound().build();
	}

	private ServerResponse toggle(ServerRequest request) {
		String name = request.pathVariable("name");
		KeyServer keyServer = byValue.values().stream().filter(ks -> ks.name().equals(name)).findFirst().orElse(null);
		if (keyServer == null) {
			return ServerResponse.notFound().build();
		}
		String extra = "extra_" + name;
		boolean present = keyServer.server().listTools().stream().anyMatch(tool -> tool.name().equals(extra));
		if (present) {
			keyServer.server().removeTool(extra);
		}
		else {
			keyServer.server().addTool(tool(extra));
		}
		return ServerResponse.ok().body(Map.of(extra, !present));
	}

	private static McpServerFeatures.SyncToolSpecification tool(String name) {
		return McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder()
				.name(name)
				.description("tool do protótipo")
				.inputSchema(Map.of("type", "object"))
				.build())
			.callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
				.addTextContent(name + " respondeu (sessão " + exchange.sessionId() + ")")
				.build())
			.build();
	}

	@PreDestroy
	void close() {
		byValue.values().forEach(ks -> ks.server().closeGracefully());
	}

}
