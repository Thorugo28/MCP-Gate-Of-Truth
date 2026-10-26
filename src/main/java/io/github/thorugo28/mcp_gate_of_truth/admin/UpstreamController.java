package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.util.List;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.routing.ToolRouter;
import io.github.thorugo28.mcp_gate_of_truth.upstream.UpstreamRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
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
		return registry.clients().entrySet().stream().map(this::toDto).toList();
	}

	@GetMapping("/{key}/tools")
	public ResponseEntity<List<ToolDto>> tools(@PathVariable String key) {
		if (!registry.clients().containsKey(key)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(router.routesOf(key)
			.stream()
			.map(route -> new ToolDto(route.exposedName(), route.originalName(), route.tool().description(),
					route.tool().inputSchema()))
			.toList());
	}

	private UpstreamDto toDto(Map.Entry<String, McpSyncClient> entry) {
		McpSyncClient client = entry.getValue();
		// null se o upstream não inicializou
		McpSchema.Implementation serverInfo = client.getServerInfo();
		// status guardado pelo health check; não fala com o upstream
		return new UpstreamDto(entry.getKey(), serverInfo != null ? serverInfo.name() : null,
				serverInfo != null ? serverInfo.version() : null, registry.status(entry.getKey()).name(),
				router.routesOf(entry.getKey()).size());
	}

}
