package com.reportix.aisolate.toolserver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.JarDigests;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLoaderTest {

  @TempDir Path dir;

  @Test
  void givenPinnedJarWithPluginAndRules_whenLoaded_thenBothJoinTheClasspathOnes() throws Exception {
    // Arrange: a jar whose classes the classpath does not hold, scanned for its plugin
    Path jar = pluginJarWithRules();

    // Act
    PluginLoader.Loaded loaded =
        PluginLoader.load(dir.toString(), JarDigests.of(Files.readAllBytes(jar)));

    // Assert: the jar's package, an airlocks segment included, means nothing
    assertThat(loaded.plugins())
        .extracting(ToolPlugin::name)
        .containsExactlyInAnyOrder("hr-acme-tools", "payroll-tools", "archive-tools", "deep-tools");
    assertThat(loaded.ruleSets()).extracting(RuleSet::entity).contains("hr.payroll");
  }

  @Test
  void givenNoDirectory_whenLoaded_thenClasspathPluginsOnly() {
    // Arrange
    String noDirectory = "";

    // Act
    PluginLoader.Loaded loaded = PluginLoader.load(noDirectory, "");

    // Assert
    assertThat(loaded.plugins())
        .extracting(ToolPlugin::name)
        .containsExactlyInAnyOrder("hr-acme-tools", "payroll-tools", "archive-tools");
  }

  @Test
  void givenMissingDirectory_whenLoaded_thenFails() {
    // Arrange
    Path missing = dir.resolve("nowhere");

    // Act
    Throwable thrown = catchThrowable(() -> PluginLoader.load(missing.toString(), ""));

    // Assert
    assertThat(thrown).hasRootCauseInstanceOf(NoSuchFileException.class);
  }

  /** A plugin jar with a rule set of its own under {@code aisolate/rules}. */
  private Path pluginJarWithRules() throws IOException {
    Path jar = dir.resolve("deep-tools.jar");
    Map<String, byte[]> entries = new TreeMap<>(PluginJars.compile(PluginJars.DEEP_TOOLS));
    entries.put("aisolate/rules/hr.payroll.json", HandlerJars.rulesFile("hr.payroll"));
    PluginJars.writeJar(jar, entries);
    return jar;
  }
}
