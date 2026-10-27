package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.Map;
import java.util.Optional;

import io.github.thorugo28.mcp_gate_of_truth.config.SecurityConfig;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ToolCallController.class, properties = { "gate.security.keys.cli.value=teste-cli",
		"gate.security.keys.painel.value=teste-painel" })
@Import({ SecurityConfig.class, AccessKeys.class })
class ToolCallControllerTest {

	private static final String NAME = "filesystem__read_file";

	private static final String PAINEL = "Bearer teste-painel";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ToolRouter router;

	private void givenRoute() {
		McpSchema.Tool tool = McpSchema.Tool.builder().name("read_file").build();
		given(router.find(NAME)).willReturn(
				Optional.of(new ToolRouter.Route(NAME, "filesystem", mock(McpSyncClient.class), tool)));
	}

	@Test
	void callsToolThroughRouter() throws Exception {
		givenRoute();
		given(router.call(eq("painel"), eq(Via.ADMIN),
				argThat(req -> req.name().equals(NAME) && req.arguments().equals(Map.of("path", "/x")))))
			.willReturn(McpSchema.CallToolResult.builder().addTextContent("conteúdo").isError(false).build());

		mvc.perform(post("/admin/tools/{name}/call", NAME).header("Authorization", PAINEL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"path\": \"/x\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].type").value("text"))
			.andExpect(jsonPath("$.content[0].text").value("conteúdo"))
			.andExpect(jsonPath("$.isError").value(false));
	}

	@Test
	void toolErrorReturns200() throws Exception {
		givenRoute();
		given(router.call(any(), any(), any()))
			.willReturn(McpSchema.CallToolResult.builder().addTextContent("arquivo não existe").isError(true).build());

		mvc.perform(post("/admin/tools/{name}/call", NAME).header("Authorization", PAINEL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isError").value(true));
	}

	@Test
	void unknownToolReturns404() throws Exception {
		given(router.find("xpto")).willReturn(Optional.empty());

		mvc.perform(post("/admin/tools/xpto/call").header("Authorization", PAINEL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isNotFound());
		verify(router, never()).call(any(), any(), any());
	}

	@Test
	void corsAllowsPanelOriginWithoutKey() throws Exception {
		mvc.perform(options("/admin/tools/{name}/call", NAME).header("Origin", "http://localhost:4200")
			.header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "authorization, content-type"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
			.andExpect(header().string("Access-Control-Allow-Headers", "authorization, content-type"));
	}

	@Test
	void corsRejectsOtherOrigin() throws Exception {
		mvc.perform(options("/admin/tools/{name}/call", NAME).header("Origin", "http://evil.example")
			.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().isForbidden());
	}

}
