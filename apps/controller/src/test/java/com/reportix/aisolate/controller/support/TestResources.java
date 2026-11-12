package com.reportix.aisolate.controller.support;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.Space;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/** The fixture documents as the custom resources the backend applies. */
public final class TestResources {

  public static final String NS = "aisolate";

  public static final Configuration FIXTURES =
      ConfigurationLoader.load(Path.of("src/test/resources/documents"));

  public static Global global() {
    return global(FIXTURES.global().spec());
  }

  public static List<Space> spaces() {
    return FIXTURES.spaces().values().stream().map(d -> space(d.name(), d.spec())).toList();
  }

  public static List<Airlock> airlocks() {
    return FIXTURES.airlocks().values().stream().map(d -> airlock(d.name(), d.spec())).toList();
  }

  public static List<AirlockVersion> versions() {
    return FIXTURES.airlockVersions().values().stream()
        .map(d -> version(d.name(), d.spec()))
        .toList();
  }

  /** The Global, versions, then airlocks, then the space: the backend's apply order. */
  public static void applyFixtures(KubernetesClient client) {
    client.resource(global()).create();
    versions().forEach(v -> client.resource(v).create());
    airlocks().forEach(a -> client.resource(a).create());
    spaces().forEach(s -> client.resource(s).create());
  }

  /** {@code version} redeployed with a digest no pod accepts: the release leaves it out. */
  public static void breakJarDigest(KubernetesClient client, String version) {
    updateVersion(
        client,
        version,
        s -> withJar(s, new AirlockVersionDocument.Jar("not-a-digest", s.jar().tools(), null)));
  }

  /** The AirlockVersion {@code name} as the cluster holds it, its spec changed. */
  public static void updateVersion(
      KubernetesClient client, String name, UnaryOperator<AirlockVersionDocument.Spec> change) {
    AirlockVersion v = client.resources(AirlockVersion.class).inNamespace(NS).withName(name).get();
    v.setSpec(change.apply(v.getSpec()));
    client.resource(v).update();
  }

  /** The Airlock {@code name} as the cluster holds it, its spec changed. */
  public static void updateAirlock(
      KubernetesClient client, String name, UnaryOperator<AirlockDocument.Spec> change) {
    Airlock a = client.resources(Airlock.class).inNamespace(NS).withName(name).get();
    a.setSpec(change.apply(a.getSpec()));
    client.resource(a).update();
  }

  public static AirlockVersionDocument.Spec withJar(
      AirlockVersionDocument.Spec s, AirlockVersionDocument.Jar jar) {
    return new AirlockVersionDocument.Spec(
        s.airlock(),
        s.version(),
        s.description(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        s.tools(),
        s.postLLM(),
        s.llms(),
        jar);
  }

  public static AirlockVersionDocument.Spec withTools(
      AirlockVersionDocument.Spec s, List<String> tools) {
    return new AirlockVersionDocument.Spec(
        s.airlock(),
        s.version(),
        s.description(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        tools,
        s.postLLM(),
        s.llms(),
        s.jar());
  }

  public static AirlockVersionDocument.Spec withVersionLlms(
      AirlockVersionDocument.Spec s, List<Llm> llms) {
    return new AirlockVersionDocument.Spec(
        s.airlock(),
        s.version(),
        s.description(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        s.tools(),
        s.postLLM(),
        llms,
        s.jar());
  }

  public static AirlockDocument.Spec withLlms(AirlockDocument.Spec s, List<Llm> llms) {
    return new AirlockDocument.Spec(
        s.space(), s.displayName(), s.description(), s.access(), llms, s.dataSources());
  }

  public static AirlockDocument.Spec withDataSources(
      AirlockDocument.Spec s, List<DataSource> dataSources) {
    return new AirlockDocument.Spec(
        s.space(), s.displayName(), s.description(), s.access(), s.llms(), dataSources);
  }

  public static Global global(GlobalDocument.Spec spec) {
    Global g = new Global();
    g.setMetadata(meta(GlobalDocument.NAME));
    g.setSpec(spec);
    return g;
  }

  public static Space space(String name, SpaceDocument.Spec spec) {
    Space s = new Space();
    s.setMetadata(meta(name));
    s.setSpec(spec);
    return s;
  }

  public static Airlock airlock(String name, AirlockDocument.Spec spec) {
    Airlock a = new Airlock();
    a.setMetadata(meta(name));
    a.setSpec(spec);
    return a;
  }

  public static AirlockVersion version(String name, AirlockVersionDocument.Spec spec) {
    AirlockVersion v = new AirlockVersion();
    v.setMetadata(meta(name));
    v.setSpec(spec);
    return v;
  }

  private static ObjectMeta meta(String name) {
    return new ObjectMetaBuilder().withName(name).withNamespace(NS).build();
  }

  private TestResources() {}
}
