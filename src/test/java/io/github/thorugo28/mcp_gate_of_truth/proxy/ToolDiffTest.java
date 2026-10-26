package io.github.thorugo28.mcp_gate_of_truth.proxy;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDiffTest {

	private static McpSchema.Tool tool(String name, String description) {
		return McpSchema.Tool.builder().name(name).description(description).inputSchema(Map.of("type", "object")).build();
	}

	@Test
	void separatesRemovedAddedChangedAndKeepsEqualOnesOut() {
		List<McpSchema.Tool> current = List.of(tool("read_file", "Lê"), tool("write_file", "Escreve"),
				tool("move_file", "Move"));
		List<McpSchema.Tool> next = List.of(tool("read_file", "Lê"), tool("write_file", "Escreve com cuidado"),
				tool("list_directory", "Lista"));

		ToolDiff diff = ToolDiff.of(current, next);

		assertThat(diff.removed()).containsExactly("move_file");
		assertThat(diff.added()).containsExactly(tool("list_directory", "Lista"));
		assertThat(diff.changed()).containsExactly(tool("write_file", "Escreve com cuidado"));
		assertThat(diff.isEmpty()).isFalse();
	}

	@Test
	void schemaChangeCountsAsChanged() {
		McpSchema.Tool before = tool("read_file", "Lê");
		McpSchema.Tool after = McpSchema.Tool.builder()
			.name("read_file")
			.description("Lê")
			.inputSchema(Map.of("type", "object", "required", List.of("path")))
			.build();

		assertThat(ToolDiff.of(List.of(before), List.of(after)).changed()).containsExactly(after);
	}

	@Test
	void sameListIsEmpty() {
		List<McpSchema.Tool> tools = List.of(tool("read_file", "Lê"), tool("write_file", "Escreve"));

		assertThat(ToolDiff.of(tools, List.copyOf(tools)).isEmpty()).isTrue();
	}

	@Test
	void fromNothingEverythingIsAddedAndToNothingEverythingIsRemoved() {
		List<McpSchema.Tool> tools = List.of(tool("read_file", "Lê"), tool("write_file", "Escreve"));

		assertThat(ToolDiff.of(List.of(), tools).added()).isEqualTo(tools);
		assertThat(ToolDiff.of(tools, List.of()).removed()).containsExactly("read_file", "write_file");
	}

}
