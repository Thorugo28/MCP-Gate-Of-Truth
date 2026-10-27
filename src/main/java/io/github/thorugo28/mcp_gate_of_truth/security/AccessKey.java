package io.github.thorugo28.mcp_gate_of_truth.security;

import java.util.List;

/**
 * Quem está chamando: o nome da chave e o que ela pode usar. Não guarda o valor.
 */
public record AccessKey(String name, List<String> upstreams, boolean admin) {

	public static final String ALL_UPSTREAMS = "*";

	/**
	 * A regra de visibilidade, num lugar só: a chave vê as tools do upstream se ele está na lista dela ou se ela tem
	 * {@code "*"}.
	 */
	public boolean canSee(String upstreamKey) {
		return upstreams.contains(ALL_UPSTREAMS) || upstreams.contains(upstreamKey);
	}

}
