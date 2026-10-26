package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import io.github.thorugo28.mcp_gate_of_truth.config.UpstreamProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * A cada {@code gate.upstreams.health-interval}, pinga cada upstream conectado (em paralelo, cada um com o ping-timeout).
 * Ping que falha ou estoura o prazo derruba o upstream no {@link UpstreamRegistry}.
 */
@Component
public class UpstreamHealthCheck implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(UpstreamHealthCheck.class);

	private final UpstreamRegistry registry;

	private final UpstreamExecutor executor;

	private final UpstreamProperties properties;

	public UpstreamHealthCheck(UpstreamRegistry registry, UpstreamExecutor executor, UpstreamProperties properties) {
		this.registry = registry;
		this.executor = executor;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(
				new FixedDelayTask(this::checkAll, properties.healthInterval(), properties.healthInterval()));
	}

	void checkAll() {
		// um upstream travado não atrasa o ping dos outros; o close() espera todos terminarem
		try (ExecutorService pings = Executors.newVirtualThreadPerTaskExecutor()) {
			registry.connected().forEach((key, client) -> pings.submit(() -> check(key, client)));
		}
	}

	private void check(String key, McpSyncClient client) {
		try {
			executor.call(client::ping, properties.pingTimeout());
		}
		catch (TimeoutException ex) {
			log.warn("Upstream '{}' não respondeu ao ping em {}", key, UpstreamExecutor.format(properties.pingTimeout()));
			registry.markDown(key);
		}
		catch (RuntimeException ex) {
			log.warn("Ping no upstream '{}' falhou: {}", key, ex.getMessage());
			registry.markDown(key);
		}
	}

}
