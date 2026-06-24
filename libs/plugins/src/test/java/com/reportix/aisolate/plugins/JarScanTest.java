package com.reportix.aisolate.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JarScanTest {

  /** Class and constructor package-private, as a developer may leave them: the scan opens both. */
  private static final PluginJars.Plugin QUIET_TOOLS =
      new PluginJars.Plugin(
          "com.example.hr.QuietTools",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          import java.util.Map;

          class QuietTools implements ToolPlugin {
            QuietTools() {}

            public String name() {
              return "quiet-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of(new Tool("count_customers", "Counts the customers.", Map.of(), null));
            }
          }
          """);

  private static final PluginJars.Plugin BOTH =
      new PluginJars.Plugin(
          "com.example.broken.Both",
          """
          package com.example.broken;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class Both implements ToolPlugin, AnswerVerifier {
            public String name() {
              return "both";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of();
            }

            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
              return Verdict.pass("cited");
            }
          }
          """);

  @Test
  void
      givenJarWithPluginsAHandlerAndAHelper_whenScanned_thenPluginsAndHandlersWhateverTheirPackage()
          throws IOException {
    // Arrange: plugins in a shallow and a deep package, one package-private, a handler, a plain
    // class
    byte[] jar =
        HandlerJars.jarOf(
            PluginJars.HR_TOOLS,
            QUIET_TOOLS,
            PluginJars.DEEP_TOOLS,
            PluginJars.STRICT_HR_TOOLS,
            HandlerJars.STRICT_VERIFIER,
            HandlerJars.HELPER);

    // Act
    JarScan scan = JarScan.of(List.of(HandlerJars.writeJar(jar)));

    // Assert
    assertThat(scan.plugins())
        .extracting(plugin -> plugin.getClass().getSimpleName(), ToolPlugin::dataSource)
        .containsExactlyInAnyOrder(
            tuple("HrTools", "acme_hr"),
            tuple("QuietTools", "acme_hr"),
            tuple("DeepTools", "acme_hr"),
            tuple("StrictTools", "acme_hr"));
    assertThat(scan.plugins())
        .filteredOn(plugin -> plugin.getClass().getSimpleName().equals("StrictTools"))
        .flatExtracting(ToolPlugin::tools)
        .extracting(Tool::name, Tool::description)
        .containsExactly(
            tuple("read_records", "Strict: at most one record."),
            tuple("export_payslips", "Exports the user's payslips."));
    assertThat(scan.handlers())
        .extracting(HandlerCandidate::name, HandlerCandidate::stage, HandlerCandidate::order)
        .containsExactly(tuple("StrictVerifier", Stage.VERIFIER, 1));
  }

  private static final PluginJars.Plugin TWO_STAGES =
      new PluginJars.Plugin(
          "com.example.broken.TwoStages",
          """
          package com.example.broken;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class TwoStages implements AnswerVerifier, ConversationMemory {
            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
              return Verdict.pass("cited");
            }

            public void recall(Exchange exchange, AIsolateManager aisolate) {}

            public void remember(Exchange exchange, Answer answer, AIsolateManager aisolate) {}

            public int order() {
              return 0;
            }
          }
          """);

  @Test
  void givenClassImplementingTwoStages_whenScanned_thenRefused() {
    // Arrange
    byte[] jar = HandlerJars.jarOf(TWO_STAGES);

    // Act
    Throwable thrown = catchThrowable(() -> JarScan.of(List.of(HandlerJars.writeJar(jar))));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("com.example.broken.TwoStages implements more than one handler stage");
  }

  @Test
  void givenClassThatIsPluginAndHandler_whenScanned_thenRefused() {
    // Arrange
    byte[] jar = HandlerJars.jarOf(BOTH);

    // Act
    Throwable thrown = catchThrowable(() -> JarScan.of(List.of(HandlerJars.writeJar(jar))));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("com.example.broken.Both is both a tool plugin and a handler");
  }

  @Test
  void givenPluginWhoseConstructorThrows_whenScanned_thenRefusedNamingTheClassAndCause() {
    // Arrange
    byte[] jar = PluginJars.buildPluginJar(PluginJars.BROKEN_TOOLS);

    // Act
    Throwable thrown = catchThrowable(() -> JarScan.of(List.of(HandlerJars.writeJar(jar))));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("com.example.broken.BrokenTools could not be created")
        .hasMessageContaining("no database");
  }
}
