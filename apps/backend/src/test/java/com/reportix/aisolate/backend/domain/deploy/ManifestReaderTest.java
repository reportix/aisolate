package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

class ManifestReaderTest {

  @Test
  void givenTheBuildsArtifact_whenRead_thenTheContentsAJarWouldGiveInPython() {
    // Arrange
    byte[] zip = PythonArtifacts.zip();

    // Act
    JarInspector.Contents contents = ManifestReader.read(PythonArtifacts.write(zip));

    // Assert
    assertThat(contents.language()).isEqualTo("python");
    assertThat(contents.digest()).matches("sha256:[0-9a-f]{64}");
    assertThat(contents.size()).isEqualTo(zip.length);
    assertThat(contents.tools())
        .containsExactly(
            new JarInspector.Tool("aggregate_records", "Aggregate", "hr-tools", "acme_hr"),
            new JarInspector.Tool("read_records", "Read", "hr-tools", "acme_hr"));
    assertThat(contents.handlers())
        .containsExactly(
            new JarInspector.Handler(
                "HandbookPreparator", "context_prep", 1, "Reads the handbook", List.of("hr")),
            new JarInspector.Handler("SalaryVerifier", "verifier", 0, null, List.of()));
    assertThat(contents.ruleSets()).containsExactly(new JarInspector.RuleSet("hr.compensation", 1));
  }

  static Stream<Arguments> refused() {
    return Stream.of(
        arguments(named("not a zip", (Supplier<byte[]>) () -> "PK?".getBytes()), "not a zip file"),
        arguments(
            named("no manifest", (Supplier<byte[]>) () -> zip(null, PythonArtifacts.files())),
            "artifact: no aisolate-manifest.json"),
        arguments(
            named("a Java manifest", edit(m -> m.put("language", "java"))),
            "artifact: aisolate-manifest.json /language"),
        arguments(named("an unknown key", edit(m -> m.put("main", "app.py"))), "main"),
        arguments(
            named(
                "an entry climbing out",
                (Supplier<byte[]>)
                    () -> zip(PythonArtifacts.manifest(), withFile("src/../../etc/x.py", ""))),
            "entry src/../../etc/x.py is not a plain path inside the zip"),
        arguments(
            named(
                "an absolute entry",
                (Supplier<byte[]>)
                    () -> zip(PythonArtifacts.manifest(), withFile("/etc/x.py", ""))),
            "entry /etc/x.py is not a plain path inside the zip"),
        arguments(
            named(
                "a symlink",
                (Supplier<byte[]>)
                    () ->
                        PythonArtifacts.withSymlink(
                            PythonArtifacts.zip(), "src/acme_hr/__init__.py")),
            "entry src/acme_hr/__init__.py is a symlink"),
        arguments(
            named(
                "a rule file the manifest does not list",
                (Supplier<byte[]>)
                    () ->
                        zip(
                            PythonArtifacts.manifest(),
                            withFile(
                                "rules/hr.leave.json", "{\"entity\":\"hr.leave\",\"rules\":[]}"))),
            "lists the rule files [rules/hr.compensation.json], the zip holds"),
        arguments(
            named(
                "a rule set in a folder",
                (Supplier<byte[]>)
                    () -> zip(PythonArtifacts.manifest(), withFile("rules/hr/x.json", "{}"))),
            "rules/hr/x.json: a rule set is rules/<entity>.json"),
        arguments(
            named(
                "a rule naming a column the entity lacks",
                (Supplier<byte[]>)
                    () ->
                        zip(
                            PythonArtifacts.manifest(),
                            withFile(
                                "rules/hr.compensation.json",
                                """
                                {"entity": "hr.compensation", "classification": "restricted", "rules": [
                                  {"name": "own", "principals": {"role": "employee"},
                                   "actions": ["read"], "effect": "allow", "row_filter": "true",
                                   "columns": {"iban": "mask"}}]}
                                """))),
            "rule set hr.compensation: rule own names column iban no plugin declares"),
        arguments(
            named(
                "a row key that is no column",
                edit(m -> ((ObjectNode) m.path("entities").get(0)).put("row_key", "id"))),
            "entity hr.compensation: row key id is not a column"),
        arguments(
            named(
                "a subject name that is no column",
                edit(m -> ((ObjectNode) m.path("entities").get(0)).put("subject_name", "name"))),
            "entity hr.compensation: subject name name is not one of its columns"),
        arguments(
            named(
                "a number column marked a fact",
                edit(
                    m ->
                        ((ObjectNode) m.path("entities").get(0).path("columns").get(0))
                            .put("fact", true))),
            "column employee_id is a number: only a text column can be a fact"),
        arguments(
            named(
                "a tool of no listed plugin",
                edit(m -> ((ObjectNode) m.path("tools").get(0)).put("plugin", "payroll"))),
            "tool aggregate_records names plugin payroll, which the manifest does not list"),
        arguments(
            named(
                "a handler twice",
                edit(
                    m ->
                        m.withArray("handlers")
                            .add(((ObjectNode) m.path("handlers").get(0)).deepCopy()))),
            "handler SalaryVerifier is declared twice"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refused")
  void givenABrokenArtifact_whenRead_thenOneArtifactErrorNamesIt(
      Supplier<byte[]> artifact, String expected) {
    // Arrange
    byte[] zip = artifact.get();

    // Act
    InvalidFormException refused =
        catchThrowableOfType(
            InvalidFormException.class, () -> ManifestReader.read(PythonArtifacts.write(zip)));

    // Assert
    assertThat(refused).isNotNull();
    assertThat(refused.getErrors()).anySatisfy(error -> assertThat(error).contains(expected));
  }

  private static Supplier<byte[]> edit(java.util.function.Consumer<ObjectNode> change) {
    return () -> {
      ObjectNode manifest = PythonArtifacts.manifest();
      change.accept(manifest);
      return zip(manifest, PythonArtifacts.files());
    };
  }

  private static Map<String, byte[]> withFile(String name, String content) {
    Map<String, byte[]> files = PythonArtifacts.files();
    files.put(name, content.getBytes(StandardCharsets.UTF_8));
    return files;
  }

  private static byte[] zip(ObjectNode manifest, Map<String, byte[]> files) {
    return PythonArtifacts.zip(manifest, files);
  }
}
