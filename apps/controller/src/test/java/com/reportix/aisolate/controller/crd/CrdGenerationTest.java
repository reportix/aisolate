package com.reportix.aisolate.controller.crd;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaProps;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CrdGenerationTest {

  /** Per file: text present, text absent, and the spec's fields exactly. */
  static Stream<Arguments> schemas() {
    List<String> llms =
        List.of("llms", "llms.id", "llms.provider", "llms.url", "llms.model", "llms.secretRef");
    List<String> dataSources =
        List.of("dataSources", "dataSources.id", "dataSources.url", "dataSources.secretRef");
    return Stream.of(
        Arguments.of(
            "globals.yaml",
            List.of("kind: \"Global\"", "self.metadata.name == 'aisolate'"),
            List.of("password"),
            concat(llms, dataSources)),
        Arguments.of(
            "spaces.yaml",
            List.of("kind: \"Space\"", "self.metadata.name.matches('^[a-z][a-z0-9-]{1,40}$')"),
            // a space may list no role
            List.of("access.users", "size(self.spec.access.callers) > 0"),
            concat(
                List.of("displayName", "description", "access", "access.roles"),
                llms,
                dataSources)),
        Arguments.of(
            "airlocks.yaml",
            List.of(
                "kind: \"Airlock\"",
                "plural: \"airlocks\"",
                "self.metadata.name.matches('^[a-z][a-z0-9-]{1,40}$')",
                "size(self.spec.space) > 0",
                "has(self.spec.access) && size(self.spec.access.callers) > 0",
                "jsonPath: \".spec.space\"",
                "jsonPath: \".status.readyReplicas\""),
            // its roles may be none: the space's roles may ask it; a model and a prompt come with
            // the first deploy; a credential never sits in a resource
            List.of(
                "access.users",
                "size(self.spec.llm) > 0",
                "size(self.spec.prompt) > 0",
                "password"),
            concat(
                List.of(
                    "space",
                    "displayName",
                    "description",
                    "access",
                    "access.roles",
                    "access.callers",
                    "access.clients",
                    "preLLM",
                    "preLLM.contextPreparator",
                    "preLLM.contextPreparator.handlers",
                    "preLLM.conversationMemory",
                    "preLLM.conversationMemory.handlers",
                    "llm",
                    "prompt",
                    "tools",
                    "postLLM",
                    "postLLM.answerVerifier",
                    "postLLM.answerVerifier.handlers",
                    "jar",
                    "jar.digest",
                    "jar.tools",
                    "jar.language"),
                llms,
                dataSources)));
  }

  @SafeVarargs
  private static List<String> concat(List<String>... lists) {
    return Stream.of(lists).flatMap(List::stream).toList();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schemas")
  void givenResourceClasses_whenGenerated_thenEachSchemaHoldsItsFieldsRulesAndStatus(
      String file,
      List<String> present,
      List<String> absent,
      List<String> fields,
      @TempDir Path dir)
      throws IOException {
    // Arrange
    Path generated = dir.resolve(file);

    // Act
    CrdGeneration.generate(dir);

    // Assert: what the API server refuses at admission, and the status tools read
    String crd = Files.readString(generated);
    assertThat(crd)
        .contains("group: \"aisolate.dev\"")
        .contains("name: \"v1\"")
        .contains("status: {}")
        .contains("jsonPath: \".status.phase\"")
        .contains("lastTransitionTime:")
        .contains(present);
    assertThat(absent).allSatisfy(text -> assertThat(crd).doesNotContain(text));
    // Exactly: a stray field, an old one or a new one, fails here
    assertThat(listSpecFields(crd)).containsExactlyInAnyOrderElementsOf(fields);
  }

  @Test
  void givenResourceClasses_whenGenerated_thenExactlyTheThreeCrds(@TempDir Path dir)
      throws IOException {
    // Arrange / Act
    CrdGeneration.generate(dir);

    // Assert: the airlock is what runs; no fourth kind
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(f -> f.getFileName().toString()))
          .containsExactlyInAnyOrder("globals.yaml", "spaces.yaml", "airlocks.yaml");
    }
  }

  /** Every property of the spec schema by its dotted path, a list's item fields under its name. */
  private static Set<String> listSpecFields(String crd) {
    JSONSchemaProps spec =
        Serialization.unmarshal(crd, CustomResourceDefinition.class)
            .getSpec()
            .getVersions()
            .getFirst()
            .getSchema()
            .getOpenAPIV3Schema()
            .getProperties()
            .get("spec");
    Set<String> fields = new TreeSet<>();
    collectFields(spec, "", fields);
    return fields;
  }

  private static void collectFields(JSONSchemaProps schema, String prefix, Set<String> fields) {
    JSONSchemaProps object = schema.getItems() == null ? schema : schema.getItems().getSchema();
    if (object.getProperties() == null) {
      return;
    }
    object
        .getProperties()
        .forEach(
            (name, child) -> {
              fields.add(prefix + name);
              collectFields(child, prefix + name + ".", fields);
            });
  }
}
