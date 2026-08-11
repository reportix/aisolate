package com.reportix.aisolate.toolserver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.toolserver.domain.PluginCatalog;
import com.reportix.aisolate.toolserver.support.HrAcmeTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginsConfigTest {

  @Test
  void givenNoPluginAnywhere_whenChecked_thenRefusedToStart() {
    // Arrange: a configured directory without a jar: nothing to govern
    PluginCatalog empty = new PluginCatalog(List.of(), List.of());

    // Act
    Throwable thrown = catchThrowable(() -> PluginsConfig.checkPlugins(empty, "/plugins"));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("no tool plugin");
  }

  @Test
  void givenPluginReadingADataSourceThePodLacks_whenChecked_thenRefusedNamingBoth() {
    // Arrange: the plugin reads acme_hr; this airlock defines hr-documents only
    PluginCatalog catalog = new PluginCatalog(List.of(new HrAcmeTools()), List.of());

    // Act
    Throwable thrown =
        catchThrowable(() -> PluginsConfig.checkDataSources(catalog, Set.of("hr-documents")));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "plugin hr-acme-tools reads data source acme_hr, which this airlock does not"
                + " define");
  }
}
