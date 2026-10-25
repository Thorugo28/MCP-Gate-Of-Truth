package io.github.thorugo28.mcp_gate_of_truth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS só para /admin/**, para o painel em Angular. O /mcp fica de fora.
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminCorsConfig implements WebMvcConfigurer {

	private final AdminProperties properties;

	public AdminCorsConfig(AdminProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/admin/**")
			.allowedOrigins(properties.corsOrigins().toArray(String[]::new))
			.allowedMethods("GET", "POST");
	}

}
