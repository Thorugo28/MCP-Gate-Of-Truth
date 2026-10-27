package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties;
import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties.Key;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class KeyServersTest {

	static AccessKeys accessKeys() {
		Map<String, Key> keys = new TreeMap<>();
		keys.put("cli", new Key("segredo-cli", List.of("*"), false));
		keys.put("fs", new Key("segredo-fs", List.of("filesystem"), false));
		return new AccessKeys(new SecurityProperties(keys));
	}

	static KeyServers keyServers(AccessKeys accessKeys) {
		UpstreamRegistry registry = mock(UpstreamRegistry.class);
		given(registry.keys()).willReturn(List.of("filesystem", "notion"));
		return new KeyServers(accessKeys, registry, new McpServerProperties(), new McpServerStreamableHttpProperties());
	}

	private final KeyServers servers = keyServers(accessKeys());

	@AfterEach
	void close() {
		servers.close();
	}

	private static McpSchema.Tool tool(String name) {
		return McpSchema.Tool.builder().name(name).inputSchema(Map.of("type", "object")).build();
	}

	private void addTool(String upstreamKey, String name) {
		servers.addTool(upstreamKey, tool(name), (caller, request) -> McpSchema.CallToolResult.builder().build());
	}

	@Test
	void toolGoesOnlyToServersOfKeysThatSeeTheUpstream() {
		addTool("filesystem", "filesystem__read_file");
		addTool("notion", "notion__API-get-self");

		assertThat(servers.toolNames("cli")).containsExactly("filesystem__read_file", "notion__API-get-self");
		assertThat(servers.toolNames("fs")).containsExactly("filesystem__read_file");
		assertThat(servers.toolCounts()).containsExactly(Map.entry("cli", 2), Map.entry("fs", 1));
	}

	@Test
	void removeToolTakesItOutOfEveryServerThatHadIt() {
		addTool("filesystem", "filesystem__read_file");
		addTool("notion", "notion__API-get-self");

		servers.removeTool("filesystem", "filesystem__read_file");

		assertThat(servers.toolNames("cli")).containsExactly("notion__API-get-self");
		assertThat(servers.toolNames("fs")).isEmpty();
	}

}
