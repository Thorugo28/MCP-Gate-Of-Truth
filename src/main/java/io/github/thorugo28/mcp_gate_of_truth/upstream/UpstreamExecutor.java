package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Roda uma chamada bloqueante a um upstream com prazo. O {@code McpSyncClient} não aceita timeout por chamada
 * (só o request-timeout do cliente inteiro), então a chamada vai para uma virtual thread e a espera tem limite.
 * Estourado o prazo, a chamada é interrompida e sai {@link TimeoutException}.
 */
@Component
public class UpstreamExecutor {

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public <T> T call(Supplier<T> action, Duration timeout) throws TimeoutException {
		Future<T> future = executor.submit(action::get);
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw ex;
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrompido esperando o upstream", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(ex.getCause());
		}
	}

	/**
	 * Duração legível para mensagens: {@code PT5S} vira {@code 5s}.
	 */
	public static String format(Duration duration) {
		return duration.toString().substring(2).toLowerCase();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
