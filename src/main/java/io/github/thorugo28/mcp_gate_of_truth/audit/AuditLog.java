package io.github.thorugo28.mcp_gate_of_truth.audit;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Uma linha de auditoria por chamada de tool, no logger {@code gate.audit}, que o {@code logback-spring.xml} manda para
 * um arquivo JSON próprio ({@code gate.audit.file}). Os campos vão como key-value do SLF4J e viram campos do JSON.
 * Os argumentos nunca entram (nem chegam aqui: o {@link ToolCalledEvent} não os tem).
 * <p>
 * Roda na thread de quem chamou a tool; a escrita no arquivo é assíncrona, e qualquer falha aqui só vira WARN.
 */
@Component
public class AuditLog {

	static final String LOGGER = "gate.audit";

	private static final Logger audit = LoggerFactory.getLogger(LOGGER);

	private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

	@EventListener
	public void onToolCalled(ToolCalledEvent event) {
		try {
			LoggingEventBuilder line = audit.atInfo()
				.addKeyValue("at", event.at().toString())
				.addKeyValue("caller", event.caller())
				.addKeyValue("via", event.via().name().toLowerCase())
				.addKeyValue("tool", event.tool())
				.addKeyValue("upstream", event.upstream())
				.addKeyValue("durationMs", event.durationMs())
				.addKeyValue("outcome", event.outcome().name());
			if (event.error() != null) {
				line = line.addKeyValue("error", event.error());
			}
			line.log("{} chamou {} via {}: {} em {}ms", event.caller(), event.tool(), event.via().name().toLowerCase(),
					event.outcome(), event.durationMs());
		}
		catch (RuntimeException ex) {
			log.warn("Falha ao auditar a chamada de '{}' por '{}'", event.tool(), event.caller(), ex);
		}
	}

}
