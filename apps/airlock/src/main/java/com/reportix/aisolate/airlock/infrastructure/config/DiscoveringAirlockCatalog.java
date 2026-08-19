package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chat answers from the pod's own documents, none in a discovery pod; discovery lists every space's
 * airlocks, read from the discovery mount ({@code <space>.json} each) again whenever the kubelet
 * swaps it (its {@code ..data} link moves). So an airlock deployed after this pod started is listed
 * without restarting it.
 */
@Slf4j
@RequiredArgsConstructor
final class DiscoveringAirlockCatalog implements AirlockCatalog {

  private final AirlockCatalog served;
  private final Path discoveryDir;

  private String loadedFrom;
  private List<DiscoveredAirlock> discovered;

  @Override
  public Optional<AirlockDefinition> findAirlock(String id) {
    return served.findAirlock(id);
  }

  @Override
  public List<AirlockDefinition> served() {
    return served.served();
  }

  @Override
  public String revision() {
    return served.revision();
  }

  @Override
  public synchronized List<DiscoveredAirlock> listAirlocks() {
    String current = currentData();
    if (!current.equals(loadedFrom)) {
      try {
        discovered = readMount();
        loadedFrom = current;
      } catch (RuntimeException unreadable) {
        // Not written yet, or mid-swap: the last good list, else this pod's own.
        log.warn("discovery documents unreadable: {}", unreadable.getMessage());
      }
    }
    return discovered != null ? discovered : served.listAirlocks();
  }

  /** Every space's file; none yet is the controller's first pass still to come. */
  private List<DiscoveredAirlock> readMount() {
    List<DiscoveredAirlock> all = new ArrayList<>();
    try (Stream<Path> files = Files.list(discoveryDir)) {
      for (Path file : files.sorted().toList()) {
        if (file.getFileName().toString().endsWith(".json")) {
          all.addAll(DiscoveredAirlock.fromJson(Files.readString(file)));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (all.isEmpty()) {
      throw new IllegalStateException("no space listed yet");
    }
    return List.copyOf(all);
  }

  /** The kubelet's swap target; a plain directory reads once. */
  private String currentData() {
    Path data = discoveryDir.resolve("..data");
    try {
      return Files.isSymbolicLink(data) ? Files.readSymbolicLink(data).toString() : "static";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
