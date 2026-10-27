package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamState;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamStatus;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallMetricsTest {

	private static final Instant AT = Instant.parse("2026-10-26T12:00:00Z");

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final UpstreamRegistry upstreams = mock(UpstreamRegistry.class);

	private CallMetrics metrics() {
		return new CallMetrics(meters, upstreams);
	}

	@Test
	void timerCountsAndTimesByClosedTags() {
		CallMetrics metrics = metrics();
		metrics.onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "filesystem__read_file", "filesystem", 12,
				Outcome.OK, null));
		metrics.onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "filesystem__read_file", "filesystem", 30,
				Outcome.OK, null));
		metrics.onToolCalled(new ToolCalledEvent(AT, "painel", Via.ADMIN, "notion__API-get-self", "notion", 5000,
				Outcome.TIMEOUT, "Upstream 'notion' não respondeu em 5s"));

		Timer cli = meters.get(CallMetrics.CALLS).tag("caller", "cli").timer();
		assertThat(cli.count()).isEqualTo(2);
		assertThat(cli.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(42);
		assertThat(cli.getId().getTags()).containsExactlyInAnyOrder(Tag.of("caller", "cli"), Tag.of("via", "mcp"),
				Tag.of("upstream", "filesystem"), Tag.of("tool", "filesystem__read_file"), Tag.of("outcome", "OK"));

		Timer timeout = meters.get(CallMetrics.CALLS).tag("outcome", "TIMEOUT").timer();
		assertThat(timeout.count()).isEqualTo(1);
		assertThat(timeout.getId().getTag("via")).isEqualTo("admin");
		// a mensagem de erro nunca vira tag
		assertThat(meters.get(CallMetrics.CALLS).timers()).allSatisfy(timer -> assertThat(timer.getId().getTags())
			.extracting(Tag::getKey)
			.containsExactlyInAnyOrder("caller", "via", "upstream", "tool", "outcome"));
	}

	@Test
	void missingUpstreamBecomesNone() {
		metrics().onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "notion__API-get-self", null, 0,
				Outcome.UNAVAILABLE, "Upstream indisponível"));

		assertThat(meters.get(CallMetrics.CALLS).tag("upstream", CallMetrics.NO_UPSTREAM).timer().count()).isEqualTo(1);
	}

	@Test
	void brokenEventDoesNotEscape() {
		CallMetrics metrics = metrics();

		assertThatNoException().isThrownBy(() -> metrics
			.onToolCalled(new ToolCalledEvent(AT, null, null, null, null, 0, null, null)));
	}

	@Test
	void gaugesReadTheRegistryOnEachRead() {
		when(upstreams.keys()).thenReturn(List.of("filesystem", "notion"));
		when(upstreams.isConnected("filesystem")).thenReturn(true);
		when(upstreams.state("filesystem"))
			.thenReturn(new UpstreamState("filesystem", UpstreamStatus.UP, null, 0, null));
		when(upstreams.state("notion")).thenReturn(new UpstreamState("notion", UpstreamStatus.DOWN, null, 3, AT));
		metrics();

		assertThat(meters.get(CallMetrics.UP).tag("upstream", "filesystem").gauge().value()).isEqualTo(1);
		assertThat(meters.get(CallMetrics.UP).tag("upstream", "notion").gauge().value()).isZero();
		assertThat(meters.get(CallMetrics.ATTEMPTS).tag("upstream", "filesystem").gauge().value()).isZero();
		assertThat(meters.get(CallMetrics.ATTEMPTS).tag("upstream", "notion").gauge().value()).isEqualTo(3);

		// notion volta: a gauge muda sem registrar nada de novo
		when(upstreams.isConnected("notion")).thenReturn(true);
		assertThat(meters.get(CallMetrics.UP).tag("upstream", "notion").gauge().value()).isEqualTo(1);
	}

}
