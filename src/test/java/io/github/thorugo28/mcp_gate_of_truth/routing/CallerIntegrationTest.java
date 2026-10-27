package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.thorugo28.mcp_gate_of_truth.proxy.KeyServers;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem chamou chega certo ao {@link ToolCalledEvent} pelos dois caminhos: pelo /mcp, o nome da chave do servidor por
 * onde a chamada passou; pelo playground, a chave do painel. E a mesma chamada chega aos três ouvintes da auditoria
 * (arquivo, {@code /admin/calls} e {@code /actuator/metrics}) sem os argumentos. Mesma configuração do
 * {@code SecurityConfigTest}, para reaproveitar o contexto (por isso as contagens são relativas).
 */
@SpringBootTest(properties = { "gate.security.keys.cli.value=teste-cli", "gate.security.keys.painel.value=teste-painel",
		"gate.security.keys.fs.value=teste-fs", "gate.security.keys.fs.upstreams=filesystem" })
@AutoConfigureMockMvc
@RecordApplicationEvents
class CallerIntegrationTest {

	private static final String TOOL = "filesystem__list_allowed_directories";

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
			"capabilities":{},"clientInfo":{"name":"teste","version":"1"}}}""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private KeyServers servers;

	@Autowired
	private ToolRouter router;

	@Autowired
	private ApplicationEvents events;

	@MockitoBean
	private UpstreamRegistry registry;

	@Value("${gate.audit.file}")
	private Path auditFile;

	private final McpSyncClient client = mock(McpSyncClient.class);

	@BeforeEach
	void exposeTool() {
		given(registry.isConnected("filesystem")).willReturn(true);
		given(client.callTool(any()))
			.willReturn(McpSchema.CallToolResult.builder().addTextContent("/sandbox").isError(false).build());
		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(TOOL)
			.inputSchema(Map.of("type", "object"))
			.build();
		router.register(TOOL, "filesystem", client, McpSchema.Tool.builder().name("list_allowed_directories").build());
		servers.addTool("filesystem", tool, (caller, request) -> router.call(caller, Via.MCP, request));
	}

	@AfterEach
	void removeTool() {
		servers.removeTool("filesystem", TOOL);
		router.remove(TOOL);
	}

	private static MockHttpServletRequestBuilder mcpPost(String key, String session, String body) {
		MockHttpServletRequestBuilder request = post("/mcp").contentType(MediaType.APPLICATION_JSON)
			.header("Accept", "application/json, text/event-stream")
			.header("Authorization", "Bearer " + key)
			.content(body);
		return session == null ? request : request.header("Mcp-Session-Id", session);
	}

	private ToolCalledEvent onlyEvent() {
		assertThat(events.stream(ToolCalledEvent.class)).hasSize(1);
		return events.stream(ToolCalledEvent.class).findFirst().orElseThrow();
	}

	@Test
	void mcpCallCarriesTheKeyOfTheServer() throws Exception {
		String session = mvc.perform(mcpPost("teste-fs", null, INITIALIZE))
			.andReturn()
			.getResponse()
			.getHeader("Mcp-Session-Id");
		mvc.perform(mcpPost("teste-fs", session, """
				{"jsonrpc":"2.0","method":"notifications/initialized"}"""));

		MvcResult started = mvc.perform(mcpPost("teste-fs", session, """
				{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}""".formatted(TOOL)))
			.andExpect(request().asyncStarted())
			.andReturn();
		mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(content().string(containsString("/sandbox")));

		ToolCalledEvent event = onlyEvent();
		assertThat(event.caller()).isEqualTo("fs");
		assertThat(event.via()).isEqualTo(Via.MCP);
		assertThat(event.tool()).isEqualTo(TOOL);
		assertThat(event.upstream()).isEqualTo("filesystem");
		assertThat(event.outcome()).isEqualTo(Outcome.OK);
	}

	@Test
	void playgroundCallCarriesThePanelKeyAndItsAuditLineHasNoArguments() throws Exception {
		String secret = "segredo-" + UUID.randomUUID();
		int before = auditLines().size();

		mvc.perform(post("/admin/tools/{name}/call", TOOL).header("Authorization", "Bearer teste-painel")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"path\": \"/%s\"}".formatted(secret))).andExpect(status().isOk());

		ToolCalledEvent event = onlyEvent();
		assertThat(event.caller()).isEqualTo("painel");
		assertThat(event.via()).isEqualTo(Via.ADMIN);
		assertThat(event.outcome()).isEqualTo(Outcome.OK);

		// a linha passa pelo encoder JSON de verdade e pelo appender assíncrono
		List<String> added = waitForAuditLines(before + 1).subList(before, before + 1);
		JsonNode line = JsonMapper.shared().readTree(added.getFirst());
		assertThat(line.get("caller").asString()).isEqualTo("painel");
		assertThat(line.get("via").asString()).isEqualTo("admin");
		assertThat(line.get("tool").asString()).isEqualTo(TOOL);
		assertThat(line.get("upstream").asString()).isEqualTo("filesystem");
		assertThat(line.get("outcome").asString()).isEqualTo("OK");
		assertThat(line.has("durationMs")).isTrue();
		assertThat(line.has("error")).isFalse();
		assertThat(line.get("log").get("logger").asString()).isEqualTo("gate.audit");
		assertThat(added.getFirst()).doesNotContain(secret);
	}

	@Test
	void callReachesRecentCallsAndMetricsWithoutArguments() throws Exception {
		String secret = "segredo-" + UUID.randomUUID();
		double before = callCount("fs");

		String session = mvc.perform(mcpPost("teste-fs", null, INITIALIZE))
			.andReturn()
			.getResponse()
			.getHeader("Mcp-Session-Id");
		mvc.perform(mcpPost("teste-fs", session, """
				{"jsonrpc":"2.0","method":"notifications/initialized"}"""));
		MvcResult started = mvc.perform(mcpPost("teste-fs", session, """
				{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{"path":"/%s"}}}"""
			.formatted(TOOL, secret))).andExpect(request().asyncStarted()).andReturn();
		mvc.perform(asyncDispatch(started)).andExpect(status().isOk());

		String calls = mvc.perform(get("/admin/calls?caller=fs&limit=1").header("Authorization", "Bearer teste-painel"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode last = JsonMapper.shared().readTree(calls).get(0);
		assertThat(last.get("caller").asString()).isEqualTo("fs");
		assertThat(last.get("via").asString()).isEqualTo("mcp");
		assertThat(last.get("tool").asString()).isEqualTo(TOOL);
		assertThat(last.get("upstream").asString()).isEqualTo("filesystem");
		assertThat(last.get("outcome").asString()).isEqualTo("OK");
		assertThat(calls).doesNotContain(secret);

		assertThat(callCount("fs")).isEqualTo(before + 1);
	}

	/**
	 * COUNT de {@code gate.tool.calls} para a chave, lido pelo /actuator/metrics (0 se ainda não houve chamada).
	 */
	private double callCount(String caller) throws Exception {
		var response = mvc
			.perform(get("/actuator/metrics/gate.tool.calls?tag=caller:{caller}&tag=tool:{tool}", caller, TOOL)
				.header("Authorization", "Bearer teste-painel"))
			.andReturn()
			.getResponse();
		if (response.getStatus() == 404) {
			return 0;
		}
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).doesNotContain("segredo-");
		for (JsonNode measurement : JsonMapper.shared().readTree(response.getContentAsString()).get("measurements")) {
			if (measurement.get("statistic").asString().equals("COUNT")) {
				return measurement.get("value").asDouble();
			}
		}
		throw new AssertionError("gate.tool.calls sem COUNT: " + response.getContentAsString());
	}

	private List<String> auditLines() throws Exception {
		return Files.exists(auditFile) ? Files.readAllLines(auditFile) : List.of();
	}

	private List<String> waitForAuditLines(int count) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		List<String> lines = auditLines();
		while (lines.size() < count && System.nanoTime() < deadline) {
			Thread.sleep(50);
			lines = auditLines();
		}
		assertThat(lines).hasSizeGreaterThanOrEqualTo(count);
		return lines;
	}

}
