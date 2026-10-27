package io.github.thorugo28.mcp_gate_of_truth.config;

import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeyAuthentication;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeyFilter;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Controle de acesso por chave ({@link AccessKeyFilter}): {@code /admin/**} e o resto do {@code /actuator/**} (ex.: as
 * métricas) só para chave com {@code admin: true} (outra chave válida dá 403), o resto (o /mcp) para qualquer chave
 * válida, e {@code /actuator/health} aberto.
 * Sem chave válida, 401 com {@code WWW-Authenticate: Bearer}, sem corpo.
 * <p>
 * Sem sessão, sem CSRF (nada vem de cookie) e sem login/basic. O CORS do /admin vem do {@link AdminCorsConfig}, e o
 * preflight é respondido antes da autenticação, sem chave.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AccessKeys accessKeys) {
		return http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new AccessKeyFilter(accessKeys), AuthorizationFilter.class)
			.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**", "/error")
				.permitAll()
				.requestMatchers("/admin/**", "/actuator/**")
				.hasRole(AccessKeyAuthentication.ADMIN_ROLE)
				.anyRequest()
				.authenticated())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, ex) -> {
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			}).accessDeniedHandler((request, response, ex) -> response.setStatus(HttpStatus.FORBIDDEN.value())))
			.build();
	}

}
