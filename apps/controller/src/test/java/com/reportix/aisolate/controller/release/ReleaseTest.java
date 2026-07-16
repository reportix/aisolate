package com.reportix.aisolate.controller.release;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.airlock;
import static com.reportix.aisolate.controller.support.TestResources.airlocks;
import static com.reportix.aisolate.controller.support.TestResources.space;
import static com.reportix.aisolate.controller.support.TestResources.spaces;
import static com.reportix.aisolate.controller.support.TestResources.withDataSources;
import static com.reportix.aisolate.controller.support.TestResources.withJar;
import static com.reportix.aisolate.controller.support.TestResources.withLlms;
import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.AirlockDocument.Jar;
import com.reportix.aisolate.config.AirlockDocument.Spec;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.render.ControllerProperties;
import com.reportix.aisolate.controller.support.TestSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What the release refuses beyond the loader: what the cluster needs to run an airlock. */
class ReleaseTest {

  private static final ControllerProperties.Egress EGRESS = TestSettings.SETTINGS.egress();
  private static final String OTLP = TestSettings.SETTINGS.otlpEndpoint();
  private static final String HR = "hr-assistant";
  private static final String FF = "founders-finance";
  private static final GlobalDocument GLOBAL = FIXTURES.global();

  static Stream<Arguments> incompleteDataSources() {
    Spec hr = FIXTURES.airlocks().get(HR).spec();
    DataSource ds = hr.dataSources().getFirst();
    return Stream.of(
        Arguments.of(
            withDataSources(hr, List.of(new DataSource(ds.id(), null, ds.secretRef()))),
            "dataSources[hr-documents].url is required"),
        Arguments.of(
            withDataSources(hr, List.of(new DataSource(" ", ds.url(), ds.secretRef()))),
            "spec.dataSources[0].id is required"));
  }

