package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.Optional;

import io.github.thorugo28.mcp_gate_of_truth.security.AccessKey;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeyAuthentication;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * A única rota do /mcp: pega a chave que o Spring Security autenticou ({@link AccessKeyAuthentication}) e repassa o
 * pedido para o transporte do servidor dela ({@link KeyServers}), que responde como responderia sozinho (JSON, SSE do
 * POST e stream do GET).
 */
@Configuration
class McpEndpointConfig {

	@Bean
	RouterFunction<ServerResponse> mcpEndpoint(KeyServers servers, McpServerStreamableHttpProperties httpProperties) {
		String endpoint = httpProperties.getMcpEndpoint();
		return RouterFunctions.route(request -> request.path().equals(endpoint), request -> dispatch(request, servers));
	}

	static ServerResponse dispatch(ServerRequest request, KeyServers servers) throws Exception {
		Optional<KeyServers.KeyServer> keyServer = AccessKeyAuthentication.current()
			.map(AccessKey::name)
			.flatMap(servers::byName);
		if (keyServer.isEmpty()) {
			// não acontece: o Spring Security barra antes o pedido sem chave válida
			return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
		}
		Optional<HandlerFunction<ServerResponse>> handler = keyServer.get().transport().getRouterFunction().route(request);
		return handler.isPresent() ? handler.get().handle(request)
				: ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).build();
	}

}
