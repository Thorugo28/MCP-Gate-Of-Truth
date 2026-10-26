package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.List;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamState;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Upstreams conectados e as tools que cada um expõe no /mcp.
 */
@RestController
@RequestMapping("/admin/upstreams")
public class UpstreamController {

	private final UpstreamRegistry registry;

	private final ToolRouter router;

	public UpstreamController(UpstreamRegistry registry, ToolRouter router) {
		this.registry = registry;
		this.router = router;
	}

	@GetMapping
	public List<UpstreamDto> list() {
		return registry.states().stream().map(this::toDto).toList();
	}

	@GetMapping("/{key}/tools")
	public ResponseEntity<List<ToolDto>> tools(@PathVariable String key) {
		if (!registry.keys().contains(key)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(router.routesOf(key)
			.stream()
			.map(route -> new ToolDto(route.exposedName(), route.originalName(), route.tool().description(),
					route.tool().inputSchema()))
			.toList());
	}

	// estado guardado pelo health check; não fala com o upstream
	private UpstreamDto toDto(UpstreamState state) {
		McpSchema.Implementation serverInfo = state.serverInfo();
		return new UpstreamDto(state.key(), serverInfo != null ? serverInfo.name() : null,
				serverInfo != null ? serverInfo.version() : null, state.status().name(),
				router.routesOf(state.key()).size(), state.attempts(), state.nextRetryAt());
	}

}
