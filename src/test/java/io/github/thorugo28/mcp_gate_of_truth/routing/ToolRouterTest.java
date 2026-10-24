package io.github.thorugo28.mcp_gate_of_truth.routing;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRouterTest {

	@Test
	void unknownToolReturnsToolError() {
		ToolRouter router = new ToolRouter();

		McpSchema.CallToolResult result = router.call(new McpSchema.CallToolRequest("notion__nao-existe", Map.of()));

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).singleElement()
			.isInstanceOfSatisfying(McpSchema.TextContent.class,
					text -> assertThat(text.text()).contains("notion__nao-existe"));
	}

}
