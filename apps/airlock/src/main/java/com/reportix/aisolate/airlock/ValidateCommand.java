package com.reportix.aisolate.airlock;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationException;
import com.reportix.aisolate.config.ConfigurationLoader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * {@code validate <dir>} as the first argument: load the documents exactly as the airlock pod
 * would, print every problem, exit. No Spring, no database, no identity provider, so CI checks a
 * release before anything is deployed.
 */
public final class ValidateCommand {

  /** 0 with the revision and the release's names on {@code out}; 1 with every problem. */
  public static int run(Path dir, PrintWriter out) {
    try {
      Configuration configuration = ConfigurationLoader.load(dir);
      out.println(
          "ok: revision "
              + configuration.revision()
              + ", spaces "
              + configuration.spaces().keySet()
              + ", airlocks "
              + configuration.airlocks().keySet());
      return 0;
    } catch (ConfigurationException invalid) {
      invalid.problems().forEach(out::println);
      return 1;
    } catch (UncheckedIOException unreadable) {
      out.println(dir + ": " + unreadable.getMessage());
      return 1;
    }
  }

  private ValidateCommand() {}
}