  @ParameterizedTest
  @MethodSource("incompleteDataSources")
  void givenIncompleteDataSource_whenLoaded_thenInvalidNamingTheField(Spec spec, String field) {
    // Arrange
    List<Airlock> all = replace(HR, s -> spec);

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), all, EGRESS, OTLP);

    // Assert: said on the airlock, not discovered from a crash-looping pod; its sibling serves
    assertThat(release.serves("Airlock", HR)).isFalse();
    assertThat(release.serves("Airlock", FF)).isTrue();
    assertThat(release.problemsOf("Airlock", HR)).anySatisfy(p -> assertThat(p).contains(field));
  }

  @Test
  void givenSpaceDataSourceWithoutSecret_whenLoaded_thenEachAirlockResolvingItNamesIt() {
    // Arrange: founders-finance replaces acme_hr with its own; hr-assistant reads the space's
    List<Space> spaces =
        replaceSpace(
            s -> {
              DataSource ds = s.dataSources().getFirst();
              return withSpaceDataSources(s, List.of(new DataSource(ds.id(), ds.url(), null)));
            });

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces, airlocks(), EGRESS, OTLP);

    // Assert: each airlock is told about the data sources it resolves, wherever they are defined;
    // one that resolves none of the broken ones still serves
    assertThat(release.problemsOf("Airlock", HR))
        .containsExactly("Airlock hr-assistant: dataSources[acme_hr].secretRef is required");
    assertThat(release.problemsOf("Airlock", FF)).isEmpty();
    assertThat(release.serves("Airlock", FF)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"granite, true", "'', false"})
  void givenAirlockWithoutCode_whenLoaded_thenValidWithOrWithoutAModel(String llm, boolean runs) {
    // Arrange: the airlock as the AIsolate UI saves it, before its first deploy
    List<Airlock> all =
        replace(
            HR,
            s ->
                new Spec(
                    s.space(),
                    s.displayName(),
                    s.description(),
                    s.access(),
                    null,
                    llm,
                    runs ? s.prompt() : null,
                    List.of(),
                    null,
                    s.llms(),
                    s.dataSources(),
                    null));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), all, EGRESS, OTLP);

    // Assert: valid either way; the reconciler decides what runs
    assertThat(release.valid()).isTrue();
    assertThat(release.serves("Airlock", HR)).isTrue();
    assertThat(release.problemsOf("Airlock", HR)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "llm, http://ollama.elsewhere.example:11434, llms[granite].url",
    "dataSources, jdbc:postgresql://db.elsewhere.example:5432/hr?ssl=true,"
        + " dataSources[hr-documents].url"
  })
  void givenUrlHostTheAdminDoesNotAllow_whenLoaded_thenInvalidNamingFieldAndHostOnly(
      String connection, String url, String field) {
    // Arrange
    List<Airlock> all = replace(HR, s -> withUrl(s, connection, url));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), all, EGRESS, OTLP);

    // Assert: the field and the host, never the URL
    assertThat(release.problemsOf("Airlock", HR))
        .singleElement()
        .satisfies(
            p ->
                assertThat(p)
                    .contains(field)
                    .contains("elsewhere.example")
                    .contains("platform.egress.hosts")
                    .doesNotContain(url));
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, true"})
  void givenGlobalLlmOnAnUnmappedHost_whenLoaded_thenLeftOutAndOnlyAnAirlockRunningItInvalid(
      boolean airlockDefinesIt, boolean serves) {
    // Arrange: the global granite, hr-assistant's main LLM, on a host the admin did not map
    GlobalDocument global =
        withGlobalLlms(
            List.of(
                new Llm("granite", "ollama", "http://ollama.elsewhere.example:11434", "granite"),
                GLOBAL.spec().llms().getLast()));
    List<Airlock> airlocks =
        airlockDefinesIt
            ? replace(
                HR,
                a ->
                    withLlms(
                        a,
                        List.of(
                            new Llm(
                                "granite", "ollama", "http://host.minikube.internal:11434", "g"))))
            : airlocks();

    // Act
    Release release = Release.of("hr-acme", global, spaces(), airlocks, EGRESS, OTLP);
    Release.ServedGlobal served = Release.ServedGlobal.of(global, EGRESS);

    // Assert: the Global is told; no catalog holds the entry; the other airlock serves
    assertThat(served.problems())
        .singleElement()
        .satisfies(
            p ->
                assertThat(p)
                    .startsWith("llms[granite].url: the host ollama.elsewhere.example")
                    .doesNotContain("11434"));
    assertThat(release.configuration().llmsOf(FF)).extracting(Llm::id).doesNotContain("granite");
    assertThat(release.serves("Airlock", HR)).isEqualTo(serves);
    assertThat(release.problemsOf("Airlock", HR))
        .isEqualTo(
            serves
                ? List.of()
                : List.of(
                    "Airlock hr-assistant: llm granite: host ollama.elsewhere.example"
                        + " is not in platform.egress.hosts"));
  }

  @ParameterizedTest
  @CsvSource({
    "jdbc:postgresql://db.elsewhere.example:5432/audit, aisolate-ds-audit-log-j1k2l3,"
        + " 'dataSources[audit_log].url: the host db.elsewhere.example is not allowed'",
    "jdbc:postgresql://acme-db.aisolate.svc:5432/audit, '',"
        + " dataSources[audit_log].secretRef is required"
  })
  void givenGlobalDataSourceUnreachableOrWithoutSecret_whenLoaded_thenLeftOutAndEveryAirlockServes(
      String url, String secretRef, String problem) {
    // Arrange
    GlobalDocument global =
        new GlobalDocument(
            GLOBAL.metadata(),
            new GlobalDocument.Spec(
                GLOBAL.spec().llms(), List.of(new DataSource("audit_log", url, secretRef))));

    // Act
    Release release = Release.of("hr-acme", global, spaces(), airlocks(), EGRESS, OTLP);

    // Assert
    assertThat(Release.ServedGlobal.of(global, EGRESS).problems())
        .singleElement()
        .satisfies(p -> assertThat(p).startsWith(problem));
    assertThat(release.serves("Airlock", HR)).isTrue();
    assertThat(release.serves("Airlock", FF)).isTrue();
    assertThat(release.configuration().dataSourcesOf(HR))
        .extracting(DataSource::id)
        .doesNotContain("audit_log");
  }

  @Test
  void givenOtlpHostNotInEgressMap_whenChecked_thenInvalid() {
    // Arrange: a collector the admin did not map
    String endpoint = "http://otel-collector.observability.svc:4318";

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks(), EGRESS, endpoint);

    // Assert: every airlock's pods would send there, so every one is told
    assertThat(release.valid()).isFalse();
    assertThat(release.problemsOf("Airlock", HR))
        .containsExactly(
            "platform.observability.otlpEndpoint host otel-collector.observability.svc is not in"
                + " platform.egress.hosts");
  }

  @ParameterizedTest
  @CsvSource({"true, true", "false, false"})
  void givenProxiedHost_whenLoaded_thenValidOnlyWhileTheProxyRuns(boolean enabled, boolean valid) {
    // Arrange: the space's qwen, which founders-finance runs, is behind the egress proxy
    ControllerProperties.Egress egress =
        TestSettings.withProxy(TestSettings.SETTINGS, enabled).egress();

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks(), egress, OTLP);

    // Assert
    assertThat(release.serves("Airlock", FF)).isEqualTo(valid);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "4d1e", "sha256:", "sha256:ABCD"})
  void givenJarWithoutSha256Digest_whenLoaded_thenTheAirlockNamesIt(String digest) {
    // Arrange
    List<Airlock> all = replace(HR, s -> withJar(s, new Jar(digest, s.jar().tools(), null)));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), all, EGRESS, OTLP);

    // Assert: every pod that mounts the jar checks it against the digest
    assertThat(release.problemsOf("Airlock", HR))
        .singleElement()
        .satisfies(p -> assertThat(p).contains("spec.jar.digest"));
  }

  @Test
  void givenNamesTooLongForAService_whenLoaded_thenTheAirlockNamesItsMcpServer() {
    // Arrange: a valid 26-character space and a valid 41-character airlock name;
    // <space>-<airlock>-mcp-server is past 63
    String space = "space-" + "x".repeat(20);
    String name = "airlock-" + "x".repeat(33);
    Spec hr = FIXTURES.airlocks().get(HR).spec();
    List<Space> spaces = List.of(space(space, FIXTURES.spaces().get("hr-acme").spec()));
    List<Airlock> airlocks =
        List.of(
            airlock(
                name,
                new Spec(
                    space,
                    hr.displayName(),
                    hr.description(),
                    hr.access(),
                    hr.preLLM(),
                    hr.llm(),
                    hr.prompt(),
                    hr.tools(),
                    hr.postLLM(),
                    hr.llms(),
                    hr.dataSources(),
                    hr.jar())));

    // Act
    Release release = Release.of(space, GLOBAL, spaces, airlocks, EGRESS, OTLP);

    // Assert
    assertThat(release.problemsOf("Airlock", name))
        .singleElement()
        .satisfies(p -> assertThat(p).contains(space + "-" + name + "-mcp-server").contains("63"));
  }

  @Test
  void givenAirlockWithAnUnknownProvider_whenLoaded_thenItAloneIsLeftOut() {
    // Arrange: the loader's problem, attributed by file
    List<Airlock> airlocks =
        replace(
            FF,
            a ->
                withLlms(
                    a,
                    List.of(
                        new Llm("gemma", "gpt", "http://host.minikube.internal:11434", "gemma3"))));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks, EGRESS, OTLP);

    // Assert: it is told why; the other airlock serves
    assertThat(release.problemsOf("Airlock", FF))
        .singleElement()
        .satisfies(
            p ->
                assertThat(p).contains("spec.llms[0].provider gpt is not one of [ollama, openai]"));
    assertThat(release.serves("Airlock", FF)).isFalse();
    assertThat(release.serves("Airlock", HR)).isTrue();
    assertThat(release.problemsOf("Airlock", HR)).isEmpty();
  }

  @Test
  void givenBrokenOtherSpaceAndAnAirlockOfNoSpace_whenLoaded_thenThisReleaseIsUntouched() {
    // Arrange: a space listing a blank user with an airlock of its own, and an airlock of a
    // missing space
    List<Space> spaces = new ArrayList<>(spaces());
    spaces.add(
        space(
            "broken",
            new SpaceDocument.Spec(
                "Broken", null, new SpaceDocument.Access(List.of(" ")), null, List.of())));
    List<Airlock> airlocks = new ArrayList<>(airlocks());
    Spec hr = FIXTURES.airlocks().get(HR).spec();
    airlocks.add(
        airlock(
            "broken-assistant",
            new Spec("broken", null, null, null, null, null, null, null, null, null, null, null)));
    airlocks.add(
        airlock(
            "homeless",
            new Spec(
                "missing",
                hr.displayName(),
                hr.description(),
                hr.access(),
                hr.preLLM(),
                hr.llm(),
                hr.prompt(),
                hr.tools(),
                hr.postLLM(),
                hr.llms(),
                hr.dataSources(),
                hr.jar())));

    // Act
    Release mine = Release.of("hr-acme", GLOBAL, spaces, airlocks, EGRESS, OTLP);
    Release broken = Release.of("broken", GLOBAL, spaces, airlocks, EGRESS, OTLP);

    // Assert: each space is its own release; an airlock of no space is not an error
    assertThat(mine.valid()).isTrue();
    assertThat(mine.configuration().airlocks()).containsOnlyKeys(HR, FF);
    assertThat(broken.problemsOf("Space", "broken"))
        .singleElement()
        .satisfies(
            p -> assertThat(p).contains("spec.access.users must not contain a blank e-mail"));
  }

  private static GlobalDocument withGlobalLlms(List<Llm> llms) {
    return new GlobalDocument(
        GLOBAL.metadata(), new GlobalDocument.Spec(llms, GLOBAL.spec().dataSources()));
  }

  private static List<Space> replaceSpace(UnaryOperator<SpaceDocument.Spec> change) {
    return List.of(space("hr-acme", change.apply(FIXTURES.spaces().get("hr-acme").spec())));
  }

  private static List<Airlock> replace(String name, UnaryOperator<Spec> change) {
    return airlocks().stream()
        .map(
            a ->
                a.getMetadata().getName().equals(name)
                    ? airlock(name, change.apply(a.getSpec()))
                    : a)
        .toList();
  }

  // Named apart: a local withDataSources would shadow TestResources' for the airlock's
  private static SpaceDocument.Spec withSpaceDataSources(
      SpaceDocument.Spec s, List<DataSource> dataSources) {
    return new SpaceDocument.Spec(
        s.displayName(), s.description(), s.access(), s.llms(), dataSources);
  }

  /** Its own main LLM's URL or its own data source's, over what it resolves elsewhere. */
  private static Spec withUrl(Spec s, String connection, String url) {
    if (connection.equals("llm")) {
      return withLlms(s, List.of(new Llm(s.llm(), "ollama", url, "granite4.1:8b")));
    }
    DataSource ds = s.dataSources().getFirst();
    return withDataSources(s, List.of(new DataSource(ds.id(), url, ds.secretRef())));
  }
}
