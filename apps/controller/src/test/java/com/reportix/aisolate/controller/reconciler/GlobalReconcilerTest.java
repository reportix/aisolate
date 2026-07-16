package com.reportix.aisolate.controller.reconciler;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.reconciler.global.GlobalReconciler;
import com.reportix.aisolate.controller.support.TestResources;
import com.reportix.aisolate.controller.support.TestSettings;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The Global's status: what the loader refuses, and the entries every catalog leaves out. */
class GlobalReconcilerTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "ollama|https://models.example.com|Ready|''",
        "ollama|http://ollama.elsewhere.example:11434|Ready|llms[gemma].url: the host"
            + " ollama.elsewhere.example is not allowed",
        "gpt|https://models.example.com|Invalid|spec.llms[2].provider gpt is not one of [ollama,"
            + " openai]"
      })
  void givenAGlobalLlm_whenReconciled_thenTheStatusNamesWhatNoReleaseServes(
      String provider, String url, String phase, String problem) {
    // Arrange: the fixtures' Global and one more LLM
    GlobalDocument.Spec fixtures = FIXTURES.global().spec();
    Global global =
        TestResources.global(
            new GlobalDocument.Spec(
                Stream.concat(
                        fixtures.llms().stream(),
                        Stream.of(new Llm("gemma", provider, url, "gemma3")))
                    .toList(),
                fixtures.dataSources()));

    // Act
    ResourceStatus status =
        new GlobalReconciler(TestSettings.SETTINGS)
            .reconcile(global, context())
            .getResource()
            .orElseThrow()
            .getStatus();

    // Assert
    assertThat(status.phase()).isEqualTo(phase);
    if (problem.isEmpty()) {
      assertThat(status.problems()).isEmpty();
    } else {
      assertThat(status.problems()).singleElement().satisfies(p -> assertThat(p).contains(problem));
    }
  }

  @SuppressWarnings("unchecked")
  private static Context<Global> context() {
    return mock(Context.class);
  }
}
