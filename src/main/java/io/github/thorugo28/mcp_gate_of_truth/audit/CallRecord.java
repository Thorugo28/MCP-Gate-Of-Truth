package io.github.thorugo28.mcp_gate_of_truth.audit;

import java.time.Instant;

import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;

/**
 * Uma chamada de tool guardada pelo {@link CallLog}. Os mesmos campos do {@link ToolCalledEvent} (e da linha do
 * {@link AuditLog}); os argumentos nunca entram.
 */
public record CallRecord(Instant at, String caller, Via via, String tool, String upstream, long durationMs,
		Outcome outcome, String error) {

	static CallRecord of(ToolCalledEvent event) {
		return new CallRecord(event.at(), event.caller(), event.via(), event.tool(), event.upstream(),
				event.durationMs(), event.outcome(), event.error());
	}

}
