package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.thorugo28.mcp_gate_of_truth.config.AuditProperties;
import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CallLogTest {

	private static final Instant AT = Instant.parse("2026-10-26T12:00:00Z");

	private static CallLog callLog(int size) {
		return new CallLog(new AuditProperties("logs/audit.json", size));
	}

	private static ToolCalledEvent call(int n, String caller, String tool, Outcome outcome) {
		return new ToolCalledEvent(AT.plusSeconds(n), caller, Via.MCP, tool, "filesystem", n, outcome, null);
	}

	private static List<Long> durations(List<CallRecord> records) {
		return records.stream().map(CallRecord::durationMs).toList();
	}

	@Test
	void newestFirstAndOldestDropsWhenFull() {
		CallLog log = callLog(3);
		for (int n = 1; n <= 5; n++) {
			log.onToolCalled(call(n, "cli", "filesystem__read_file", Outcome.OK));
		}

		assertThat(durations(log.recent(10, null, null, null))).containsExactly(5L, 4L, 3L);
		assertThat(durations(log.recent(2, null, null, null))).containsExactly(5L, 4L);
	}

	@Test
	void keepsEveryFieldOfTheEvent() {
		CallLog log = callLog(3);
		log.onToolCalled(new ToolCalledEvent(AT, "painel", Via.ADMIN, "notion__API-get-self", "notion", 5000,
				Outcome.TIMEOUT, "Upstream 'notion' não respondeu em 5s"));

		assertThat(log.recent(1, null, null, null)).containsExactly(new CallRecord(AT, "painel", Via.ADMIN,
				"notion__API-get-self", "notion", 5000, Outcome.TIMEOUT, "Upstream 'notion' não respondeu em 5s"));
	}

	@Test
	void filtersCombineAndLimitCountsOnlyMatches() {
		CallLog log = callLog(10);
		log.onToolCalled(call(1, "cli", "a", Outcome.OK));
		log.onToolCalled(call(2, "fs", "a", Outcome.OK));
		log.onToolCalled(call(3, "cli", "b", Outcome.TOOL_ERROR));
		log.onToolCalled(call(4, "cli", "a", Outcome.TIMEOUT));
		log.onToolCalled(call(5, "cli", "a", Outcome.OK));

		assertThat(durations(log.recent(10, "cli", null, null))).containsExactly(5L, 4L, 3L, 1L);
		assertThat(durations(log.recent(10, "cli", "a", null))).containsExactly(5L, 4L, 1L);
		assertThat(durations(log.recent(10, "cli", "a", Outcome.OK))).containsExactly(5L, 1L);
		assertThat(durations(log.recent(1, null, "a", Outcome.OK))).containsExactly(5L);
		assertThat(log.recent(10, "ninguem", null, null)).isEmpty();
	}

	@Test
	void parallelCallsNeitherLoseNorExceedTheLimit() throws Exception {
		CallLog log = callLog(500);
		int threads = 8;
		int perThread = 1000;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> done = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int t = 0; t < threads; t++) {
				done.add(pool.submit(() -> {
					start.await();
					for (int n = 0; n < perThread; n++) {
						log.onToolCalled(call(n, "cli", "a", Outcome.OK));
						log.recent(50, null, null, null);
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : done) {
				f.get();
			}
		}

		assertThat(log.recent(10_000, null, null, null)).hasSize(500);
	}

	@Test
	void sizeMustBePositive() {
		assertThatIllegalArgumentException().isThrownBy(() -> callLog(0)).withMessageContaining("gate.audit.recent-size");
	}

}
