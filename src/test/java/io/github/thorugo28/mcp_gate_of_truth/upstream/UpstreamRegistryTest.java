package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UpstreamRegistryTest {

	/**
	 * Relógio que o teste avança na mão.
	 */
	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-26T12:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

	private final MutableClock clock = new MutableClock();

	private final Instant start = clock.instant();

	private final UpstreamClientFactory factory = mock(UpstreamClientFactory.class);

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final UpstreamProperties properties = new UpstreamProperties(Duration.ofSeconds(20), Duration.ofSeconds(3),
			Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofMinutes(5));

	private UpstreamRegistry registry(String... keys) {
		given(factory.keys()).willReturn(List.of(keys));
		return new UpstreamRegistry(factory, events, properties, clock);
	}

	private static McpSyncClient failing() {
		McpSyncClient client = mock(McpSyncClient.class);
		given(client.initialize()).willThrow(new RuntimeException("Client failed to initialize"));
		return client;
	}

	private static McpSyncClient healthy(String serverName) {
		McpSyncClient client = mock(McpSyncClient.class);
		given(client.getServerInfo()).willReturn(new McpSchema.Implementation(serverName, "1.0"));
		return client;
	}

	@Test
	void upstreamThatFailsToInitializeIsDownAndOthersStayUp() {
		McpSyncClient notion = failing();
		McpSyncClient filesystem = healthy("secure-filesystem-server");
		given(factory.create("notion")).willReturn(notion);
		given(factory.create("filesystem")).willReturn(filesystem);

		UpstreamRegistry registry = registry("filesystem", "notion");

		assertThat(registry.keys()).containsExactly("filesystem", "notion");
		assertThat(registry.connected()).containsOnlyKeys("filesystem");
		assertThat(registry.states()).containsExactly(
				new UpstreamState("filesystem", UpstreamStatus.UP,
						new McpSchema.Implementation("secure-filesystem-server", "1.0"), 0, null),
				new UpstreamState("notion", UpstreamStatus.DOWN, null, 0, start.plusSeconds(15)));
		verify(notion).closeGracefully();
		// cliente DOWN não pode receber chamada: tentaria inicializar de novo
		verify(notion, never()).ping();
		// nunca teve tools expostas, então não há o que retirar
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void markDownSchedulesReconnectPublishesEventThenClosesClientOnlyOnce() {
		McpSyncClient notion = healthy("Notion API");
		given(factory.create("notion")).willReturn(notion);
		UpstreamRegistry registry = registry("notion");

		registry.markDown("notion");
		registry.markDown("notion");

		assertThat(registry.status("notion")).isEqualTo(UpstreamStatus.DOWN);
		assertThat(registry.states()).containsExactly(
				new UpstreamState("notion", UpstreamStatus.DOWN, null, 0, start.plusSeconds(15)));
		var order = inOrder(events, notion);
		order.verify(events).publishEvent(new UpstreamDownEvent("notion"));
		order.verify(notion).closeGracefully();
		verify(events, times(1)).publishEvent(any(Object.class));
	}

	@Test
	void reconnectIsDueOnlyAfterTheDelay() {
		McpSyncClient notion = failing();
		given(factory.create("notion")).willReturn(notion);
		UpstreamRegistry registry = registry("notion");

		clock.advance(Duration.ofSeconds(14));
		assertThat(registry.dueForReconnect()).isEmpty();
		clock.advance(Duration.ofSeconds(1));
		assertThat(registry.dueForReconnect()).containsExactly("notion");
	}

	@Test
	void failedReconnectsDoubleTheDelayUpToTheCap() {
		given(factory.create("notion")).willAnswer(invocation -> failing());
		UpstreamRegistry registry = registry("notion");

		// esperas depois de cada falha de reconexão: 30s, 60s, 120s, 240s e o teto de 300s
		for (long expected : new long[] { 30, 60, 120, 240, 300, 300 }) {
			clock.advance(Duration.ofSeconds(1));
			Instant before = clock.instant();
			registry.reconnect("notion");
			UpstreamState state = registry.states().getFirst();
			assertThat(state.status()).isEqualTo(UpstreamStatus.DOWN);
			assertThat(state.nextRetryAt()).isEqualTo(before.plusSeconds(expected));
		}
		assertThat(registry.states().getFirst().attempts()).isEqualTo(6);
		// cada tentativa: cliente novo (a subida + 6), todos fechados
		verify(factory, times(7)).create("notion");
	}

	@Test
	void successfulReconnectWithNewClientGoesUpAndPublishesEvent() {
		McpSyncClient first = healthy("Notion API");
		McpSyncClient second = healthy("Notion API");
		given(factory.create("notion")).willReturn(first, second);
		UpstreamRegistry registry = registry("notion");
		registry.markDown("notion");

		registry.reconnect("notion");

		assertThat(registry.connected()).containsEntry("notion", second);
		assertThat(registry.dueForReconnect()).isEmpty();
		assertThat(registry.states().getFirst())
			.isEqualTo(new UpstreamState("notion", UpstreamStatus.UP, new McpSchema.Implementation("Notion API", "1.0"), 0,
					null));
		verify(events).publishEvent(new UpstreamUpEvent("notion"));
	}

	@Test
	void backoffResetsAfterReconnecting() {
		McpSyncClient first = failing();
		McpSyncClient second = failing();
		McpSyncClient third = healthy("Notion API");
		given(factory.create("notion")).willReturn(first, second, third);
		UpstreamRegistry registry = registry("notion");
		registry.reconnect("notion");
		registry.reconnect("notion");

		registry.markDown("notion");

		assertThat(registry.states().getFirst())
			.isEqualTo(new UpstreamState("notion", UpstreamStatus.DOWN, null, 0, clock.instant().plusSeconds(15)));
	}

	@Test
	void reconnectWhoseToolsCannotBeRegisteredGoesDownAgain() {
		McpSyncClient first = healthy("Notion API");
		McpSyncClient second = healthy("Notion API");
		given(factory.create("notion")).willReturn(first, second);
		UpstreamRegistry registry = registry("notion");
		registry.markDown("notion");
		willThrow(new RuntimeException("tools/list falhou")).given(events).publishEvent(new UpstreamUpEvent("notion"));

		registry.reconnect("notion");

		assertThat(registry.status("notion")).isEqualTo(UpstreamStatus.DOWN);
		verify(second).closeGracefully();
		verify(events, times(2)).publishEvent(new UpstreamDownEvent("notion"));
	}

	@Test
	void reconnectOfUpstreamThatIsUpDoesNothing() {
		McpSyncClient notion = healthy("Notion API");
		given(factory.create("notion")).willReturn(notion);
		UpstreamRegistry registry = registry("notion");

		registry.reconnect("notion");

		verify(factory, times(1)).create("notion");
		assertThat(registry.connected()).containsEntry("notion", notion);
	}

	@Test
	void closeClosesEveryClientAndStopsReconnecting() {
		McpSyncClient fs = healthy("fs");
		McpSyncClient notion = failing();
		given(factory.create("filesystem")).willReturn(fs);
		given(factory.create("notion")).willReturn(notion);
		UpstreamRegistry registry = registry("filesystem", "notion");

		registry.close();
		clock.advance(Duration.ofMinutes(1));

		verify(fs).closeGracefully();
		assertThat(registry.connected()).isEmpty();
		assertThat(registry.dueForReconnect()).isEmpty();
		registry.reconnect("notion");
		verify(factory, times(1)).create("notion");
	}

}
