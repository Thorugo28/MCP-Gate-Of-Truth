package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKey;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Um {@link McpSyncServer} por chave de acesso, cada um com o seu transporte Streamable HTTP. Cada servidor só tem as tools
 * dos upstreams que a chave vê ({@link AccessKey#canSee}), então o {@code tools/list} sai filtrado pelo próprio SDK, tool
 * fora da chave dá o mesmo {@code Unknown tool} de tool inexistente, o {@code list_changed} só vai para quem vê a tool, e
 * sessão aberta com uma chave não existe para outra.
 * <p>
 * Nenhum transporte registra rota: o {@link McpEndpointConfig} é o único no /mcp e repassa cada pedido para o transporte da
 * chave. Os servidores anunciam só tools (o SDK acrescenta logging sozinho). Nome, versão, instruções, timeout e as
 * opções do /mcp vêm das mesmas propriedades {@code spring.ai.mcp.server.*} do yml.
 * <p>
 * É um {@link SmartLifecycle} na fase padrão (a mais alta) para fechar os servidores, e com eles as sessões e os streams
 * SSE abertos, antes do graceful shutdown do Tomcat; senão ele espera os 30s inteiros por esses streams.
 */
@Component
public class KeyServers implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(KeyServers.class);

	/**
	 * O servidor de uma chave; {@code key} é quem chamou, para a auditoria da fase 5.
	 */
	record KeyServer(AccessKey key, McpSyncServer server, WebMvcStreamableServerTransportProvider transport) {
	}

	private final Map<String, KeyServer> byName = new TreeMap<>();

	private volatile boolean running;

	public KeyServers(AccessKeys accessKeys, UpstreamRegistry registry, McpServerProperties serverProperties,
			McpServerStreamableHttpProperties httpProperties) {
		JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(jsonMapper());
		for (AccessKey key : accessKeys.all()) {
			key.upstreams()
				.stream()
				.filter(upstream -> !upstream.equals(AccessKey.ALL_UPSTREAMS) && !registry.keys().contains(upstream))
				.forEach(upstream -> log.warn("A chave de acesso '{}' cita o upstream '{}', que não existe no yml",
						key.name(), upstream));
			var transport = WebMvcStreamableServerTransportProvider.builder()
				.jsonMapper(jsonMapper)
				.mcpEndpoint(httpProperties.getMcpEndpoint())
				.keepAliveInterval(httpProperties.getKeepAliveInterval())
				.disallowDelete(httpProperties.isDisallowDelete())
				.build();
			var spec = McpServer.sync(transport)
				.serverInfo(serverProperties.getName(), serverProperties.getVersion())
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.requestTimeout(serverProperties.getRequestTimeout())
				// o handler roda na thread da requisição, como no servidor do Spring AI em servlet
				.immediateExecution(true);
			if (serverProperties.getInstructions() != null) {
				spec.instructions(serverProperties.getInstructions());
			}
			byName.put(key.name(), new KeyServer(key, spec.build(), transport));
		}
	}

	/**
	 * O mesmo {@code mcpServerJsonMapper} que o Spring AI monta (o autoconfig do servidor está desligado).
	 */
	private static JsonMapper jsonMapper() {
		return JsonMapper.builder()
			.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
			.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
			.addModules(JacksonUtils.instantiateAvailableModules())
			.changeDefaultPropertyInclusion(
					incl -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
			.build();
	}

	/**
	 * Põe a tool em todos os servidores das chaves que veem o upstream; num servidor que já a tem, substitui.
	 * Cada servidor recebe o seu handler, que chama {@code handler} com o nome da sua chave: é assim que a chamada sabe
	 * quem chamou, sem depender da thread.
	 */
	public void addTool(String upstreamKey, McpSchema.Tool tool,
			BiFunction<String, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
		serversSeeing(upstreamKey).forEach(ks -> ks.server()
			.addTool(new McpServerFeatures.SyncToolSpecification(tool,
					(exchange, request) -> handler.apply(ks.key().name(), request))));
	}

	public void removeTool(String upstreamKey, String exposedName) {
		serversSeeing(upstreamKey).forEach(ks -> ks.server().removeTool(exposedName));
	}

	private List<KeyServer> serversSeeing(String upstreamKey) {
		return byName.values().stream().filter(ks -> ks.key().canSee(upstreamKey)).toList();
	}

	/**
	 * Quantas tools cada chave vê, pelo nome da chave.
	 */
	public Map<String, Integer> toolCounts() {
		Map<String, Integer> counts = new TreeMap<>();
		byName.forEach((name, ks) -> counts.put(name, ks.server().listTools().size()));
		return counts;
	}

	/**
	 * Nomes das tools que uma chave vê.
	 */
	public List<String> toolNames(String keyName) {
		return byName.get(keyName).server().listTools().stream().map(McpSchema.Tool::name).sorted().toList();
	}

	Optional<KeyServer> byName(String keyName) {
		return Optional.ofNullable(byName.get(keyName));
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		close();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/**
	 * Fechar um servidor MCP não tem volta, então o {@code stop()} só pode vir no fim do contexto, nunca numa pausa (o
	 * cache de contextos dos testes pausa o contexto entre classes e depois o retoma).
	 */
	@Override
	public boolean isPauseable() {
		return false;
	}

	public void close() {
		byName.values().forEach(ks -> ks.server().closeGracefully());
	}

}
