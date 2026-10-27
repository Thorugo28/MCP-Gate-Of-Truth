package io.github.thorugo28.mcp_gate_of_truth.security;

import java.util.List;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties;
import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties.Key;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeysTest {

	private final AccessKeys keys = new AccessKeys(new SecurityProperties(Map.of("cli",
			new Key("segredo-cli", List.of("*"), false), "fs", new Key("segredo-fs", List.of("filesystem"), false))));

	@Test
	void findsTheKeyByValue() {
		assertThat(keys.find("segredo-fs")).contains(new AccessKey("fs", List.of("filesystem"), false));
		assertThat(keys.find("segredo-cli")).map(AccessKey::name).contains("cli");
	}

	@Test
	void unknownBlankOrMissingValueFindsNothing() {
		assertThat(keys.find("segredo")).isEmpty();
		assertThat(keys.find("segredo-cli ")).isEmpty();
		assertThat(keys.find("")).isEmpty();
		assertThat(keys.find(null)).isEmpty();
	}

}
