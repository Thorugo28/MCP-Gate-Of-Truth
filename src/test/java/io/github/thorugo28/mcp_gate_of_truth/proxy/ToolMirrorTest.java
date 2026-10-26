package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamDownEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamExecutor;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamToolsChangedEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamUpEvent;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ToolMirrorTest {

	private final McpSyncServer server = mock(McpSyncServer.class);

	private final UpstreamRegistry registry = mock(UpstreamRegistry.class);

	private final ToolRouter router = new ToolRouter(new UpstreamExecutor(), new UpstreamProperties(Duration.ofSeconds(20),
			Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofSeconds(15),
			Duration.ofMinutes(5)), registry);

	private final ToolMirror mirror = new ToolMirror(server, registry, router);

	private final McpSyncClient client = mock(McpSyncClient.class);

	private static McpSchema.Tool tool(String name, String description) {
		return McpSchema.Tool.builder().name(name).description(description).inputSchema(Map.of("type", "object")).build();
	}

	private List<String> exposedNames(String key) {
		return router.routesOf(key).stream().map(ToolRouter.Route::exposedName).toList();
	}

	@Test
	void upstreamUpRegistersItsToolsAgain() {
		given(registry.connected()).willReturn(Map.of("notion", client));
		given(client.listTools())
			.willReturn(new McpSchema.ListToolsResult(List.of(tool("API-get-self", "Eu")), null));

		mirror.addTools(new UpstreamUpEvent("notion"));

		assertThat(exposedNames("notion")).containsExactly("notion__API-get-self");
		verify(server).addTool(argThat(spec -> spec.tool().name().equals("notion__API-get-self")));
	}

	@Test
	void upstreamDownRemovesItsToolsFromServer() {
		given(registry.connected()).willReturn(Map.of("notion", client));
		mirror.syncTools(new UpstreamToolsChangedEvent("notion",
				List.of(tool("API-get-self", "Eu"), tool("API-post-page", "Cria"))));

		mirror.removeTools(new UpstreamDownEvent("notion"));

		assertThat(router.routesOf("notion")).isEmpty();
		verify(server).removeTool("notion__API-get-self");
		verify(server).removeTool("notion__API-post-page");
	}

	@Test
	void listChangedAppliesOnlyTheDifference() {
		given(registry.connected()).willReturn(Map.of("filesystem", client));
		mirror.syncTools(new UpstreamToolsChangedEvent("filesystem",
				List.of(tool("read_file", "Lê"), tool("write_file", "Escreve"), tool("move_file", "Move"))));

		mirror.syncTools(new UpstreamToolsChangedEvent("filesystem",
				List.of(tool("read_file", "Lê"), tool("write_file", "Escreve com cuidado"), tool("list_directory", "Lista"))));

		assertThat(exposedNames("filesystem")).containsExactly("filesystem__list_directory", "filesystem__read_file",
				"filesystem__write_file");
		assertThat(router.find("filesystem__write_file")).get()
			.extracting(route -> route.tool().description())
			.isEqualTo("Escreve com cuidado");
		verify(server).removeTool("filesystem__move_file");
		// read_file não mudou: só o addTool da primeira sincronização
		verify(server, times(1)).addTool(argThat(spec -> spec.tool().name().equals("filesystem__read_file")));
		// write_file mudou: addTool de novo, que substitui no servidor
		verify(server, times(2)).addTool(argThat(spec -> spec.tool().name().equals("filesystem__write_file")));
		verify(server, never()).removeTool("filesystem__write_file");
	}

	@Test
	void listChangedOfUpstreamThatIsDownIsIgnored() {
		given(registry.connected()).willReturn(Map.of());

		mirror.syncTools(new UpstreamToolsChangedEvent("notion", List.of(tool("API-get-self", "Eu"))));

		assertThat(router.routesOf("notion")).isEmpty();
		verify(server, never()).addTool(any());
	}

	@Test
	void listChangedDuringReconnectDoesNotRegisterTwice() {
		given(registry.connected()).willReturn(Map.of("notion", client));
		List<McpSchema.Tool> tools = List.of(tool("API-get-self", "Eu"));
		given(client.listTools()).willReturn(new McpSchema.ListToolsResult(tools, null));

		// o aviso chega entre o upstream voltar e o UpstreamUpEvent
		mirror.syncTools(new UpstreamToolsChangedEvent("notion", tools));
		mirror.addTools(new UpstreamUpEvent("notion"));

		assertThat(exposedNames("notion")).containsExactly("notion__API-get-self");
		verify(server, times(1)).addTool(any());
	}

}
