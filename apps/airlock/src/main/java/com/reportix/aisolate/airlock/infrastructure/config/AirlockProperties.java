package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.config.DataSourceLogin;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The pod's own settings ({@code aisolate.*}); what it says to callers is {@link
 * com.reportix.aisolate.identity.IdentityProperties}.
 *
 * @param exchange limits that keep one chat exchange bounded
 * @param config where the documents this pod serves are mounted (empty with a discovery mount: a
 *     discovery pod, which serves none), the revision the controller computed for them (empty: the
 *     hash of the files as read), and where every space's airlocks are mounted for discovery
 *     (empty: discovery lists this pod's own)
 * @param airlock the one airlock this process serves; every other id is 404
 * @param toolServer the MCP server the controller rendered for this airlock
 * @param datasource the airlock's data sources as the controller renders them; a handler reaches
 *     one by id
 * @param audit where the audit lines go besides stdout
 */
@ConfigurationProperties("aisolate")
public record AirlockProperties(
    @DefaultValue Exchange exchange,
    @DefaultValue Config config,
    String airlock,
    @DefaultValue ToolServer toolServer,
    @DefaultValue List<DataSourceLogin> datasource,
    @DefaultValue Audit audit) {

  /**
   * @param url the backend's ingest; blank: none (a headless install), the lines go to stdout alone
   * @param tokenFile the airlock's ingest token, mounted from its Secret
   * @param bufferDir unsent lines, on the pod's emptyDir
   * @param bufferMax the buffer's bound; full, the pod refuses new questions when {@code
   *     failClosed}, else drops lines
   * @param stdout each line printed to stdout too, for a SIEM
   */
  public record Audit(
      String url,
      String tokenFile,
      @DefaultValue("/var/lib/aisolate/audit") String bufferDir,
      @DefaultValue("64MB") DataSize bufferMax,
      @DefaultValue("true") boolean failClosed,
      @DefaultValue("true") boolean stdout) {}

  public record Config(String dir, String revision, String discoveryDir) {

    /** A discovery pod's: the discovery mount and no documents, so no airlock to serve. */
    public boolean discoveryOnly() {
      return discoveryDir != null && !discoveryDir.isBlank() && (dir == null || dir.isBlank());
    }
  }

  public record ToolServer(@DefaultValue("http://localhost:8081/mcp") String url) {}

  /**
   * What one exchange may cost. {@code maxDuration} is a backstop checked between steps: the
   * per-call HTTP timeouts are what actually bound an exchange, and they are sized to fit inside
   * it.
   */
  public record Exchange(@DefaultValue("8m") Duration maxDuration) {}
}
