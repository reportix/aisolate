package com.reportix.aisolate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

class TracingEndpointConditionTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "http://collector:4318|true",
        "'   '|false",
        // A quote must not end a SpEL string literal and run the rest as an expression.
        "http://x'.isBlank() or T(java.lang.Runtime).getRuntime().exit(1) or '|true"
      })
  void givenAnEndpointValue_whenTheConditionIsRead_thenSetMeansNonBlankAndNothingRuns(
      String endpoint, boolean expected) {
    // Arrange
    ConditionContext context = mock(ConditionContext.class);
    when(context.getEnvironment())
        .thenReturn(new MockEnvironment().withProperty(TracingConfig.ENDPOINT, endpoint));

    // Act
    boolean set = new TracingConfig.EndpointSet().matches(context, null);

    // Assert
    assertThat(set).isEqualTo(expected);
  }
}
