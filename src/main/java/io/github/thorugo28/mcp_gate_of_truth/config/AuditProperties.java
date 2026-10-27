package io.github.thorugo28.mcp_gate_of_truth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Auditoria das chamadas de tool. {@code file}: arquivo JSON do log de auditoria (lido pelo {@code logback-spring.xml};
 * está aqui só para ficar documentado num lugar). {@code recentSize}: quantas chamadas o {@code audit/CallLog} guarda em
 * memória para o {@code GET /admin/calls}.
 */
@ConfigurationProperties("gate.audit")
public record AuditProperties(@DefaultValue("logs/audit.json") String file, @DefaultValue("500") int recentSize) {

	public AuditProperties {
		if (recentSize < 1) {
			throw new IllegalArgumentException(
					"gate.audit.recent-size precisa ser pelo menos 1 (veio " + recentSize + ")");
		}
	}

}
