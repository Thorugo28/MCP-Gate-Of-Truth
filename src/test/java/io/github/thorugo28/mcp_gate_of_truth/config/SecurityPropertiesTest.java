package io.github.thorugo28.mcp_gate_of_truth.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties.Key;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

	@Test
	void bindsKeysWithDefaults() {
		SecurityProperties properties = bind(Map.of("gate.security.keys.cli.value", "segredo-cli",
				"gate.security.keys.painel.value", "segredo-painel", "gate.security.keys.painel.upstreams[0]", "*",
				"gate.security.keys.painel.admin", "true"));

		assertThat(properties.keys().get("cli").upstreams()).isEmpty();
		assertThat(properties.keys().get("cli").admin()).isFalse();
		assertThat(properties.keys().get("painel").upstreams()).containsExactly("*");
		assertThat(properties.keys().get("painel").admin()).isTrue();
	}

	@Test
	void failsOnEmptyValueNamingTheKey() {
		// é o que acontece com ${GATE_KEY_CLI:} sem a variável definida
		assertThatThrownBy(() -> bind(Map.of("gate.security.keys.cli.value", "", "gate.security.keys.cli.admin", "false")))
			.isInstanceOf(BindException.class)
			.rootCause()
			.hasMessageContaining("'cli'")
			.hasMessageContaining("sem valor");
	}

	@Test
	void failsOnRepeatedValue() {
		Map<String, Key> keys = new LinkedHashMap<>();
		keys.put("cli", new Key("igual", List.of("*"), false));
		keys.put("painel", new Key("igual", List.of("*"), true));

		assertThatThrownBy(() -> new SecurityProperties(keys)).hasMessageContaining("'cli' e 'painel'")
			.hasMessageNotContaining("igual");
	}

	@Test
	void failsWithoutKeys() {
		assertThatThrownBy(() -> new SecurityProperties(Map.of())).hasMessageContaining("Nenhuma chave");
	}

	@Test
	void toStringHidesTheValue() {
		SecurityProperties properties = new SecurityProperties(Map.of("cli", new Key("segredo", List.of("*"), false)));

		assertThat(properties.toString()).doesNotContain("segredo").contains("cli");
	}

	private static SecurityProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("gate.security",
				SecurityProperties.class);
	}

}
