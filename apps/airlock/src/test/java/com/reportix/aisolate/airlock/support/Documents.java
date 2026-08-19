package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The fixture release: hr-acme with hr-assistant and founders-finance. */
public final class Documents {

  public static final Path DIR = Path.of("src/test/resources/documents");

  /** The fixture documents plus {@code more}, each another file of the same release. */
  public static Configuration parseWith(String... more) {
    Map<String, String> files = new LinkedHashMap<>();
    try {
      for (Path file : Files.list(DIR).sorted().toList()) {
        files.put(file.getFileName().toString(), Files.readString(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (int i = 0; i < more.length; i++) {
      files.put("more-" + i + ".yaml", more[i]);
    }
    return ConfigurationLoader.parse(files);
  }

  private Documents() {}
}
