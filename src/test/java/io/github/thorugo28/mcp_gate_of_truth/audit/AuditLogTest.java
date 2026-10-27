package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AuditLogTest {

	private static final Instant AT = Instant.parse("2026-10-26T12:00:00Z");

	private final Logger logger = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER);

	private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

	private final AuditLog auditLog = new AuditLog();

	@BeforeEach
	void attach() {
		lines.start();
		logger.addAppender(lines);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(lines);
	}

	private Map<String, Object> fields(ILoggingEvent line) {
		return line.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
	}

	@Test
	void writesOneLineWithTheCallFields() {
		auditLog.onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "filesystem__read_file", "filesystem", 12,
				Outcome.OK, null));

		assertThat(lines.list).hasSize(1);
		ILoggingEvent line = lines.list.getFirst();
		assertThat(fields(line)).containsExactlyInAnyOrderEntriesOf(Map.of("at", "2026-10-26T12:00:00Z", "caller", "cli",
				"via", "mcp", "tool", "filesystem__read_file", "upstream", "filesystem", "durationMs", 12L, "outcome",
				"OK"));
		assertThat(line.getFormattedMessage()).isEqualTo("cli chamou filesystem__read_file via mcp: OK em 12ms");
	}

	@Test
	void failureCarriesTheGatewayMessage() {
		auditLog.onToolCalled(new ToolCalledEvent(AT, "painel", Via.ADMIN, "notion__API-get-self", "notion", 60_000,
				Outcome.TIMEOUT, "Upstream 'notion' não respondeu em 60s"));

		Map<String, Object> fields = fields(lines.list.getFirst());
		assertThat(fields).containsEntry("via", "admin")
			.containsEntry("outcome", "TIMEOUT")
			.containsEntry("error", "Upstream 'notion' não respondeu em 60s");
	}

	@Test
	void toolErrorHasNoErrorField() {
		auditLog.onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "filesystem__read_file", "filesystem", 3,
				Outcome.TOOL_ERROR, null));

		assertThat(fields(lines.list.getFirst())).containsEntry("outcome", "TOOL_ERROR").doesNotContainKey("error");
	}

	@Test
	void brokenEventNeverEscapes() {
		assertThatNoException().isThrownBy(() -> auditLog
			.onToolCalled(new ToolCalledEvent(AT, "cli", null, "filesystem__read_file", null, 0, null, null)));
	}

}
