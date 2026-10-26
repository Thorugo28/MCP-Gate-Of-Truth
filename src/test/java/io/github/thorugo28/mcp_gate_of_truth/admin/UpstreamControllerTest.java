package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.List;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamStatus;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UpstreamController.class)
class UpstreamControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UpstreamRegistry registry;

	@MockitoBean
	private ToolRouter router;

	private final McpSyncClient up = mock(McpSyncClient.class);

	private final McpSyncClient down = mock(McpSyncClient.class);

	private final McpSchema.Tool readFile = McpSchema.Tool.builder()
		.name("read_file")
		.description("Lê um arquivo")
		.inputSchema(Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")), "required",
				List.of("path")))
		.build();

	@Test
	void listsUpstreamsWithStatusAndToolCount() throws Exception {
		given(registry.clients()).willReturn(Map.of("filesystem", up, "notion", down));
		given(up.getServerInfo()).willReturn(new McpSchema.Implementation("secure-filesystem-server", "0.2.0"));
		given(registry.status("filesystem")).willReturn(UpstreamStatus.UP);
		given(registry.status("notion")).willReturn(UpstreamStatus.DOWN);
		given(router.routesOf("filesystem"))
			.willReturn(List.of(new ToolRouter.Route("filesystem__read_file", "filesystem", up, readFile)));
		given(router.routesOf("notion")).willReturn(List.of());

		mvc.perform(get("/admin/upstreams"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.key == 'filesystem')].serverName").value("secure-filesystem-server"))
			.andExpect(jsonPath("$[?(@.key == 'filesystem')].serverVersion").value("0.2.0"))
			.andExpect(jsonPath("$[?(@.key == 'filesystem')].status").value("UP"))
			.andExpect(jsonPath("$[?(@.key == 'filesystem')].toolCount").value(1))
			.andExpect(jsonPath("$[?(@.key == 'notion')].status").value("DOWN"))
			.andExpect(jsonPath("$[?(@.key == 'notion')].serverName").value(contains(nullValue())))
			.andExpect(jsonPath("$[?(@.key == 'notion')].toolCount").value(0));
	}

	@Test
	void listsToolsOfUpstream() throws Exception {
		given(registry.clients()).willReturn(Map.of("filesystem", up));
		given(router.routesOf("filesystem"))
			.willReturn(List.of(new ToolRouter.Route("filesystem__read_file", "filesystem", up, readFile)));

		mvc.perform(get("/admin/upstreams/filesystem/tools"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("filesystem__read_file"))
			.andExpect(jsonPath("$[0].originalName").value("read_file"))
			.andExpect(jsonPath("$[0].description").value("Lê um arquivo"))
			.andExpect(jsonPath("$[0].inputSchema.type").value("object"))
			.andExpect(jsonPath("$[0].inputSchema.properties.path.type").value("string"))
			.andExpect(jsonPath("$[0].inputSchema.required[0]").value("path"));
	}

	@Test
	void unknownUpstreamReturns404() throws Exception {
		given(registry.clients()).willReturn(Map.of("filesystem", up));

		mvc.perform(get("/admin/upstreams/xpto/tools")).andExpect(status().isNotFound());
	}

}
