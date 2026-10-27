package io.github.thorugo28.mcp_gate_of_truth.config;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * O autoconfig do servidor MCP do Spring AI está desligado (o gateway monta um servidor por chave em
 * {@code proxy/KeyServers}), mas as propriedades {@code spring.ai.mcp.server.*} do yml continuam valendo.
 */
@Configuration
@EnableConfigurationProperties({ McpServerProperties.class, McpServerStreamableHttpProperties.class })
public class McpServerConfig {
}
