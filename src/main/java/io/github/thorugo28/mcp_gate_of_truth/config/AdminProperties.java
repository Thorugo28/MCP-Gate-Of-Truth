package io.github.thorugo28.mcp_gate_of_truth.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração da API /admin. {@code corsOrigins}: origens do painel liberadas no CORS.
 */
@ConfigurationProperties("gate.admin")
public record AdminProperties(@DefaultValue("http://localhost:4200") List<String> corsOrigins) {
}
