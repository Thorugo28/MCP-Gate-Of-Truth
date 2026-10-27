package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKey;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playground: chama uma tool pelo mesmo {@link ToolRouter} que atende o /mcp.
 * Erro da tool ({@code isError: true}) volta com 200, porque é resposta válida da tool. Quem chamou é a chave do painel
 * que autenticou o pedido.
 */
@RestController
public class ToolCallController {

	private final ToolRouter router;

	public ToolCallController(ToolRouter router) {
		this.router = router;
	}

	@PostMapping("/admin/tools/{name}/call")
	public ResponseEntity<McpSchema.CallToolResult> call(@AuthenticationPrincipal AccessKey key,
			@PathVariable String name, @RequestBody Map<String, Object> arguments) {
		if (router.find(name).isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(router.call(key.name(), Via.ADMIN, new McpSchema.CallToolRequest(name, arguments)));
	}

}
