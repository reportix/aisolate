package com.reportix.aisolate.controller.support;

import com.reportix.aisolate.controller.render.ControllerProperties;
import java.util.List;
import java.util.Map;

/**
 * An installation's settings as the chart writes them, for every controller test: the model on the
 * model host, the HR database and the trace collector mapped to peers, a hosted model behind the
 * egress proxy.
 */
public final class TestSettings {

  public static final String PROXIED_HOST = "models.example.com";

  public static final ControllerProperties SETTINGS =
      new ControllerProperties(
          "aisolate",
          new ControllerProperties.Ingress(
              "aisolate.localtest.me", "nginx", "http://aisolate.localtest.me"),
          new ControllerProperties.Images(
              "aisolate/airlock:dev",
              "aisolate/tool-server:dev",
              "aisolate/airlock-python:dev",
              "aisolate/tool-server-python:dev",
              "Never",
              true),
          new ControllerProperties.Identity(
              "http://keycloak.localtest.me/realms/acme",
              "http://keycloak.aisolate-dev.svc:8080/realms/acme/protocol/openid-connect/certs",
              "org.roles",
              Map.of(
                  "namespaceSelector", Map.of("kubernetes.io/metadata.name", "aisolate-dev"),
                  "podSelector", Map.of("app", "keycloak"),
                  "port", 8080)),
          new ControllerProperties.Egress(
              Map.of(
                  "namespaceSelector", Map.of("kubernetes.io/metadata.name", "kube-system"),
                  "podSelector", Map.of("k8s-app", "kube-dns"),
                  "port", 53),
              Map.of("namespaceSelector", Map.of("kubernetes.io/metadata.name", "ingress-nginx")),
              Map.of(
                  "host.minikube.internal",
                  Map.of("cidr", "192.168.49.1/32", "port", 11434),
                  "acme-db.aisolate.svc",
                  Map.of("podSelector", Map.of("app", "acme-db"), "port", 5432),
                  "tempo.aisolate-monitoring.svc",
                  Map.of(
                      "namespaceSelector",
                      Map.of("kubernetes.io/metadata.name", "aisolate-monitoring"),
                      "podSelector",
                      Map.of("app", "tempo"),
                      "port",
                      4318)),
              new ControllerProperties.Proxy(true, "egress", 3128, List.of(PROXIED_HOST))),
          new ControllerProperties.AirlockPods(1, ""),
          "http://chat.localtest.me",
          new ControllerProperties.Observability("http://tempo.aisolate-monitoring.svc:4318"),
          new ControllerProperties.PodResources(
              new ControllerProperties.Resources(
                  Map.of("cpu", "250m", "memory", "768Mi", "ephemeral-storage", "256Mi"),
                  Map.of("cpu", "2", "memory", "1536Mi", "ephemeral-storage", "1Gi")),
              new ControllerProperties.Resources(
                  Map.of("cpu", "100m", "memory", "512Mi", "ephemeral-storage", "128Mi"),
                  Map.of("cpu", "1", "memory", "1Gi", "ephemeral-storage", "512Mi"))),
          new ControllerProperties.Audit(
              "http://backend.aisolate.svc:8085/v1/audit/events", "test-ingest-key", true, true),
          new ControllerProperties.Code("http://backend.aisolate.svc:8085/v1/code"));

  /** The same installation with the egress proxy on or off. */
  public static ControllerProperties withProxy(ControllerProperties s, boolean enabled) {
    ControllerProperties.Proxy proxy = s.egress().proxy();
    return new ControllerProperties(
        s.namespace(),
        s.ingress(),
        s.images(),
        s.identity(),
        new ControllerProperties.Egress(
            s.egress().dns(),
            s.egress().ingressFrom(),
            s.egress().hosts(),
            new ControllerProperties.Proxy(enabled, proxy.host(), proxy.port(), proxy.domains())),
        s.airlockPods(),
        s.corsAllowedOrigins(),
        s.observability(),
        s.resources(),
        s.audit(),
        s.code());
  }

  /** The same installation sending its traces to {@code endpoint}; blank for nowhere. */
  public static ControllerProperties withOtlpEndpoint(ControllerProperties s, String endpoint) {
    return new ControllerProperties(
        s.namespace(),
        s.ingress(),
        s.images(),
        s.identity(),
        s.egress(),
        s.airlockPods(),
        s.corsAllowedOrigins(),
        new ControllerProperties.Observability(endpoint),
        s.resources(),
        s.audit(),
        s.code());
  }

  /** The same installation with every pod the controller renders under {@code runtimeClass}. */
  public static ControllerProperties withRuntimeClass(ControllerProperties s, String runtimeClass) {
    return new ControllerProperties(
        s.namespace(),
        s.ingress(),
        s.images(),
        s.identity(),
        s.egress(),
        new ControllerProperties.AirlockPods(s.airlockPods().replicas(), runtimeClass),
        s.corsAllowedOrigins(),
        s.observability(),
        s.resources(),
        s.audit(),
        s.code());
  }

  /** The same installation without the AIsolate UI's backend: no audit ingest, no code to fetch. */
  public static ControllerProperties withoutAuditIngest(ControllerProperties s) {
    return new ControllerProperties(
        s.namespace(),
        s.ingress(),
        s.images(),
        s.identity(),
        s.egress(),
        s.airlockPods(),
        s.corsAllowedOrigins(),
        s.observability(),
        s.resources(),
        new ControllerProperties.Audit("", "", true, true),
        new ControllerProperties.Code(""));
  }

  private TestSettings() {}
}
