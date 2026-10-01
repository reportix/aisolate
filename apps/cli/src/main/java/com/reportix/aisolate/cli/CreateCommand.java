package com.reportix.aisolate.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code aisolate create -s=<space> -t=<template> [--airlock=<id>] [--language=python|java]}: a
 * project directory named after the template, or after the template and the airlock when one is
 * given, in the language's template tree with that language's SDK.
 */
@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description =
        "Create a project from a template into ./<template>, or ./<template>-<airlock> with"
            + " --airlock.")
@RequiredArgsConstructor
final class CreateCommand implements Callable<Integer> {

  enum Template {
    company_hr("hr-contracts");

    /** The airlock the project deploys when {@code --airlock} names none. */
    final String airlock;

    Template(String airlock) {
      this.airlock = airlock;
    }

    /** The template directory and the option's spelling: dashed, as the showcases are named. */
    @Override
    public String toString() {
      return name().replace('_', '-');
    }
  }

  /** The extraction step, replaceable so a test can fail it partway. */
  interface Scaffold {
    void into(String template, String space, String airlock, Path project) throws IOException;
  }

  private final Console console;

  Scaffold scaffold =
      (template, space, airlock, project) -> {
        // this.: the option is declared below, and set before any scaffold runs.
        Templates.extract(
            this.language, template, Map.of("SPACE", space, "AIRLOCK", airlock), project);
        if (this.language == Language.python) {
          Templates.writePythonSdk(project.resolve("libs"));
        } else {
          Templates.writeSdkJar(project.resolve("libs"));
        }
      };

  @Option(
      names = {"-s", "--space"},
      required = true,
      description = "The space the project deploys into.")
  String space;

  @Option(
      names = {"-t", "--template"},
      required = true,
      description =
          "Template: ${COMPLETION-CANDIDATES}; the directory name, followed by -<airlock> when"
              + " --airlock is given.")
  Template template;

  @Option(
      names = {"-a", "--airlock"},
      description =
          "The airlock the project deploys, written into aisolate.yaml (default: the"
              + " template's: hr-contracts).")
  String airlock;

  @Option(
      names = {"-p", "--path"},
      defaultValue = ".",
      description = "Parent directory (default: current).")
  Path parent;

  @Option(
      names = {"-l", "--language"},
      defaultValue = "python",
      description =
          "The project's language: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). Python"
              + " builds with uv or Python 3.13.")
  Language language;

  @Override
  public Integer call() {
    // Refused here, not at the first deploy after a build and a sign-in.
    if (!ProjectFile.SLUG.matcher(space).matches()) {
      console.error("space must be " + ProjectFile.SLUG_RULE);
      return 2;
    }
    if (airlock != null && !ProjectFile.SLUG.matcher(airlock).matches()) {
      console.error("airlock must be " + ProjectFile.SLUG_RULE);
      return 2;
    }
    // One template, several airlocks: one directory each.
    Path project = parent.resolve(airlock == null ? template.toString() : template + "-" + airlock);
    if (Files.exists(project, LinkOption.NOFOLLOW_LINKS)) {
      console.error(project + " already exists");
      return 1;
    }
    try {
      scaffold.into(
          template.toString(), space, airlock == null ? template.airlock : airlock, project);
      writeEnvFile(project);
    } catch (IOException | IllegalStateException e) {
      // The project did not exist before, so a half-written one is ours to remove; a rerun works.
      deleteQuietly(project);
      String reason =
          e instanceof FileSystemException fse && fse.getReason() != null
              ? fse.getReason()
              : e.getMessage();
      console.error("cannot create " + project + ": " + reason);
      return 1;
    }
    console.println("Created " + project + " from the " + template + " template.");
    // Words, not `&&`: Windows PowerShell 5.1 has no `&&`.
    console.println(
        "Next: cd "
            + project
            + ", then aisolate login and aisolate deploy (the space exists in the AIsolate UI"
            + " first; the deploy creates or updates the airlock)");
    return 0;
  }

  /** {@code .env} from the template's {@code .env.example}, unless the template brought one. */
  private static void writeEnvFile(Path project) throws IOException {
    Path example = project.resolve(".env.example");
    Path env = project.resolve(".env");
    if (!Files.isRegularFile(example) || Files.exists(env, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.copy(example, env);
    try {
      // Credentials go here: owner-only, like the session file.
      Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException windows) {
      // No POSIX permissions there.
    }
  }

  private static void deleteQuietly(Path dir) {
    try (var paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException | UncheckedIOException ignored) {
      // Nothing was written, or it is already gone.
    }
  }
}
