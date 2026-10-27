package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.audit.CallLog;
import io.github.thorugo28.mcp_gate_of_truth.audit.CallRecord;
import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Últimas chamadas de tool (pelo /mcp e pelo playground), mais recentes primeiro, lidas do {@link CallLog}.
 * {@code limit} vai de 1 ao tamanho do buffer (acima disso, vale o tamanho; abaixo de 1, 400). Filtros vazios não filtram;
 * {@code outcome} inválido dá 400.
 */
@RestController
public class CallController {

	private final CallLog callLog;

	public CallController(CallLog callLog) {
		this.callLog = callLog;
	}

	@GetMapping("/admin/calls")
	public ResponseEntity<List<CallDto>> list(@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String caller, @RequestParam(required = false) String tool,
			@RequestParam(required = false) Outcome outcome) {
		if (limit < 1) {
			return ResponseEntity.badRequest().build();
		}
		return ResponseEntity.ok(callLog
			.recent(Math.min(limit, callLog.capacity()), StringUtils.hasText(caller) ? caller : null,
					StringUtils.hasText(tool) ? tool : null, outcome)
			.stream()
			.map(CallController::toDto)
			.toList());
	}

	private static CallDto toDto(CallRecord record) {
		return new CallDto(record.at(), record.caller(), record.via().name().toLowerCase(), record.tool(),
				record.upstream(), record.durationMs(), record.outcome().name(), record.error());
	}

}
