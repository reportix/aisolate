package com.reportix.aisolate.plugins.testing;

import com.reportix.aisolate.plugins.testing.PluginJars.Plugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler classes as a developer's jar ships them: compiled at test time into packages on no
 * classpath, so a loader can only find them in the jar.
 */
public final class HandlerJars {

  /** A context preparator: one handbook passage for every question. */
  public static final Plugin HANDBOOK =
      new Plugin(
          "com.example.hr.HandbookPreparator",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class HandbookPreparator implements ContextPreparator {
            @Override
            public int order() {
              return 2;
            }

            public void prepare(Exchange exchange, AIsolateManager aisolate) {
              exchange.addEvidence(
                  "hr-documents",
                  List.of(
                      new Passage(
                          "Employee Handbook",
                          "Parental leave",
                          "Parents may take up to three years of parental leave per child.")));
            }
          }
          """);

  /** A verifier that fails an answer without evidence. */
  public static final Plugin STRICT_VERIFIER =
      new Plugin(
          "com.example.hr.StrictVerifier",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class StrictVerifier implements AnswerVerifier {
            @Override
            public int order() {
              return 1;
            }

            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
              return evidence.isEmpty() ? Verdict.fail("no evidence") : Verdict.pass("cited");
            }
          }
          """);

  /** Remembers the last question per user in the JVM; recalls it as context. */
  public static final Plugin MEMORY =
      new Plugin(
          "com.example.hr.SessionMemory",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.Map;
          import java.util.concurrent.ConcurrentHashMap;

          public class SessionMemory implements ConversationMemory {
            @Override
            public int order() {
              return 1;
            }

            private final Map<String, String> last = new ConcurrentHashMap<>();

            public void recall(Exchange exchange, AIsolateManager aisolate) {
              String previous = last.get(aisolate.principal().sub());
              if (previous != null) {
                exchange.addContext("The user's previous question was: " + previous);
              }
            }

            public void remember(Exchange exchange, Answer answer, AIsolateManager aisolate) {
              last.put(aisolate.principal().sub(), exchange.question());
            }
          }
          """);

  /** Compiles against a class the jar will not carry: it cannot link at runtime. */
  public static final Plugin UNLINKABLE =
      new Plugin(
          "com.example.broken.NeedsHelper",
          """
          package com.example.broken;

          import com.reportix.aisolate.sdk.*;

          public class NeedsHelper extends com.example.broken.Helper {}
          """);

  /** The class NeedsHelper extends; compiled with it, left out of the jar on purpose. */
  public static final Plugin HELPER =
      new Plugin(
          "com.example.broken.Helper",
          """
          package com.example.broken;

          public class Helper {}
          """);

  /** One jar with the classes of every plugin. */
  public static byte[] jarOf(Plugin... plugins) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (Plugin plugin : plugins) {
      entries.putAll(PluginJars.compile(plugin));
    }
    return PluginJars.buildJar(entries);
  }

  /** The jar minus every class file whose name contains {@code classNamePart}. */
  private static byte[] without(byte[] jar, String classNamePart) {
    Map<String, byte[]> entries = new LinkedHashMap<>(PluginJars.entriesOf(jar));
    entries.keySet().removeIf(name -> name.contains(classNamePart));
    return PluginJars.buildJar(entries);
  }

  /** NeedsHelper's classes without Helper: a class that cannot link. */
  public static byte[] unlinkableJar() {
    // The slash keeps NeedsHelper.class: it also ends in "Helper.class".
    return without(PluginJars.buildJar(PluginJars.compile(HELPER, UNLINKABLE)), "/Helper.class");
  }

  /** A rule-set file as a project ships it at {@code aisolate/rules/<entity>.json}. */
  public static byte[] rulesFile(String entity) {
    return ("{\"entity\":\""
            + entity
            + "\",\"classification\":\"restricted\",\"rules\":["
            + "{\"name\":\"all\",\"principals\":{\"role\":\"employee\"},"
            + "\"actions\":[\"read\"],\"effect\":\"allow\",\"row_filter\":\"true\"}]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  public static Path writeJar(byte[] jar) {
    try {
      Path file = Files.createTempFile("handlers-", ".jar");
      Files.write(file, jar);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private HandlerJars() {}
}
