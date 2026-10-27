package io.github.thorugo28.mcp_gate_of_truth.security;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lê {@code Authorization: Bearer <valor>} e, se o valor for de uma chave configurada, autentica o pedido com ela.
 * Sem header, com outro esquema ou com valor desconhecido, o pedido segue anônimo e a regra de acesso decide (401);
 * o filtro não responde nada sozinho, para não dizer qual parte falhou.
 * <p>
 * Roda também nos dispatches ASYNC e ERROR: o stream SSE do /mcp termina num dispatch ASYNC, e sem sessão HTTP o
 * contexto não sobrevive de um dispatch para o outro.
 * <p>
 * Não é bean: um {@code Filter} bean seria registrado de novo pelo Boot na cadeia do servlet, fora do Spring Security.
 */
public class AccessKeyFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final AccessKeys accessKeys;

	public AccessKeyFilter(AccessKeys accessKeys) {
		this.accessKeys = accessKeys;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		bearer(request).flatMap(accessKeys::find).ifPresent(key -> {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(new AccessKeyAuthentication(key));
			SecurityContextHolder.setContext(context);
		});
		chain.doFilter(request, response);
	}

	private static Optional<String> bearer(HttpServletRequest request) {
		return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION))
			.filter(header -> header.startsWith(BEARER))
			.map(header -> header.substring(BEARER.length()));
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

}
