package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamExecutor;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ToolRouterTest {

	private final UpstreamRegistry registry = mock(UpstreamRegistry.class);

	private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

	private final List<Object> events = new CopyOnWriteArrayList<>();

	private final ToolRouter router = new ToolRouter(new UpstreamExecutor(),
			new UpstreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200),
					Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5)),
			registry, events::add, Clock.fixed(NOW, ZoneOffset.UTC));

	private final McpSyncClient client = mock(McpSyncClient.class);

	private final McpSchema.Tool tool = McpSchema.Tool.builder().name("read_file").build();

	ToolRouterTest() {
		given(registry.isConnected("filesystem")).willReturn(true);
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	private McpSchema.CallToolResult call(String name) {
		return router.call("cli", Via.MCP, new McpSchema.CallToolRequest(name, Map.of("path", "/segredo")));
	}

	private ToolCalledEvent onlyEvent() {
		assertThat(events).hasSize(1).first().isInstanceOf(ToolCalledEvent.class);
		return (ToolCalledEvent) events.getFirst();
	}

	@Test
	void unknownToolReturnsToolError() {
		McpSchema.CallToolResult result = call("notion__nao-existe");

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).contains("notion__nao-existe");
		assertThat(onlyEvent()).isEqualTo(new ToolCalledEvent(NOW, "cli", Via.MCP, "notion__nao-existe", null,
				onlyEvent().durationMs(), Outcome.UNAVAILABLE, "Tool desconhecida no gateway: notion__nao-existe"));
	}

	@Test
	void callsUpstreamWithOriginalName() {
		McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder().addTextContent("conteúdo").build();
		given(client.callTool(argThat(req -> req.name().equals("read_file")))).willReturn(ok);
		router.register("filesystem__read_file", "filesystem", client, tool);

		assertThat(call("filesystem__read_file")).isSameAs(ok);
		assertThat(onlyEvent()).isEqualTo(new ToolCalledEvent(NOW, "cli", Via.MCP, "filesystem__read_file", "filesystem",
				onlyEvent().durationMs(), Outcome.OK, null));
	}

	@Test
	void toolErrorFromUpstreamIsToolErrorWithoutItsContent() {
		given(client.callTool(any())).willReturn(
				McpSchema.CallToolResult.builder().addTextContent("não achei /segredo").isError(true).build());
		router.register("filesystem__read_file", "filesystem", client, tool);

		McpSchema.CallToolResult result = router.call("painel", Via.ADMIN,
				new McpSchema.CallToolRequest("filesystem__read_file", Map.of("path", "/segredo")));

		assertThat(text(result)).isEqualTo("não achei /segredo");
		ToolCalledEvent event = onlyEvent();
		assertThat(event.caller()).isEqualTo("painel");
		assertThat(event.via()).isEqualTo(Via.ADMIN);
		assertThat(event.outcome()).isEqualTo(Outcome.TOOL_ERROR);
		assertThat(event.error()).isNull();
		// os argumentos não vão para lugar nenhum do evento
		assertThat(event.toString()).doesNotContain("segredo");
	}

	@Test
	void slowUpstreamReturnsToolErrorAfterTimeout() {
		given(client.callTool(any())).willAnswer(invocation -> {
			Thread.sleep(5_000);
			return null;
		});
		router.register("filesystem__read_file", "filesystem", client, tool);

		long start = System.nanoTime();
		McpSchema.CallToolResult result = call("filesystem__read_file");

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Upstream 'filesystem' não respondeu em 0.2s");
		ToolCalledEvent event = onlyEvent();
		assertThat(event.outcome()).isEqualTo(Outcome.TIMEOUT);
		assertThat(event.error()).isEqualTo("Upstream 'filesystem' não respondeu em 0.2s");
		assertThat(event.durationMs()).isBetween(200L, 2_000L);
	}

	@Test
	void failingUpstreamReturnsToolError() {
		given(client.callTool(any())).willThrow(new RuntimeException("transporte fechado"));
		router.register("filesystem__read_file", "filesystem", client, tool);

		McpSchema.CallToolResult result = call("filesystem__read_file");

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Falha ao chamar o upstream 'filesystem': transporte fechado");
		assertThat(onlyEvent().outcome()).isEqualTo(Outcome.FAILURE);
		assertThat(onlyEvent().error()).isEqualTo("Falha ao chamar o upstream 'filesystem': transporte fechado");
	}

	@Test
	void routeOfDownUpstreamReturnsUnavailableWithoutCallingClient() {
		router.register("notion__API-get-self", "notion", client, tool);

		McpSchema.CallToolResult result = call("notion__API-get-self");

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Upstream 'notion' indisponível");
		verify(client, never()).callTool(any());
		assertThat(onlyEvent()).isEqualTo(new ToolCalledEvent(NOW, "cli", Via.MCP, "notion__API-get-self", "notion",
				onlyEvent().durationMs(), Outcome.UNAVAILABLE, "Upstream 'notion' indisponível"));
	}

	@Test
	void failingListenerDoesNotBreakTheCall() {
		McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder().addTextContent("conteúdo").build();
		given(client.callTool(any())).willReturn(ok);
		ToolRouter failing = new ToolRouter(new UpstreamExecutor(),
				new UpstreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200),
						Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5)),
				registry, event -> {
					throw new IllegalStateException("auditoria quebrada");
				}, Clock.systemUTC());
		failing.register("filesystem__read_file", "filesystem", client, tool);

		assertThat(failing.call("cli", Via.MCP, new McpSchema.CallToolRequest("filesystem__read_file", Map.of())))
			.isSameAs(ok);
	}

	@Test
	void unregisterRemovesOnlyRoutesOfThatUpstream() {
		router.register("notion__API-get-self", "notion", client, tool);
		router.register("notion__API-post-page", "notion", client, tool);
		router.register("filesystem__read_file", "filesystem", client, tool);

		assertThat(router.unregister("notion")).containsExactly("notion__API-get-self", "notion__API-post-page");
		assertThat(router.routesOf("notion")).isEmpty();
		assertThat(router.find("filesystem__read_file")).isPresent();
		// dá para registrar de novo depois (reconexão)
		router.register("notion__API-get-self", "notion", client, tool);
	}

	@Test
	void removeTakesOutOneRoute() {
		router.register("filesystem__read_file", "filesystem", client, tool);

		assertThat(router.remove("filesystem__read_file")).isTrue();
		assertThat(router.remove("filesystem__read_file")).isFalse();
		assertThat(router.find("filesystem__read_file")).isEmpty();
	}

}
