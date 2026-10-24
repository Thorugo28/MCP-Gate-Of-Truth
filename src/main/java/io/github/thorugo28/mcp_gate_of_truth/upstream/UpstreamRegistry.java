package io.github.thorugo28.mcp_gate_of_truth.upstream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.stereotype.Component;

/**
 * Guarda os {@link McpSyncClient} dos upstreams indexados pela chave da conexão no yml (ex.: {@code notion}, {@code filesystem}).
 * O Spring AI monta o clientInfo de cada cliente como {@code "<spring.ai.mcp.client.name> - <chave>"}; a chave sai desse nome.
 */
@Component
public class UpstreamRegistry {

	private final Map<String, McpSyncClient> clients;

	public UpstreamRegistry(List<McpSyncClient> clients, McpClientCommonProperties properties) {
		String prefix = properties.getName() + " - ";
		Map<String, McpSyncClient> byKey = new LinkedHashMap<>();
		for (McpSyncClient client : clients) {
			String clientName = client.getClientInfo().name();
			if (!clientName.startsWith(prefix)) {
				throw new IllegalStateException(
						"Não deu para extrair a chave do upstream do clientInfo '" + clientName + "' (esperado '" + prefix + "<chave>')");
			}
			byKey.put(clientName.substring(prefix.length()), client);
		}
		this.clients = Collections.unmodifiableMap(byKey);
	}

	public Map<String, McpSyncClient> clients() {
		return clients;
	}

}
