package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.config.AuditProperties;
import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * As últimas chamadas de tool, em memória (somem ao reiniciar; o histórico que fica é o arquivo do {@link AuditLog}).
 * Buffer circular de {@code gate.audit.recent-size} chamadas: cheio, a mais antiga sai. É o único lugar que guarda
 * chamadas; quando entrar o banco, só esta classe troca.
 * <p>
 * A ordem é a de chegada do evento, que sai quando a chamada termina: "mais recente" é a que terminou por último.
 * Igual ao {@link AuditLog}, uma falha aqui só vira WARN.
 */
@Component
public class CallLog {

	private static final Logger log = LoggerFactory.getLogger(CallLog.class);

	private final int capacity;

	// o mais recente no fim; guardado pelo próprio monitor
	private final ArrayDeque<CallRecord> records;

	public CallLog(AuditProperties properties) {
		this.capacity = properties.recentSize();
		this.records = new ArrayDeque<>(capacity);
	}

	@EventListener
	public void onToolCalled(ToolCalledEvent event) {
		try {
			CallRecord record = CallRecord.of(event);
			synchronized (records) {
				if (records.size() == capacity) {
					records.removeFirst();
				}
				records.addLast(record);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Falha ao guardar a chamada de '{}' por '{}'", event.tool(), event.caller(), ex);
		}
	}

	public int capacity() {
		return capacity;
	}

	/**
	 * Até {@code limit} chamadas, mais recentes primeiro. Filtro {@code null} não filtra.
	 */
	public List<CallRecord> recent(int limit, String caller, String tool, Outcome outcome) {
		List<CallRecord> result = new ArrayList<>(Math.min(limit, capacity));
		synchronized (records) {
			Iterator<CallRecord> newestFirst = records.descendingIterator();
			while (newestFirst.hasNext() && result.size() < limit) {
				CallRecord record = newestFirst.next();
				if ((caller == null || caller.equals(record.caller())) && (tool == null || tool.equals(record.tool()))
						&& (outcome == null || outcome == record.outcome())) {
					result.add(record);
				}
			}
		}
		return result;
	}

}
