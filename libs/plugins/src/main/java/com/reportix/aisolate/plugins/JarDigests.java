package com.reportix.aisolate.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How every pod pins the developer's jars: each jar in its plugins directory must carry one of the
 * digests the controller rendered ({@code AISOLATE_PLUGIN_DIGESTS}), the one its Airlock document
 * carries in {@code spec.jar.digest}. A pod fetches its jar ({@link CodeFetch}) and loads it from
 * here, so this is the pin: a jar nobody pinned, an empty list included, refuses the start.
 */
public final class JarDigests {

  /** {@code sha256:<hex>} of a jar's bytes, the form AIsolate and the chart name jars by. */
  public static String of(byte[] bytes) {
    MessageDigest sha = sha256();
    sha.update(bytes);
    return format(sha);
  }

  /** {@link #of(byte[])} of a file, read as a stream: an artifact may be gigabytes. */
  public static String of(Path file) throws IOException {
    MessageDigest sha = sha256();
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return format(sha);
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String format(MessageDigest sha) {
    return "sha256:" + HexFormat.of().formatHex(sha.digest());
  }

  /** {@code AISOLATE_PLUGIN_DIGESTS}: comma-separated, blanks and case ignored. */
  private static Set<String> parse(String digests) {
    return Arrays.stream(digests.split(","))
        .map(String::strip)
        .filter(digest -> !digest.isEmpty())
        .map(digest -> digest.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  /**
   * The jars of {@code dir} by digest, in name order, each one of {@code digests}; none without a
   * directory.
   *
   * @param dir {@code AISOLATE_PLUGINS_DIR}, blank for none
   * @param digests {@code AISOLATE_PLUGIN_DIGESTS}
   * @throws IllegalStateException a jar nobody pinned: the pod does not start
   */
  public static Map<String, Path> pinnedJars(String dir, String digests) {
    if (dir == null || dir.isBlank()) {
      return Map.of();
    }
    // Unset pins nothing: the jar is then refused by name, not with a bare NPE.
    Set<String> pinned = parse(digests == null ? "" : digests);
    try (Stream<Path> files = Files.list(Path.of(dir))) {
      Map<String, Path> jars = new LinkedHashMap<>();
      for (Path jar : files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
        String digest = of(jar);
        if (!pinned.contains(digest)) {
          throw new IllegalStateException(
              "plugin jar "
                  + jar.getFileName()
                  + " has digest "
                  + digest
                  + " which is not in AISOLATE_PLUGIN_DIGESTS");
        }
        jars.put(digest, jar);
      }
      return jars;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private JarDigests() {}
}
