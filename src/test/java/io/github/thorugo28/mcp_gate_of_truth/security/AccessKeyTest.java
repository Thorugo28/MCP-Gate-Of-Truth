package io.github.thorugo28.mcp_gate_of_truth.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeyTest {

	@Test
	void seesListedUpstreamsOrAllWithStar() {
		AccessKey fs = new AccessKey("fs", List.of("filesystem"), false);
		AccessKey all = new AccessKey("cli", List.of("*"), false);
		AccessKey none = new AccessKey("nada", List.of(), false);

		assertThat(fs.canSee("filesystem")).isTrue();
		assertThat(fs.canSee("notion")).isFalse();
		assertThat(all.canSee("notion")).isTrue();
		assertThat(none.canSee("filesystem")).isFalse();
	}

}
