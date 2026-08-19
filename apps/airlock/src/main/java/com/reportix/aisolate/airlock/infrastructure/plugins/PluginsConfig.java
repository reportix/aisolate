package com.reportix.aisolate.airlock.infrastructure.plugins;

import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import com.reportix.aisolate.airlock.domain.chat.HandlerCatalog;
import com.reportix.aisolate.airlock.domain.chat.HandlerChain;
import com.reportix.aisolate.plugins.CodeFetch;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.plugins.JarDigests;
import com.reportix.aisolate.plugins.JarScan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The developer's handlers, loaded once at start from the airlock's jar, fetched from the backend
 * and pinned by digest as on the tool server ({@link CodeFetch}): the pod's jar never changes, a
 * deploy rolls a new pod. Each airlock runs the handlers of its own jar, the one whose digest its
 * document names. A jar nobody pinned, one that does not load, or a served airlock listing a
 * handler its jar does not carry, fails the start.
 */
@Configuration
@Slf4j
public class PluginsConfig {

  @Bean
  HandlerCatalog handlerCatalog(
      @Value("${aisolate.plugins.dir:}") String pluginsDir,
      @Value("${aisolate.plugins.digests:}") String digests,
      @Value("${aisolate.code.url:}") String codeUrl,
      @Value("${aisolate.code.digest:}") String codeDigest,
      @Value("${aisolate.code.token-file:}") String codeTokenFile,
      AirlockCatalog airlocks)
      throws IOException {
    CodeFetch.Code code =
        CodeFetch.resolve(codeUrl, codeDigest, codeTokenFile, pluginsDir, digests);
    Map<String, Path> jars = JarDigests.pinnedJars(code.dir(), code.digests());
    // One class loader per jar: no airlock sees another's classes.
    Map<String, List<HandlerCandidate>> candidatesByDigest = new HashMap<>();
    for (Map.Entry<String, Path> jar : jars.entrySet()) {
      candidatesByDigest.put(jar.getKey(), JarScan.handlersOf(List.of(jar.getValue())));
    }
    log.info(
        "loaded {} handler(s) from {} jar(s)",
        candidatesByDigest.values().stream().mapToInt(List::size).sum(),
        jars.size());
    // JarDigests.of writes lower case and the pin ignores case: a document's digest may use either.
    HandlerCatalog catalog =
        airlock -> {
          String digest = airlock.handlers().jarDigest();
          return HandlerChain.resolve(
              candidatesByDigest.getOrDefault(
                  digest == null ? null : digest.toLowerCase(Locale.ROOT), List.of()),
              airlock.spaceId(),
              airlock.id(),
              airlock.handlers());
        };
    // What this pod serves, not what discovery lists: a new airlock starts before it is listed.
    airlocks.served().forEach(catalog::resolveHandlers);
    return catalog;
  }
}
