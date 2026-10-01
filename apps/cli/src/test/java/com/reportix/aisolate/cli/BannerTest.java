package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BannerTest {

  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(ints = {200, 66, 30})
  void givenTerminalWidth_whenRender_thenEveryRowIsTheBoxWidth(int width) {
    // Arrange
    Banner banner = new Banner("0.1.0", width, false);

    // Act
    List<String> rows = banner.render(dir).lines().toList();

    // Assert
    int expected = Math.clamp(width, 40, 80);
    assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(expected));
    assertThat(rows.getFirst()).startsWith("╭─ AIsolate v0.1.0 ");
    assertThat(rows.getLast()).startsWith("╰").endsWith("╯");
  }

  @Test
  void givenProjectDirectory_whenRender_thenSpaceRepoAndQuickStart() throws IOException {
    // Arrange
    TestProject.create(dir, "hr-lab", "http://localhost:8080");

    // Act
    String box = new Banner("0.1.0", 80, false).render(dir);

    // Assert
    assertThat(box)
        .contains("Welcome to AIsolate")
        .contains("Space  hr-lab")
        .contains("Repo   ")
        .contains("Quick start")
        .contains("aisolate login")
        .contains("aisolate deploy");
  }

  @Test
  void givenNoProject_whenRender_thenNoSpaceLine() {
    // Arrange
    Banner banner = new Banner("0.1.0", 80, false);

    // Act
    String box = banner.render(dir);

    // Assert
    assertThat(box).doesNotContain("Space ").contains("Repo   ");
  }

  @Test
  void givenColorOn_whenRender_thenEscapesThatDoNotChangeTheWidth() {
    // Arrange
    Banner banner = new Banner("0.1.0", 80, true);

    // Act
    List<String> rows = banner.render(dir).lines().toList();

    // Assert
    assertThat(String.join("", rows)).contains("\u001b[");
    assertThat(rows)
        .allSatisfy(row -> assertThat(row.replaceAll("\u001b\\[[0-9;]*m", "")).hasSize(80));
  }
}
