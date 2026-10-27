package io.github.thorugo28.mcp_gate_of_truth.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Chaves de acesso ao gateway, por nome (o nome aparece em log; o valor, nunca). A subida falha se não houver chave, se
 * um valor estiver vazio (variável de ambiente não definida) ou se duas chaves tiverem o mesmo valor.
 */
@ConfigurationProperties("gate.security")
public record SecurityProperties(Map<String, Key> keys) {

	/**
	 * {@code upstreams}: chaves dos upstreams que ela vê, ou {@code "*"} para todos. {@code admin}: pode usar o /admin.
	 */
	public record Key(String value, @DefaultValue List<String> upstreams, boolean admin) {

		@Override
		public String toString() {
			return "Key[value=***, upstreams=" + upstreams + ", admin=" + admin + "]";
		}

	}

	public SecurityProperties {
		if (keys == null || keys.isEmpty()) {
			throw new IllegalArgumentException("Nenhuma chave de acesso em gate.security.keys");
		}
		Map<String, String> nameByValue = new HashMap<>();
		keys.forEach((name, key) -> {
			if (key.value() == null || key.value().isBlank()) {
				throw new IllegalArgumentException("A chave de acesso '" + name + "' está sem valor (gate.security.keys."
						+ name + ".value); confira a variável de ambiente dela");
			}
			String other = nameByValue.putIfAbsent(key.value(), name);
			if (other != null) {
				throw new IllegalArgumentException(
						"As chaves de acesso '" + other + "' e '" + name + "' têm o mesmo valor");
			}
		});
	}

}
