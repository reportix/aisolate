package com.reportix.aisolate.backend.domain.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.plugins.JarDigests;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JarInspectorTest {

  /** A conversation memory named like {@link HandlerJars#MEMORY}'s, in another package. */
  private static final PluginJars.Plugin OTHER_MEMORY =
      new PluginJars.Plugin(
          "com.example.other.SessionMemory",
          """
          package com.example.other;

          import com.reportix.aisolate.sdk.*;

          public class SessionMemory implements ConversationMemory {
            public void recall(Exchange exchange, AIsolateManager aisolate) {}

            public void remember(Exchange exchange, Answer answer, AIsolateManager aisolate) {}
          }
          """);

  /** A verifier that says in one sentence what it does. */
  private static final PluginJars.Plugin DESCRIBED_VERIFIER =
      new PluginJars.Plugin(
          "com.example.hr.FigureChecker",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          @Description("Check the figures")
          @DataSources({"hr", "clients"})
          public class FigureChecker implements AnswerVerifier {
            @Override
            public int order() {
              return 3;
            }

            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
              return Verdict.pass("checked");
            }
          }
          """);

  /** Declares an entity, names no data source it lives in. */
  private static final PluginJars.Plugin NO_DATA_SOURCE =
      new PluginJars.Plugin(
          "com.example.broken.NoDataSource",
          """
          package com.example.broken;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class NoDataSource implements ToolPlugin {
            public String name() {
              return "hr-acme-tools";
            }

            public String dataSource() {
              return null;
            }

            public List<Entity> entities() {
              return List.of(new Entity("hr.employee", "employees", "id"));
            }

            public List<Tool> tools() {
              return List.of();
            }
          }
          """);

  @Test
  void givenPluginsInAnyPackage_whenRead_thenEveryToolWithItsPluginAndDataSource() {
    // Arrange: a package with airlocks segments means nothing
    byte[] jar =
        PluginJars.buildJar(PluginJars.compile(PluginJars.DEEP_TOOLS, PluginJars.PAYROLL_TOOLS));

    // Act
    JarInspector.Contents contents = JarInspector.read(PythonArtifacts.write(jar));

    // Assert: the backend's own loader cannot see the classes
    assertThat(contents.tools())
        .containsExactly(
            new JarInspector.Tool(
                "read_records", "Deep: at most 20 records.", "deep-tools", "acme_hr"),
            new JarInspector.Tool(
                "export_payslips", "Exports the user's payslips.", "payroll-tools", "acme_hr"));
    assertThat(contents.digest()).isEqualTo(JarDigests.of(jar));
    assertThat(contents.size()).isEqualTo(jar.length);
    assertThat(
            ToolPlugin.class.getClassLoader().getResource("com/example/payroll/PayrollTools.class"))
        .isNull();
  }

  @Test
  void givenJarWithToolsHandlersAndRules_whenRead_thenAllThree() {
    // Arrange
    Map<String, byte[]> entries =
        new LinkedHashMap<>(
            PluginJars.compile(
                PluginJars.HR_TOOLS, HandlerJars.HANDBOOK, HandlerJars.STRICT_VERIFIER));
    entries.put("aisolate/rules/hr.compensation.json", HandlerJars.rulesFile("hr.compensation"));
    entries.put("aisolate/rules/hr.payroll.json", HandlerJars.rulesFile("hr.payroll"));

    // Act
    JarInspector.Contents contents =
        JarInspector.read(PythonArtifacts.write(PluginJars.buildJar(entries)));

    // Assert
    assertThat(contents.toolNames()).containsExactly("read_records", "aggregate_records");
    assertThat(contents.handlers())
        .containsExactly(
            new JarInspector.Handler("HandbookPreparator", "context_prep", 2, null, List.of()),
            new JarInspector.Handler("StrictVerifier", "verifier", 1, null, List.of()));
    assertThat(contents.ruleSets())
        .containsExactlyInAnyOrder(
            new JarInspector.RuleSet("hr.compensation", 1),
            new JarInspector.RuleSet("hr.payroll", 1));
  }

  static Stream<Arguments> givenJarWithOneKindOnly_whenRead_thenThatKindAlone() {
    return Stream.of(
        // Without a @Description the sentence is null, not empty: the wire says string or null
        arguments(
            named("a handler", HandlerJars.jarOf(HandlerJars.MEMORY)),
            List.of(new JarInspector.Handler("SessionMemory", "memory", 1, null, List.of())),
            List.of()),
        arguments(
            named(
                "a rule set",
                PluginJars.buildJar(
                    Map.of("aisolate/rules/hr.json", HandlerJars.rulesFile("hr.compensation")))),
            List.of(),
            List.of(new JarInspector.RuleSet("hr.compensation", 1))));
  }

  @ParameterizedTest
  @MethodSource
  void givenJarWithOneKindOnly_whenRead_thenThatKindAlone(
      byte[] jar, List<JarInspector.Handler> handlers, List<JarInspector.RuleSet> ruleSets) {
    // Arrange

    // Act
    JarInspector.Contents contents = JarInspector.read(PythonArtifacts.write(jar));

    // Assert
    assertThat(contents.tools()).isEmpty();
    assertThat(contents.handlers()).isEqualTo(handlers);
    assertThat(contents.ruleSets()).isEqualTo(ruleSets);
  }

  @Test
  void givenHandlerWithDescriptionAndDataSources_whenInspected_thenBothAreKept() {
    // Arrange
    byte[] jar = HandlerJars.jarOf(DESCRIBED_VERIFIER);

    // Act
    JarInspector.Contents contents = JarInspector.read(PythonArtifacts.write(jar));

    // Assert
    assertThat(contents.handlers())
        .containsExactly(
            new JarInspector.Handler(
                "FigureChecker", "verifier", 3, "Check the figures", List.of("hr", "clients")));
  }

  static Stream<Arguments> givenUnusableJar_whenRead_thenOneJarError() {
    return Stream.of(
        arguments(
            named("bytes that are no jar", "not a jar".getBytes(StandardCharsets.UTF_8)),
            "jar: not a jar file"),
        arguments(
            named(
                "a plain class only", PluginJars.buildJar(PluginJars.compile(HandlerJars.HELPER))),
            "jar: nothing to load: no ToolPlugin class, no handler class, no aisolate/rules"
                + " file"),
        arguments(
            named(
                "a plugin whose constructor fails",
                PluginJars.buildPluginJar(PluginJars.BROKEN_TOOLS)),
            "jar: com.example.broken.BrokenTools could not be loaded: no database"),
        arguments(
            named(
                "a tool published twice",
                PluginJars.buildJar(
                    PluginJars.compile(PluginJars.HR_TOOLS, PluginJars.DEEP_TOOLS))),
            "jar: tool read_records is published twice"),
        // An airlock names a handler by its simple name
        arguments(
            named("a handler declared twice", HandlerJars.jarOf(HandlerJars.MEMORY, OTHER_MEMORY)),
            "jar: handler SessionMemory is declared twice"),
        arguments(
            named(
                "a nested rules path",
                PluginJars.buildJar(
                    Map.of("aisolate/rules/airlocks/hr/x.json", HandlerJars.rulesFile("x")))),
            "jar: aisolate/rules/airlocks/hr/x.json: a rule set is rules/<entity>.json"),
        arguments(
            named(
                "a plugin with entities and no data source",
                PluginJars.buildPluginJar(NO_DATA_SOURCE)),
            "jar: plugin hr-acme-tools declares entities but names no data source"),
        // The tool server would refuse to start with it: the deploy refuses it first.
        arguments(
            named(
                "two rule sets for one entity",
                PluginJars.buildJar(
                    Map.of(
                        "aisolate/rules/a.json", HandlerJars.rulesFile("hr.compensation"),
                        "aisolate/rules/b.json", HandlerJars.rulesFile("hr.compensation")))),
            "jar: two rule sets for entity hr.compensation"),
        arguments(
            named(
                "a rules file with a scope",
                PluginJars.buildJar(
                    Map.of(
                        "aisolate/rules/hr.json",
                        withKey(HandlerJars.rulesFile("hr"), "\"scope\":\"acme-hr\"")))),
            "jar: aisolate/rules/hr.json: unknown key 'scope' (a rule set has entity,"
                + " classification and rules)"));
  }

  @ParameterizedTest
  @MethodSource
  void givenUnusableJar_whenRead_thenOneJarError(byte[] jar, String error) {
    // Arrange

    // Act
    InvalidFormException thrown =
        catchThrowableOfType(
            InvalidFormException.class, () -> JarInspector.read(PythonArtifacts.write(jar)));

    // Assert
    assertThat(thrown.getErrors()).containsExactly(error);
  }

  /** The rules file with one more root key. */
  private static byte[] withKey(byte[] rulesFile, String key) {
    return new String(rulesFile, StandardCharsets.UTF_8)
        .replaceFirst("}$", "," + key + "}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
