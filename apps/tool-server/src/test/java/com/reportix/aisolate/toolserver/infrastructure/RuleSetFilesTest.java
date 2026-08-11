package com.reportix.aisolate.toolserver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleSetFilesTest {

  @TempDir Path dir;

  @Test
  void givenTestClasspath_whenDiscover_thenTheResourceFileIsFound() {
    // Arrange
    ClassLoader loader = getClass().getClassLoader();

    // Act
    List<RuleSet> found = RuleSetFiles.discover(loader, List.of());

    // Assert
    assertThat(found).extracting(RuleSet::entity).contains("hr.compensation");
  }

  @Test
  void givenJarWithoutDirectoryEntries_whenDiscover_thenTheFileIsFound() throws IOException {
    // Arrange: some jar tools omit directory entries
    Path jar = dir.resolve("flat.jar");
    PluginJars.writeJar(
        jar, Map.of("aisolate/rules/hr.payroll.json", HandlerJars.rulesFile("hr.payroll")));

    // Act
    List<RuleSet> found = discover(jar);

    // Assert
    assertThat(found).extracting(RuleSet::entity).containsExactly("hr.payroll");
  }

  @Test
  void givenMalformedRulesFile_whenDiscover_thenRefusedNamingTheFile() throws IOException {
    // Arrange
    Path jar = dir.resolve("broken.jar");
    PluginJars.writeJar(
        jar,
        Map.of(
            "aisolate/rules/broken.json",
            "{\"entity\":\"x\",\"rules\":[{\"name\":\"r\",\"effect\":\"allow\",\"principals\":{}}]}"
                .getBytes(StandardCharsets.UTF_8)));

    // Act
    Throwable thrown = catchThrowable(() -> discover(jar));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aisolate/rules/broken.json")
        .hasMessageContaining("principals.role");
  }

  @Test
  void givenNestedPath_whenDiscover_thenRefusedNamingTheFile() throws IOException {
    // Arrange: a rule set sits in the directory itself; any folder is a mistake, never ignored
    String path = "airlocks/acme-hr/hr.payroll.json";
    Path jar = dir.resolve("nested.jar");
    PluginJars.writeJar(
        jar,
        Map.of(
            "aisolate/rules/hr.compensation.json",
            HandlerJars.rulesFile("hr.compensation"),
            "aisolate/rules/" + path,
            HandlerJars.rulesFile("hr.payroll")));

    // Act
    Throwable thrown = catchThrowable(() -> discover(jar));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("aisolate/rules/" + path + ": a rule set is rules/<entity>.json");
  }

  @Test
  void givenNonJsonFileInTheDirectory_whenDiscover_thenIgnored() throws IOException {
    // Arrange
    Path jar = dir.resolve("notes.jar");
    PluginJars.writeJar(
        jar, Map.of("aisolate/rules/README.md", "notes".getBytes(StandardCharsets.UTF_8)));

    // Act
    List<RuleSet> found = discover(jar);

    // Assert
    assertThat(found).isEmpty();
  }

  /** The jar alone: a classpath that holds no rules. */
  private static List<RuleSet> discover(Path jar) {
    return RuleSetFiles.discover(ClassLoader.getPlatformClassLoader(), List.of(jar));
  }
}
