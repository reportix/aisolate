package com.reportix.aisolate.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class JarDigestsTest {

  private static final byte[] JAR = "a jar".getBytes(StandardCharsets.UTF_8);

  @TempDir Path dir;

  @Test
  void givenPinnedJarAndAnotherFile_whenPinnedJars_thenTheJarOnly() throws IOException {
    // Arrange
    Files.write(dir.resolve("hr.jar"), JAR);
    Files.writeString(dir.resolve("README.txt"), "not a jar");

    // Act
    var jars = JarDigests.pinnedJars(dir.toString(), " " + JarDigests.of(JAR).toUpperCase() + ",");

    // Assert: listed in any case, blanks between entries ignored
    assertThat(jars).containsExactly(Map.entry(JarDigests.of(JAR), dir.resolve("hr.jar")));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " , ", "sha256:0000"})
  void givenJarNobodyPinned_whenPinnedJars_thenRefusedNamingIt(String digests) throws IOException {
    // Arrange: an empty list pins nothing, it does not switch the check off
    Files.write(dir.resolve("hr.jar"), JAR);

    // Act
    Throwable thrown = catchThrowable(() -> JarDigests.pinnedJars(dir.toString(), digests));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hr.jar")
        .hasMessageContaining("AISOLATE_PLUGIN_DIGESTS");
  }
}
