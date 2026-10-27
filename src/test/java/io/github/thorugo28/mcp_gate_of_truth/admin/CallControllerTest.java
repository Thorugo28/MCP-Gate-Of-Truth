package io.github.thorugo28.mcp_gate_of_truth.admin;

import java.time.Instant;

import io.github.thorugo28.mcp_gate_of_truth.audit.CallLog;
import io.github.thorugo28.mcp_gate_of_truth.config.AuditConfig;
import io.github.thorugo28.mcp_gate_of_truth.config.SecurityConfig;
import io.github.thorugo28.mcp_gate_of_truth.routing.Outcome;
import io.github.thorugo28.mcp_gate_of_truth.routing.ToolCalledEvent;
import io.github.thorugo28.mcp_gate_of_truth.routing.Via;
import io.github.thorugo28.mcp_gate_of_truth.security.AccessKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Com o {@link CallLog} de verdade (buffer de 3), alimentado direto pelos eventos.
 */
@WebMvcTest(value = CallController.class, properties = { "gate.security.keys.cli.value=teste-cli",
		"gate.security.keys.painel.value=teste-painel", "gate.audit.recent-size=3" })
@Import({ SecurityConfig.class, AccessKeys.class, AuditConfig.class, CallLog.class })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class CallControllerTest {

	private static final String PAINEL = "Bearer teste-painel";

	private static final Instant AT = Instant.parse("2026-10-26T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private CallLog callLog;

	@BeforeEach
	void calls() {
		callLog.onToolCalled(new ToolCalledEvent(AT, "cli", Via.MCP, "filesystem__read_file", "filesystem", 12,
				Outcome.OK, null));
		callLog.onToolCalled(new ToolCalledEvent(AT.plusSeconds(1), "painel", Via.ADMIN, "notion__API-get-self",
				"notion", 5000, Outcome.TIMEOUT, "Upstream 'notion' não respondeu em 5s"));
		callLog.onToolCalled(new ToolCalledEvent(AT.plusSeconds(2), "cli", Via.MCP, "filesystem__read_file", null, 0,
				Outcome.UNAVAILABLE, "Upstream indisponível"));
	}

	@Test
	void listsNewestFirstWithTheAuditFields() throws Exception {
		mvc.perform(get("/admin/calls").header("Authorization", PAINEL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].outcome").value("UNAVAILABLE"))
			.andExpect(jsonPath("$[0].upstream").value(nullValue()))
			.andExpect(jsonPath("$[1].at").value("2026-10-26T12:00:01Z"))
			.andExpect(jsonPath("$[1].caller").value("painel"))
			.andExpect(jsonPath("$[1].via").value("admin"))
			.andExpect(jsonPath("$[1].tool").value("notion__API-get-self"))
			.andExpect(jsonPath("$[1].upstream").value("notion"))
			.andExpect(jsonPath("$[1].durationMs").value(5000))
			.andExpect(jsonPath("$[1].outcome").value("TIMEOUT"))
			.andExpect(jsonPath("$[1].error").value("Upstream 'notion' não respondeu em 5s"))
			.andExpect(jsonPath("$[2].via").value("mcp"))
			.andExpect(jsonPath("$[2].error").value(nullValue()));
	}

	@Test
	void filtersAndLimit() throws Exception {
		mvc.perform(get("/admin/calls?caller=cli&tool=filesystem__read_file&outcome=OK").header("Authorization", PAINEL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].durationMs").value(contains(12)));
		mvc.perform(get("/admin/calls?limit=1").header("Authorization", PAINEL))
			.andExpect(jsonPath("$[*].outcome").value(contains("UNAVAILABLE")));
		// acima do buffer vale o buffer; filtro vazio não filtra
		mvc.perform(get("/admin/calls?limit=1000&caller=&tool=&outcome=").header("Authorization", PAINEL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3));
	}

	@Test
	void badParamsAreBadRequest() throws Exception {
		mvc.perform(get("/admin/calls?limit=0").header("Authorization", PAINEL)).andExpect(status().isBadRequest());
		mvc.perform(get("/admin/calls?outcome=XPTO").header("Authorization", PAINEL)).andExpect(status().isBadRequest());
	}

	@Test
	void onlyForAdminKey() throws Exception {
		mvc.perform(get("/admin/calls")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/calls").header("Authorization", "Bearer teste-cli"))
			.andExpect(status().isForbidden())
			.andExpect(content().string(""));
	}

}
