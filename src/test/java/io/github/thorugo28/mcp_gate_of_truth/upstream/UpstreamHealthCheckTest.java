package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.time.Duration;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UpstreamHealthCheckTest {

	private final UpstreamRegistry registry = mock(UpstreamRegistry.class);

	private final UpstreamHealthCheck healthCheck = new UpstreamHealthCheck(registry, new UpstreamExecutor(),
			new UpstreamProperties(Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(1),
					Duration.ofSeconds(15)));

	@Test
	void failingOrSlowPingMarksDownAndHealthyStaysUp() {
		McpSyncClient healthy = mock(McpSyncClient.class);
		McpSyncClient failing = mock(McpSyncClient.class);
		McpSyncClient frozen1 = mock(McpSyncClient.class);
		McpSyncClient frozen2 = mock(McpSyncClient.class);
		given(failing.ping()).willThrow(new RuntimeException("transporte fechado"));
		given(frozen1.ping()).willAnswer(invocation -> {
			Thread.sleep(5_000);
			return null;
		});
		given(frozen2.ping()).willAnswer(invocation -> {
			Thread.sleep(5_000);
			return null;
		});
		given(registry.connected())
			.willReturn(Map.of("filesystem", healthy, "notion", failing, "frozen1", frozen1, "frozen2", frozen2));

		long start = System.nanoTime();
		healthCheck.checkAll();

		// dois travados em paralelo: espera um ping-timeout, não dois
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(550));
		verify(registry).markDown("notion");
		verify(registry).markDown("frozen1");
		verify(registry).markDown("frozen2");
		verify(registry, never()).markDown("filesystem");
	}

}
