package com.reportix.aisolate.toolserver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import com.reportix.aisolate.toolserver.support.PayrollTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginCatalogTest {

  private static Policy policy(String airlock, String... tools) {
    return new Policy(
        airlock,
        "1",
        new AirlockDocument.Access(List.of("eric@example.com"), List.of("*")),
        List.of(tools));
  }

  /** A plugin publishing one tool of its own. */
  private static ToolPlugin plugin(String name, List<Entity> entities, String tool) {
    return new ToolPlugin() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<Entity> entities() {
        return entities;
      }

      @Override
      public String dataSource() {
        return "acme_hr";
      }

      @Override
      public List<Tool> tools() {
        return List.of(new Tool(tool, "", Map.of(), (q, a) -> Map.of()));
      }
    };
  }

  @Test
  void givenToolPublishedTwice_whenConstructed_thenRefusedAsTheDeployRefusesIt() {
    // Arrange
    List<ToolPlugin> plugins = List.of(new CompensationTools(), new CompensationTools());

    // Act
    Throwable thrown = catchThrowable(() -> new PluginCatalog(plugins, List.of()));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("tool read_records is published twice");
  }

  @Test
  void givenServedAirlocks_whenListPublishedTools_thenTheirToolsAPluginPublishesSortedByName() {
    // Arrange: hr-archive lists a name no plugin publishes; no airlock lists lookup
    PluginCatalog catalog =
        new PluginCatalog(
            List.of(
                new CompensationTools(),
                new PayrollTools(),
                plugin("lookup-tools", List.of(), "lookup")),
            List.of());
    List<Policy> served =
        List.of(
            policy("hr-assistant", "read_records", "export_payslips"),
            policy("hr-archive", "read_records", "nowhere"));

    // Act
    List<Tool> published = catalog.listPublishedTools(served);

    // Assert
    assertThat(published)
        .extracting(tool -> tool.name() + "=" + tool.description())
        .containsExactly(
            "export_payslips=Export the payslips of hr.payroll.", "read_records=compensation");
  }

  @Test
  void givenTool_whenFindTool_thenItsPlugin() {
    // Arrange
    PluginCatalog catalog =
        new PluginCatalog(List.of(new CompensationTools(), new PayrollTools()), List.of());

    // Act
    var readRecords = catalog.findTool("read_records");
    var exportPayslips = catalog.findTool("export_payslips");
    var nowhere = catalog.findTool("nowhere");

    // Assert
    assertThat(readRecords).map(ToolPlugin::name).contains("compensation-tools");
    assertThat(exportPayslips).map(ToolPlugin::name).contains("payroll-tools");
    assertThat(nowhere).isEmpty();
  }
}
