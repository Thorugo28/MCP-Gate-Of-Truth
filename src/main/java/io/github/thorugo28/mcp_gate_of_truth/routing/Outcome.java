package io.github.thorugo28.mcp_gate_of_truth.routing;

/**
 * Como terminou uma chamada de tool.
 */
public enum Outcome {

	/** O upstream respondeu sem erro. */
	OK,
	/** O upstream respondeu com {@code isError: true}. */
	TOOL_ERROR,
	/** O upstream não respondeu dentro do {@code gate.upstreams.call-timeout}. */
	TIMEOUT,
	/** O upstream estava DOWN (ou a rota já tinha saído) e a chamada nem foi feita. */
	UNAVAILABLE,
	/** O cliente do upstream lançou exceção. */
	FAILURE

}
