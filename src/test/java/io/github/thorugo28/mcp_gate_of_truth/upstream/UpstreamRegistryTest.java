package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UpstreamRegistryTest {

	private final McpClientCommonProperties properties = new McpClientCommonProperties();

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	UpstreamRegistryTest() {
		properties.setName("gate");
	}

	private UpstreamRegistry registry(McpSyncClient... clients) {
		return new UpstreamRegistry(List.of(clients), properties, events);
	}

	private static McpSyncClient client(String name) {
		McpSyncClient client = mock(McpSyncClient.class);
		given(client.getClientInfo()).willReturn(new McpSchema.Implementation(name, "1.0"));
		return client;
	}

	@Test
	void upstreamThatFailsToInitializeIsDownAndOthersStayUp() {
		McpSyncClient notion = client("gate - notion");
		McpSyncClient filesystem = client("gate - filesystem");
		given(notion.initialize()).willThrow(new RuntimeException("Client failed to initialize"));

		UpstreamRegistry registry = registry(notion, filesystem);

		assertThat(registry.clients()).containsOnlyKeys("notion", "filesystem");
		assertThat(registry.connected()).containsOnlyKeys("filesystem");
		assertThat(registry.status("notion")).isEqualTo(UpstreamStatus.DOWN);
		assertThat(registry.status("filesystem")).isEqualTo(UpstreamStatus.UP);
		verify(notion).closeGracefully();
		// cliente DOWN não pode receber chamada: tentaria inicializar de novo
		verify(notion, never()).ping();
		// nunca teve tools expostas, então não há o que retirar
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void markDownPublishesEventThenClosesClientOnlyOnce() {
		McpSyncClient notion = client("gate - notion");
		UpstreamRegistry registry = registry(notion);

		registry.markDown("notion");
		registry.markDown("notion");

		assertThat(registry.status("notion")).isEqualTo(UpstreamStatus.DOWN);
		assertThat(registry.connected()).isEmpty();
		var order = inOrder(events, notion);
		order.verify(events).publishEvent(new UpstreamDownEvent("notion"));
		order.verify(notion).closeGracefully();
		verify(events, times(1)).publishEvent(any(Object.class));
	}

	@Test
	void unexpectedClientNameFailsStartup() {
		McpSyncClient other = client("outro - notion");

		assertThatIllegalStateException().isThrownBy(() -> registry(other));
	}

}
