package com.reportix.aisolate.airlock.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.domain.airlock.DocumentsAirlockCatalog;
import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveringAirlockCatalogTest {

  private static final Path OTHERS = Path.of("src/test/resources/documents-security");

  @TempDir Path mount;

  @Test
  void givenKubeletSwapsTheMount_whenListed_thenTheNewDocumentsWithoutARestart()
      throws IOException {
    // Arrange: the kubelet's layout, every file a link through ..data to a timestamped directory
    swapTo(Documents.DIR, "..2026_10_01_1");
    DocumentsAirlockCatalog served =
        new DocumentsAirlockCatalog(ConfigurationLoader.load(Documents.DIR), "hr-assistant");
    DiscoveringAirlockCatalog catalog = new DiscoveringAirlockCatalog(served, mount);
    List<String> before = ids(catalog.listAirlocks());

    // Act
    swapTo(OTHERS, "..2026_10_01_2");
    List<String> after = ids(catalog.listAirlocks());

    // Assert: discovery follows the mount; chat still answers from the pod's own documents
    assertThat(before).containsExactly("hr-assistant", "founders-finance");
    assertThat(after).containsExactly("acme-hr", "strict-app", "short-app", "closed-app");
    assertThat(catalog.findAirlock("hr-assistant")).isPresent();
  }

  /** The controller's projection of {@code documents}, as one space's file in the mount. */
  private void swapTo(Path documents, String timestamped) throws IOException {
    Path target = Files.createDirectory(mount.resolve(timestamped));
    Path file = Path.of("space.json");
    Files.writeString(
        target.resolve(file),
        DiscoveredAirlock.toJson(DiscoveredAirlock.of(ConfigurationLoader.load(documents))));
    Path link = mount.resolve(file);
    if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      Files.createSymbolicLink(link, Path.of("..data").resolve(file));
    }
    Path data = mount.resolve("..data");
    Files.deleteIfExists(data);
    Files.createSymbolicLink(data, Path.of(timestamped));
  }

  private static List<String> ids(List<DiscoveredAirlock> airlocks) {
    return airlocks.stream().map(DiscoveredAirlock::id).toList();
  }
}
