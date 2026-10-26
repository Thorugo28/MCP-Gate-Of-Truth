package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.time.Duration;
import java.util.Map;

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

	private final ToolRouter router = new ToolRouter(new UpstreamExecutor(),
			new UpstreamProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200),
					Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofMinutes(5)),
			registry);

	private final McpSyncClient client = mock(McpSyncClient.class);

	private final McpSchema.Tool tool = McpSchema.Tool.builder().name("read_file").build();

	ToolRouterTest() {
		given(registry.isConnected("filesystem")).willReturn(true);
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	@Test
	void unknownToolReturnsToolError() {
		McpSchema.CallToolResult result = router.call(new McpSchema.CallToolRequest("notion__nao-existe", Map.of()));

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).contains("notion__nao-existe");
	}

	@Test
	void callsUpstreamWithOriginalName() {
		McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder().addTextContent("conteúdo").build();
		given(client.callTool(argThat(req -> req.name().equals("read_file")))).willReturn(ok);
		router.register("filesystem__read_file", "filesystem", client, tool);

		assertThat(router.call(new McpSchema.CallToolRequest("filesystem__read_file", Map.of()))).isSameAs(ok);
	}

	@Test
	void slowUpstreamReturnsToolErrorAfterTimeout() {
		given(client.callTool(any())).willAnswer(invocation -> {
			Thread.sleep(5_000);
			return null;
		});
		router.register("filesystem__read_file", "filesystem", client, tool);

		long start = System.nanoTime();
		McpSchema.CallToolResult result = router.call(new McpSchema.CallToolRequest("filesystem__read_file", Map.of()));

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Upstream 'filesystem' não respondeu em 0.2s");
	}

	@Test
	void failingUpstreamReturnsToolError() {
		given(client.callTool(any())).willThrow(new RuntimeException("transporte fechado"));
		router.register("filesystem__read_file", "filesystem", client, tool);

		McpSchema.CallToolResult result = router.call(new McpSchema.CallToolRequest("filesystem__read_file", Map.of()));

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Falha ao chamar o upstream 'filesystem': transporte fechado");
	}

	@Test
	void routeOfDownUpstreamReturnsUnavailableWithoutCallingClient() {
		router.register("notion__API-get-self", "notion", client, tool);

		McpSchema.CallToolResult result = router.call(new McpSchema.CallToolRequest("notion__API-get-self", Map.of()));

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("Upstream 'notion' indisponível");
		verify(client, never()).callTool(any());
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
