package com.reportix.aisolate.controller.render;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The installation-level part of what the controller renders: what the chart decided at install
 * time and no document carries. Bound from {@code /etc/aisolate/settings.yaml} (the chart's
 * ConfigMap) under {@code aisolate.controller}.
 *
 * @param namespace the one namespace watched and rendered into
 * @param ingress the host and class every airlock route is rendered under
 * @param images the data-plane images, a Java and a Python pair, by digest in production
 * @param identity the identity provider as the pods validate tokens against it
 * @param egress the cluster's DNS and ingress peers, the hosts the admin allows, the egress proxy
 * @param airlockPods replicas and the optional runtime class of every airlock pod
 * @param corsAllowedOrigins the browser origins an airlock pod answers (the chat's host)
 * @param observability where the airlock and tool server pods send their traces
 * @param resources the requests and limits of every airlock pod and every MCP server; none rendered
 *     when absent
 * @param audit where the airlock pods hand their audit lines over; none without the AIsolate UI
 * @param code where an airlock's pods fetch its code; none without the AIsolate UI
 */
@ConfigurationProperties("aisolate.controller")
public record ControllerProperties(
    String namespace,
    Ingress ingress,
    Images images,
    Identity identity,
    Egress egress,
    AirlockPods airlockPods,
    String corsAllowedOrigins,
    Observability observability,
    PodResources resources,
    Audit audit,
    Code code) {

  /**
   * @param url the backend's ingest, in the cluster; blank: no backend (headless), stdout alone
   * @param ingestKey the install's key each airlock's token derives from, from the chart's Secret
   * @param failClosed an airlock pod refuses new questions while its buffer of unsent lines is full
   * @param stdout each line printed to the pod's stdout too
   */
  public record Audit(String url, String ingestKey, boolean failClosed, boolean stdout) {

    public boolean enabled() {
      return url != null && !url.isBlank() && ingestKey != null && !ingestKey.isBlank();
    }
  }

  public boolean auditEnabled() {
    return audit != null && audit.enabled();
  }

  /**
   * @param url the backend's code endpoint in the cluster, {@code http://backend.<ns>.svc:8085/v1/
   *     code}; blank: no backend (headless), and an airlock with code cannot start
   */
  public record Code(String url) {}

  /** Each airlock's code token derives from the audit's ingest key: one install key. */
  public boolean codeEnabled() {
    return code != null
        && code.url() != null
        && !code.url().isBlank()
        && audit != null
        && audit.ingestKey() != null
        && !audit.ingestKey().isBlank();
  }

  /** The OTLP/HTTP base URL the pods send traces to; blank when they send none. */
  public String otlpEndpoint() {
    return observability == null || observability.otlpEndpoint() == null
        ? ""
        : observability.otlpEndpoint().strip();
  }

  public record Ingress(String host, String className, String publicUrl) {}

  /**
   * The airlock and tool server images of each language an airlock's code may be in.
   *
   * @param allowTags the chart's escape hatch for clusters that build their own images; off, a
   *     reference without a digest refuses to bind, so what runs is what was pinned
   */
  public record Images(
      String airlock,
      String toolServer,
      String airlockPython,
      String toolServerPython,
      String pullPolicy,
      boolean allowTags) {

    public Images {
      if (!allowTags) {
        for (String image : List.of(airlock, toolServer, airlockPython, toolServerPython)) {
          if (!image.contains("@sha256:")) {
            throw new IllegalArgumentException(
                "the image "
                    + image
                    + " has no digest: pin it as repository@sha256:... or set allowTags");
          }
        }
      }
    }

    public String airlock(boolean python) {
      return python ? airlockPython : airlock;
    }

    public String toolServer(boolean python) {
      return python ? toolServerPython : toolServer;
    }
  }

  /**
   * @param rolesClaim where the tokens carry the roles the rules name; blank: the pods' default,
   *     {@code realm_access.roles}
   * @param egress a NetworkPolicy peer map (namespaceSelector/podSelector/cidr/port)
   */
  public record Identity(
      String issuerUri, String jwkSetUri, String rolesClaim, Map<String, Object> egress) {
    public Identity {
      Peers.check(egress, "identity.egress", true);
    }
  }

  /**
   * @param hosts each host an airlock's URL may name, to its NetworkPolicy peer; the chart writes
   *     the keys as {@code "[<host>]"} so their dots survive the binding
   */
  public record Egress(
      Map<String, Object> dns,
      Map<String, Object> ingressFrom,
      Map<String, Map<String, Object>> hosts,
      Proxy proxy) {
    public Egress {
      hosts = hosts == null ? Map.of() : Map.copyOf(hosts);
      Peers.check(dns, "egress.dns", true);
      Peers.check(ingressFrom, "egress.ingressFrom", false); // the pods' own port
      hosts.forEach((host, peer) -> Peers.check(peer, "egress.hosts[" + host + "]", true));
    }

    /** A host the egress proxy reaches for the pods: no peer of its own. */
    public boolean proxies(String host) {
      return proxy != null && proxy.enabled() && proxy.domains().contains(host);
    }
  }

  /** {@code domains}: the exact host names the proxy lets through. */
  public record Proxy(boolean enabled, String host, int port, List<String> domains) {
    public Proxy {
      domains = domains == null ? List.of() : List.copyOf(domains);
    }
  }

  public record AirlockPods(int replicas, String runtimeClassName) {}

  /** {@code otlpEndpoint}: its host must be in {@code egress.hosts} or proxied. */
  public record Observability(String otlpEndpoint) {}

  public record PodResources(Resources airlock, Resources toolServer) {}

  /**
   * Kubernetes quantities by resource name: {@code cpu}, {@code memory}, {@code ephemeral-storage}.
   */
  public record Resources(Map<String, String> requests, Map<String, String> limits) {
    public Resources {
      requests = requests == null ? Map.of() : Map.copyOf(requests);
      limits = limits == null ? Map.of() : Map.copyOf(limits);
    }
  }
}
