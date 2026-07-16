package com.reportix.aisolate.controller.crd;

import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.Space;
import io.fabric8.crdv2.generator.CRDGenerator;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Writes the three CRDs from the resource classes: {@code ./gradlew :apps:controller:syncCrds
 * <dir>}.
 */
public final class CrdGeneration {

  public static void main(String[] args) {
    generate(Path.of(args.length > 0 ? args[0] : "build/crds"));
  }

  /** Generates {@code globals.yaml}, {@code spaces.yaml} and {@code airlocks.yaml}. */
  public static void generate(Path dir) {
    File out = dir.toFile();
    out.mkdirs();
    new CRDGenerator()
        .customResourceClasses(Global.class, Space.class, Airlock.class)
        .inOutputDir(out)
        .generate();
    try (Stream<Path> files = Files.list(dir)) {
      files
          .filter(f -> f.toString().endsWith(".yml"))
          .toList()
          .forEach(CrdGeneration::writeAsPlural);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The generator names a file {@code <plural>.<group>-<CRD API version>.yml}; the chart keeps one
   * {@code <plural>.yaml} per kind.
   */
  private static void writeAsPlural(Path generated) {
    String plural = generated.getFileName().toString().split("\\.")[0];
    try {
      Files.move(
          generated,
          generated.resolveSibling(plural + ".yaml"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private CrdGeneration() {}
}
