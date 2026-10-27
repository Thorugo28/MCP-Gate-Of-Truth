package io.github.thorugo28.mcp_gate_of_truth.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeyAuthenticationTest {

	private final AccessKey painel = new AccessKey("painel", List.of("*"), true);

	private final AccessKey cli = new AccessKey("cli", List.of("*"), false);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void onlyAdminKeyGetsAdminRole() {
		assertThat(new AccessKeyAuthentication(painel).getAuthorities()).map(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_ADMIN");
		assertThat(new AccessKeyAuthentication(cli).getAuthorities()).isEmpty();
	}

	@Test
	void exposesKeyNameButNoCredentials() {
		AccessKeyAuthentication authentication = new AccessKeyAuthentication(cli);

		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getName()).isEqualTo("cli");
		assertThat(authentication.getCredentials()).isNull();
	}

	@Test
	void currentReadsOnlyAccessKeyAuthentication() {
		assertThat(AccessKeyAuthentication.current()).isEmpty();

		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("x", "y", List.of()));
		assertThat(AccessKeyAuthentication.current()).isEmpty();

		SecurityContextHolder.getContext().setAuthentication(new AccessKeyAuthentication(cli));
		assertThat(AccessKeyAuthentication.current()).contains(cli);
	}

}
