package com.reportix.aisolate.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/** What every command on a project shares, as a picocli mixin: its {@code --path}, its failure. */
final class ProjectCommand {

  @Option(
      names = {"-p", "--path"},
      defaultValue = ".",
      description = "Project directory (default: current).")
  Path dir;

  /** A command's body, which reads and writes files. */
  interface Body {
    int run() throws IOException;
  }

  /** The body's exit code; an expected failure is one error line and exit 1. */
  static int run(Console console, Body body) {
    try {
      return body.run();
    } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
      console.error(e.getMessage());
      return 1;
    } catch (IOException e) {
      console.error(e.getClass().getSimpleName() + ": " + e.getMessage());
      return 1;
    }
  }
}
