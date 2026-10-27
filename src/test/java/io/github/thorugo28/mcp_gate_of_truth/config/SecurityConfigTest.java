package io.github.thorugo28.mcp_gate_of_truth.config;

import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controle de acesso de ponta a ponta, com o contexto inteiro e os servidores MCP por chave de verdade. O registry é
 * mock para não subir nenhum upstream: os servidores ficam sem tools, o que basta para a autenticação.
 */
@SpringBootTest(properties = { "gate.security.keys.cli.value=teste-cli", "gate.security.keys.painel.value=teste-painel",
		"gate.security.keys.fs.value=teste-fs", "gate.security.keys.fs.upstreams=filesystem" })
@AutoConfigureMockMvc
class SecurityConfigTest {

	private static final String CLI = "Bearer teste-cli";

	private static final String PAINEL = "Bearer teste-painel";

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
			"capabilities":{},"clientInfo":{"name":"teste","version":"1"}}}""";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private UpstreamRegistry registry;

	private static MockHttpServletRequestBuilder mcpPost(String body) {
		return post("/mcp").contentType(MediaType.APPLICATION_JSON)
			.header("Accept", "application/json, text/event-stream")
			.content(body);
	}

	private String initialize(String authorization) throws Exception {
		return mvc.perform(mcpPost(INITIALIZE).header("Authorization", authorization))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getHeader("Mcp-Session-Id");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "Bearer errada", "Bearer ", "teste-cli", "Basic teste-cli", "bearer teste-cli",
			"Bearer teste-cli " })
	void mcpWithoutValidKeyIsUnauthorized(String authorization) throws Exception {
		var request = mcpPost(INITIALIZE);
		if (!authorization.isEmpty()) {
			request.header("Authorization", authorization);
		}
		mvc.perform(request)
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"))
			.andExpect(content().string(""));
	}

	@Test
	void mcpWithKeyOpensSessionWithoutCookie() throws Exception {
		mvc.perform(mcpPost(INITIALIZE).header("Authorization", CLI))
			.andExpect(status().isOk())
			.andExpect(header().exists("Mcp-Session-Id"))
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	/**
	 * A resposta SSE termina num dispatch ASYNC, que passa de novo pela cadeia do Spring Security sem sessão HTTP: o
	 * filtro tem que autenticar ali também.
	 */
	@Test
	void sseResponseSurvivesAsyncDispatch() throws Exception {
		String session = initialize(CLI);
		mvc.perform(mcpPost("""
				{"jsonrpc":"2.0","method":"notifications/initialized"}""").header("Authorization", CLI)
			.header("Mcp-Session-Id", session)).andExpect(status().isAccepted());

		MvcResult started = mvc
			.perform(mcpPost("""
					{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").header("Authorization", CLI)
				.header("Mcp-Session-Id", session))
			.andExpect(request().asyncStarted())
			.andReturn();
		mvc.perform(asyncDispatch(started))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"tools\":[]")));
	}

	@Test
	void sessionOfOneKeyIsNotFoundWithAnotherAndUnauthorizedWithout() throws Exception {
		String session = initialize(CLI);
		String toolsList = """
				{"jsonrpc":"2.0","id":2,"method":"tools/list"}""";

		mvc.perform(mcpPost(toolsList).header("Authorization", "Bearer teste-fs").header("Mcp-Session-Id", session))
			.andExpect(status().isNotFound());
		mvc.perform(mcpPost(toolsList).header("Mcp-Session-Id", session)).andExpect(status().isUnauthorized());
	}

	@Test
	void adminWithoutKeyIsUnauthorized() throws Exception {
		mvc.perform(get("/admin/upstreams"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"));
		mvc.perform(get("/admin/upstreams").header("Authorization", "Bearer errada"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void adminWithNonAdminKeyIsForbidden() throws Exception {
		mvc.perform(get("/admin/upstreams").header("Authorization", CLI))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist("WWW-Authenticate"))
			.andExpect(content().string(""));
		mvc.perform(post("/admin/tools/filesystem__write_file/call").header("Authorization", "Bearer teste-fs")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isForbidden());
	}

	@Test
	void adminWithAdminKeyIsAllowed() throws Exception {
		mvc.perform(get("/admin/upstreams").header("Authorization", PAINEL)).andExpect(status().isOk());
	}

	@Test
	void adminPreflightPassesWithoutKey() throws Exception {
		mvc.perform(options("/admin/upstreams").header("Origin", "http://localhost:4200")
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
			.andExpect(header().string("Access-Control-Allow-Headers", "authorization"));
	}

	@Test
	void mcpHasNoCors() throws Exception {
		mvc.perform(options("/mcp").header("Origin", "http://localhost:4200")
			.header("Access-Control-Request-Method", "POST"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void healthIsOpenAndOtherActuatorEndpointsAreNot() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
	}

	@Test
	void metricsOnlyForAdminKey() throws Exception {
		mvc.perform(get("/actuator/metrics/gate.tool.calls")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/metrics").header("Authorization", CLI))
			.andExpect(status().isForbidden())
			.andExpect(content().string(""));
		mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer teste-fs")).andExpect(status().isForbidden());
		mvc.perform(get("/actuator/metrics").header("Authorization", PAINEL))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("jvm.memory.used")));
	}

	@Test
	void unknownPathDoesNotLeakWithoutKey() throws Exception {
		mvc.perform(get("/xpto")).andExpect(status().isUnauthorized());
		mvc.perform(get("/xpto").header("Authorization", CLI)).andExpect(status().isNotFound());
	}

}
