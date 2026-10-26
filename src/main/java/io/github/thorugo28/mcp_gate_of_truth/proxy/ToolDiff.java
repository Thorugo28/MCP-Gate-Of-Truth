package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Diferença entre a lista de tools que o gateway expõe de um upstream e a lista nova que ele anunciou, pelos nomes originais.
 * {@code removed}: nomes que sumiram. {@code added}: tools novas. {@code changed}: mesmo nome, definição diferente
 * (descrição, schema...). As iguais ficam de fora, para não mexer no que não mudou.
 */
record ToolDiff(List<String> removed, List<McpSchema.Tool> added, List<McpSchema.Tool> changed) {

	static ToolDiff of(List<McpSchema.Tool> current, List<McpSchema.Tool> next) {
		Map<String, McpSchema.Tool> before = byName(current);
		Map<String, McpSchema.Tool> after = byName(next);
		List<String> removed = before.keySet().stream().filter(name -> !after.containsKey(name)).toList();
		List<McpSchema.Tool> added = after.values().stream().filter(tool -> !before.containsKey(tool.name())).toList();
		List<McpSchema.Tool> changed = after.values()
			.stream()
			.filter(tool -> before.containsKey(tool.name()) && !before.get(tool.name()).equals(tool))
			.toList();
		return new ToolDiff(removed, added, changed);
	}

	private static Map<String, McpSchema.Tool> byName(List<McpSchema.Tool> tools) {
		Map<String, McpSchema.Tool> map = new LinkedHashMap<>();
		tools.forEach(tool -> map.put(tool.name(), tool));
		return map;
	}

	boolean isEmpty() {
		return removed.isEmpty() && added.isEmpty() && changed.isEmpty();
	}

}
