package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playground: chama uma tool pelo mesmo {@link ToolRouter} que atende o /mcp.
 * Erro da tool ({@code isError: true}) volta com 200, porque é resposta válida da tool.
 */
@RestController
public class ToolCallController {

	private final ToolRouter router;

	public ToolCallController(ToolRouter router) {
		this.router = router;
	}

	@PostMapping("/admin/tools/{name}/call")
	public ResponseEntity<McpSchema.CallToolResult> call(@PathVariable String name,
			@RequestBody Map<String, Object> arguments) {
		if (router.find(name).isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(router.call(new McpSchema.CallToolRequest(name, arguments)));
	}

}
