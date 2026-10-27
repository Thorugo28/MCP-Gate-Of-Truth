package io.github.thorugo28.mcp_gate_of_truth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = { "gate.security.keys.cli.value=teste-cli", "gate.security.keys.painel.value=teste-painel" })
class McpGateOfTruthApplicationTests {

	@Test
	void contextLoads() {
	}

}
