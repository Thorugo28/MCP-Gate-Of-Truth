package io.github.thorugo28.mcp_gate_of_truth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import io.github.thorugo28.mcp_gate_of_truth.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * As chaves de acesso configuradas e a busca pelo valor apresentado na requisição. A comparação é em tempo constante:
 * os dois lados viram SHA-256 (mesmo tamanho, então o tamanho do valor não vaza) e todas as chaves são comparadas, sem
 * parar na primeira que bater.
 */
@Component
public class AccessKeys {

	private static final Logger log = LoggerFactory.getLogger(AccessKeys.class);

	private record Entry(AccessKey key, byte[] digest) {
	}

	private final List<Entry> entries;

	public AccessKeys(SecurityProperties properties) {
		this.entries = properties.keys()
			.entrySet()
			.stream()
			.map(e -> new Entry(new AccessKey(e.getKey(), e.getValue().upstreams(), e.getValue().admin()),
					digest(e.getValue().value())))
			.toList();
		log.info("{} chave(s) de acesso: {}", entries.size(), entries.stream().map(e -> e.key().name()).toList());
	}

	/**
	 * Todas as chaves configuradas, sem os valores.
	 */
	public List<AccessKey> all() {
		return entries.stream().map(Entry::key).toList();
	}

	/**
	 * A chave cujo valor é {@code presented}, se houver.
	 */
	public Optional<AccessKey> find(String presented) {
		if (presented == null) {
			return Optional.empty();
		}
		byte[] digest = digest(presented);
		AccessKey found = null;
		for (Entry entry : entries) {
			if (MessageDigest.isEqual(entry.digest(), digest)) {
				found = entry.key();
			}
		}
		return Optional.ofNullable(found);
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
