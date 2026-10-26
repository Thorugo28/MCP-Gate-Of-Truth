package io.github.thorugo28.mcp_gate_of_truth.config;

import io.modelcontextprotocol.client.McpClient;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ajustes nos clientes que o Spring AI monta para cada conexão do yml, e o agendamento do health check dos upstreams.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamClientConfig {

	// O Spring AI só repassa o request-timeout; o timeout do handshake fica no padrão do SDK se ninguém mexer.
	@Bean
	McpClientCustomizer<McpClient.SyncSpec> initTimeoutCustomizer(UpstreamProperties properties) {
		return (name, spec) -> spec.initializationTimeout(properties.initTimeout());
	}

}
