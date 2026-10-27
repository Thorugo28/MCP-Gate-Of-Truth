package io.github.thorugo28.mcp_gate_of_truth.security;

import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Pedido autenticado por uma chave de acesso. O principal é a {@link AccessKey} (sem o valor); chave com
 * {@code admin: true} ganha {@code ROLE_ADMIN}.
 */
public class AccessKeyAuthentication extends AbstractAuthenticationToken {

	public static final String ADMIN_ROLE = "ADMIN";

	private final AccessKey key;

	public AccessKeyAuthentication(AccessKey key) {
		super(key.admin() ? List.of(new SimpleGrantedAuthority("ROLE_" + ADMIN_ROLE)) : List.of());
		this.key = key;
		setAuthenticated(true);
	}

	/**
	 * A chave que autenticou o pedido da thread atual. Fora da thread da requisição (ex.: no {@code UpstreamExecutor})
	 * não há nada.
	 */
	public static Optional<AccessKey> current() {
		return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
			.filter(AccessKeyAuthentication.class::isInstance)
			.map(auth -> ((AccessKeyAuthentication) auth).key);
	}

	@Override
	public AccessKey getPrincipal() {
		return key;
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return key.name();
	}

}
