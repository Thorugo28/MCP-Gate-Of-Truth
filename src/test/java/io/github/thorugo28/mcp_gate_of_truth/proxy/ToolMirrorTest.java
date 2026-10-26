package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamDownEvent;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolMirrorTest {

	@Test
	void upstreamDownRemovesItsToolsFromServer() {
		McpSyncServer server = mock(McpSyncServer.class);
		ToolRouter router = mock(ToolRouter.class);
		given(router.unregister("notion")).willReturn(List.of("notion__API-get-self", "notion__API-post-page"));
		ToolMirror mirror = new ToolMirror(server, mock(UpstreamRegistry.class), router);

		mirror.removeTools(new UpstreamDownEvent("notion"));

		verify(server).removeTool("notion__API-get-self");
		verify(server).removeTool("notion__API-post-page");
	}

}
