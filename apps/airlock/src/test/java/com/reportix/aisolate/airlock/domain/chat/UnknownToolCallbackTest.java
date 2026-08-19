package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

class UnknownToolCallbackTest {

  @Test
  void givenAnyName_whenResolved_thenAStubIsReturnedRatherThanNothing() {
    // Arrange: the resolver shape the tool-calling manager consults
    ToolCallbackResolver resolver = UnknownToolCallback::new;

    // Act
    ToolCallback resolved = resolver.resolve("invented_by_the_model");

    // Assert: the model hears this and can try something else, instead of the exchange dying;
    // the meters see one name for every invented one
    assertThat(resolved).isNotNull();
    assertThat(resolved.getToolDefinition().name()).isEqualTo("unknown");
    assertThat(resolved.call("{}")).isEqualTo("Tool not available: invented_by_the_model");
  }
}
