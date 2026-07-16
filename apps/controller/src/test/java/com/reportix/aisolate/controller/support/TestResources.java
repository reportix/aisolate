package com.reportix.aisolate.controller.support;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
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

  /** The Global, then the airlocks, then the space: the backend's apply order. */
  public static void applyFixtures(KubernetesClient client) {
    client.resource(global()).create();
    airlocks().forEach(a -> client.resource(a).create());
    spaces().forEach(s -> client.resource(s).create());
  }

  /** {@code airlock} redeployed with a digest no pod accepts: the release leaves it out. */
  public static void breakJarDigest(KubernetesClient client, String airlock) {
    updateAirlock(
        client,
        airlock,
        s -> withJar(s, new AirlockDocument.Jar("not-a-digest", s.jar().tools(), null)));
  }

  /** The Airlock {@code name} as the cluster holds it, its spec changed. */
  public static void updateAirlock(
      KubernetesClient client, String name, UnaryOperator<AirlockDocument.Spec> change) {
    Airlock a = client.resources(Airlock.class).inNamespace(NS).withName(name).get();
    a.setSpec(change.apply(a.getSpec()));
    client.resource(a).update();
  }

  public static AirlockDocument.Spec withJar(AirlockDocument.Spec s, AirlockDocument.Jar jar) {
    return new AirlockDocument.Spec(
        s.space(),
        s.displayName(),
        s.description(),
        s.access(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        s.tools(),
        s.postLLM(),
        s.llms(),
        s.dataSources(),
        jar);
  }

  public static AirlockDocument.Spec withTools(AirlockDocument.Spec s, List<String> tools) {
    return new AirlockDocument.Spec(
        s.space(),
        s.displayName(),
        s.description(),
        s.access(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        tools,
        s.postLLM(),
        s.llms(),
        s.dataSources(),
        s.jar());
  }

  /** The model and the prompt as the AIsolate UI saves an airlock before its first deploy. */
  public static AirlockDocument.Spec withLlmAndPrompt(
      AirlockDocument.Spec s, String llm, String prompt) {
    return new AirlockDocument.Spec(
        s.space(),
        s.displayName(),
        s.description(),
        s.access(),
        s.preLLM(),
        llm,
        prompt,
        s.tools(),
        s.postLLM(),
        s.llms(),
        s.dataSources(),
        s.jar());
  }

  public static AirlockDocument.Spec withLlms(AirlockDocument.Spec s, List<Llm> llms) {
    return new AirlockDocument.Spec(
        s.space(),
        s.displayName(),
        s.description(),
        s.access(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        s.tools(),
        s.postLLM(),
        llms,
        s.dataSources(),
        s.jar());
  }

  public static AirlockDocument.Spec withDataSources(
      AirlockDocument.Spec s, List<DataSource> dataSources) {
    return new AirlockDocument.Spec(
        s.space(),
        s.displayName(),
        s.description(),
        s.access(),
        s.preLLM(),
        s.llm(),
        s.prompt(),
        s.tools(),
        s.postLLM(),
        s.llms(),
        dataSources,
        s.jar());
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

  private static ObjectMeta meta(String name) {
    return new ObjectMetaBuilder().withName(name).withNamespace(NS).build();
  }

  private TestResources() {}
}
