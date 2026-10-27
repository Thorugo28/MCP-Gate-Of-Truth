package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeyAuthentication;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpEndpointConfigTest {

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
			"capabilities":{},"clientInfo":{"name":"teste","version":"1"}}}""";

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":2,"method":"tools/list"}""";

	private final AccessKeys accessKeys = KeyServersTest.accessKeys();

	private final KeyServers servers = KeyServersTest.keyServers(accessKeys);

	@AfterEach
	void close() {
		servers.close();
		SecurityContextHolder.clearContext();
	}

	/**
	 * Um POST no /mcp como se o Spring Security tivesse autenticado com o valor {@code keyValue} (ou ninguém, se null).
	 */
	private ServerResponse post(String keyValue, String sessionId, String body) throws Exception {
		SecurityContextHolder.clearContext();
		if (keyValue != null) {
			SecurityContextHolder.getContext()
				.setAuthentication(new AccessKeyAuthentication(accessKeys.find(keyValue).orElseThrow()));
		}
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setContentType("application/json");
		request.addHeader("Accept", "application/json, text/event-stream");
		if (sessionId != null) {
			request.addHeader("Mcp-Session-Id", sessionId);
		}
		request.setContent(body.getBytes());
		return McpEndpointConfig.dispatch(ServerRequest.create(request, List.of(new StringHttpMessageConverter())),
				servers);
	}

	@Test
	void withoutAuthenticatedKeyIsUnauthorized() throws Exception {
		assertThat(post(null, null, INITIALIZE).statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void sessionOfOneKeyDoesNotExistForAnother() throws Exception {
		ServerResponse initialized = post("segredo-cli", null, INITIALIZE);
		assertThat(initialized.statusCode()).isEqualTo(HttpStatus.OK);
		String sessionId = initialized.headers().getFirst("Mcp-Session-Id");
		assertThat(sessionId).isNotBlank();

		assertThat(post("segredo-fs", sessionId, TOOLS_LIST).statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
