package io.github.thorugo28.mcp_gate_of_truth.config;

import java.time.Clock;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuração dos upstreams. O cliente MCP do Spring AI fica desligado ({@code spring.ai.mcp.client.enabled: false}) e o
 * gateway cria os clientes ele mesmo, na subida e na reconexão; as conexões continuam no yml, em
 * {@code spring.ai.mcp.client.stdio.connections}, lidas com as classes de propriedades do próprio Spring AI.
 * Também liga o agendamento do health check.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ UpstreamProperties.class, McpStdioClientProperties.class, McpClientCommonProperties.class })
public class UpstreamClientConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
