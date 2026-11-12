package com.reportix.aisolate.controller.release;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.airlock;
import static com.reportix.aisolate.controller.support.TestResources.airlocks;
import static com.reportix.aisolate.controller.support.TestResources.space;
import static com.reportix.aisolate.controller.support.TestResources.spaces;
import static com.reportix.aisolate.controller.support.TestResources.version;
import static com.reportix.aisolate.controller.support.TestResources.versions;
import static com.reportix.aisolate.controller.support.TestResources.withDataSources;
import static com.reportix.aisolate.controller.support.TestResources.withJar;
import static com.reportix.aisolate.controller.support.TestResources.withLlms;
import static com.reportix.aisolate.controller.support.TestResources.withVersionLlms;
import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument.Jar;
import com.reportix.aisolate.config.AirlockVersionDocument.Spec;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
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

/** What the release refuses beyond the loader: what the cluster needs to run a version. */
class ReleaseTest {

  private static final ControllerProperties.Egress EGRESS = TestSettings.SETTINGS.egress();
  private static final String OTLP = TestSettings.SETTINGS.otlpEndpoint();
  private static final String HR = "hr-assistant";
  private static final String HR_V1 = "hr-assistant-v1";
  private static final String FF = "founders-finance";
  private static final String FF_V1 = "founders-finance-v1";
  private static final GlobalDocument GLOBAL = FIXTURES.global();

  static Stream<Arguments> incompleteDataSources() {
    AirlockDocument.Spec hr = FIXTURES.airlocks().get(HR).spec();
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
  void givenIncompleteDataSource_whenLoaded_thenInvalidNamingTheField(
      AirlockDocument.Spec spec, String field) {
    // Arrange: the airlock's data source, which its version resolves
    List<Airlock> all = replaceAirlock(HR, s -> spec);

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), all, versions(), EGRESS, OTLP);

    // Assert: said on the version (the loader's problem on the airlock, with its version), not
    // discovered from a crash-looping pod; its sibling serves
    assertThat(release.serves("AirlockVersion", HR_V1)).isFalse();
    assertThat(release.serves("AirlockVersion", FF_V1)).isTrue();
    assertThat(
            Stream.concat(
                    release.problemsOf("AirlockVersion", HR_V1).stream(),
                    release.problemsOf("Airlock", HR).stream())
                .toList())
        .anySatisfy(p -> assertThat(p).contains(field));
  }

  @Test
  void givenSpaceDataSourceWithoutSecret_whenLoaded_thenEachVersionResolvingItNamesIt() {
    // Arrange: founders-finance replaces acme_hr with its own; hr-assistant-v1 reads the space's
    List<Space> spaces =
        replaceSpace(s -> {
          DataSource ds = s.dataSources().getFirst();
          return withSpaceDataSources(s, List.of(new DataSource(ds.id(), ds.url(), null)));
        });

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces, airlocks(), versions(), EGRESS, OTLP);

    // Assert: each version is told about the data sources it resolves, wherever they are defined;
    // one that resolves none of the broken ones still serves
    assertThat(release.problemsOf("AirlockVersion", HR_V1))
        .containsExactly(
            "AirlockVersion hr-assistant-v1: dataSources[acme_hr].secretRef is required");
    assertThat(release.problemsOf("AirlockVersion", FF_V1)).isEmpty();
    assertThat(release.serves("AirlockVersion", FF_V1)).isTrue();
  }

  @Test
  void givenVersionWithoutCode_whenLoaded_thenValid() {
    // Arrange: the version as the AIsolate UI saves it, before its first deploy
    List<AirlockVersion> all =
        replace(
            HR_V1,
            s ->
                new Spec(
                    s.airlock(),
                    s.version(),
                    s.description(),
                    null,
                    s.llm(),
                    s.prompt(),
                    List.of(),
                    null,
                    s.llms(),
                    null));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks(), all, EGRESS, OTLP);

    // Assert
    assertThat(release.valid()).isTrue();
    assertThat(release.serves("AirlockVersion", HR_V1)).isTrue();
    assertThat(release.problemsOf("AirlockVersion", HR_V1)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "llm, http://ollama.elsewhere.example:11434, llms[granite].url",
    "dataSource, jdbc:postgresql://db.elsewhere.example:5432/hr, dataSources[hr-documents].url"
  })
  void givenUrlHostTheAdminDoesNotAllow_whenLoaded_thenInvalidNamingFieldAndHostOnly(
      String connection, String url, String field) {
    // Arrange: the version's own main LLM, or its airlock's data source
    List<AirlockVersion> versions =
        connection.equals("llm")
            ? replace(
                HR_V1,
                s ->
                    withVersionLlms(s, List.of(new Llm("granite", "ollama", url, "granite4.1:8b"))))
            : versions();
    List<Airlock> airlocks =
        connection.equals("llm")
            ? airlocks()
            : replaceAirlock(
                HR,
                a -> {
                  DataSource ds = a.dataSources().getFirst();
                  return withDataSources(a, List.of(new DataSource(ds.id(), url, ds.secretRef())));
                });

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks, versions, EGRESS, OTLP);

    // Assert: the field and the host, never the URL
    assertThat(release.problemsOf("AirlockVersion", HR_V1))
        .singleElement()
        .satisfies(p ->
            assertThat(p)
                .startsWith("AirlockVersion hr-assistant-v1: " + field + ": the host ")
                .contains("elsewhere.example")
                .doesNotContain("11434")
                .doesNotContain("5432"));
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, true"})
  void givenGlobalLlmOnAnUnmappedHost_whenLoaded_thenLeftOutAndOnlyAVersionRunningItInvalid(
      boolean airlockDefinesIt, boolean serves) {
    // Arrange: the global granite, hr-assistant-v1's main LLM, on a host the admin did not map
    GlobalDocument global =
        withGlobalLlms(
            List.of(
                new Llm("granite", "ollama", "http://ollama.elsewhere.example:11434", "g"),
                GLOBAL.spec().llms().getLast()));
    List<Airlock> airlocks =
        airlockDefinesIt
            ? replaceAirlock(
                HR,
                a ->
                    withLlms(
                        a,
                        List.of(
                            new Llm(
                                "granite", "ollama", "http://host.minikube.internal:11434", "g"))))
            : airlocks();

    // Act
    Release release = Release.of("hr-acme", global, spaces(), airlocks, versions(), EGRESS, OTLP);
    Release.ServedGlobal served = Release.ServedGlobal.of(global, EGRESS);

    // Assert: the Global is told; no catalog holds the entry; the other version serves
    assertThat(served.problems())
        .singleElement()
        .satisfies(p ->
            assertThat(p)
                .startsWith("llms[granite].url: the host ollama.elsewhere.example")
                .doesNotContain("11434"));
    assertThat(release.configuration().llmsOf(FF_V1)).extracting(Llm::id).doesNotContain("granite");
    assertThat(release.serves("AirlockVersion", HR_V1)).isEqualTo(serves);
    assertThat(release.problemsOf("AirlockVersion", HR_V1))
        .isEqualTo(
            serves
                ? List.of()
                : List.of(
                    "AirlockVersion hr-assistant-v1: llm granite: host ollama.elsewhere.example"
                        + " is not in platform.egress.hosts"));
  }

  @ParameterizedTest
  @CsvSource({
    "jdbc:postgresql://db.elsewhere.example:5432/audit, aisolate-ds-audit-log-j1k2l3,"
        + " 'dataSources[audit_log].url: the host db.elsewhere.example is not allowed'",
    "jdbc:postgresql://acme-db.aisolate.svc:5432/audit, '',"
        + " dataSources[audit_log].secretRef is required"
  })
  void givenGlobalDataSourceUnreachableOrWithoutSecret_whenLoaded_thenLeftOutAndEveryVersionServes(
      String url, String secretRef, String problem) {
    // Arrange
    GlobalDocument global =
        new GlobalDocument(
            GLOBAL.metadata(),
            new GlobalDocument.Spec(
                GLOBAL.spec().llms(), List.of(new DataSource("audit_log", url, secretRef))));

    // Act
    Release release = Release.of("hr-acme", global, spaces(), airlocks(), versions(), EGRESS, OTLP);

    // Assert
    assertThat(Release.ServedGlobal.of(global, EGRESS).problems())
        .singleElement()
        .satisfies(p -> assertThat(p).startsWith(problem));
    assertThat(release.serves("AirlockVersion", HR_V1)).isTrue();
    assertThat(release.serves("AirlockVersion", FF_V1)).isTrue();
    assertThat(release.configuration().dataSourcesOf(HR_V1))
        .extracting(DataSource::id)
        .doesNotContain("audit_log");
  }

  @Test
  void givenOtlpEndpointOnAnUnmappedHost_whenLoaded_thenInvalidForEveryVersion() {
    // Arrange
    String endpoint = "http://otel-collector.observability.svc:4318";

    // Act
    Release release =
        Release.of("hr-acme", GLOBAL, spaces(), airlocks(), versions(), EGRESS, endpoint);

    // Assert: every version's pods would send there, so every one is told
    assertThat(release.valid()).isFalse();
    assertThat(release.problemsOf("AirlockVersion", HR_V1))
        .containsExactly(
            "platform.observability.otlpEndpoint host otel-collector.observability.svc is not in"
                + " platform.egress.hosts");
  }

  @ParameterizedTest
  @CsvSource({"true, true", "false, false"})
  void givenProxiedHost_whenLoaded_thenValidOnlyWhileTheProxyRuns(boolean enabled, boolean valid) {
    // Arrange: the space's qwen, which founders-finance-v1 runs, is behind the egress proxy
    ControllerProperties.Egress egress =
        TestSettings.withProxy(TestSettings.SETTINGS, enabled).egress();

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks(), versions(), egress, OTLP);

    // Assert
    assertThat(release.serves("AirlockVersion", FF_V1)).isEqualTo(valid);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "4d1e", "sha256:", "sha256:ABCD"})
  void givenJarWithoutSha256Digest_whenLoaded_thenTheVersionNamesIt(String digest) {
    // Arrange
    List<AirlockVersion> all =
        replace(HR_V1, s -> withJar(s, new Jar(digest, s.jar().tools(), null)));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks(), all, EGRESS, OTLP);

    // Assert: every pod that mounts the jar checks it against the digest
    assertThat(release.problemsOf("AirlockVersion", HR_V1))
        .singleElement()
        .satisfies(p -> assertThat(p).contains("spec.jar.digest"));
  }

  @Test
  void givenNamesTooLongForAService_whenLoaded_thenTheVersionNamesItsMcpServer() {
    // Arrange: a valid 41-character airlock name; hr-acme-<airlock>-v100-mcp-server makes 65
    String name = "airlock-" + "x".repeat(33);
    AirlockDocument.Spec a = FIXTURES.airlocks().get(HR).spec();
    Spec v1 = FIXTURES.airlockVersions().get(HR_V1).spec();
    List<Airlock> airlocks = List.of(airlock(name, a));
    List<AirlockVersion> versions =
        List.of(
            version(
                name + "-v100",
                new Spec(
                    name,
                    "v100",
                    v1.description(),
                    v1.preLLM(),
                    v1.llm(),
                    v1.prompt(),
                    v1.tools(),
                    v1.postLLM(),
                    v1.llms(),
                    v1.jar())));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks, versions, EGRESS, OTLP);

    // Assert
    assertThat(release.problemsOf("AirlockVersion", name + "-v100"))
        .singleElement()
        .satisfies(
            p -> assertThat(p).contains("hr-acme-" + name + "-v100-mcp-server").contains("63"));
  }

  @Test
  void givenAirlockWithAnUnknownProvider_whenLoaded_thenItAndItsVersionsAloneAreLeftOut() {
    // Arrange: the loader's problem, attributed by file
    List<Airlock> airlocks =
        replaceAirlock(
            FF,
            a ->
                withLlms(
                    a,
                    List.of(
                        new Llm("gemma", "gpt", "http://host.minikube.internal:11434", "gemma3"))));

    // Act
    Release release = Release.of("hr-acme", GLOBAL, spaces(), airlocks, versions(), EGRESS, OTLP);

    // Assert: its version is told why; the other airlock and its version serve
    assertThat(release.problemsOf("Airlock", FF))
        .singleElement()
        .satisfies(p ->
            assertThat(p).contains("spec.llms[0].provider gpt is not one of [ollama, openai]"));
    assertThat(release.problemsOf("AirlockVersion", FF_V1))
        .containsExactly("its Airlock founders-finance is invalid");
    assertThat(release.serves("Airlock", HR)).isTrue();
    assertThat(release.serves("AirlockVersion", HR_V1)).isTrue();
    assertThat(release.problemsOf("AirlockVersion", HR_V1)).isEmpty();
  }

  @Test
  void givenBrokenOtherSpaceAndAVersionOfNoAirlock_whenLoaded_thenThisReleaseIsUntouched() {
    // Arrange: a space listing a blank role with an airlock of its own, and a version of a missing
    // airlock
    List<Space> spaces = new ArrayList<>(spaces());
    spaces.add(
        space(
            "broken",
            new SpaceDocument.Spec(
                "Broken", null, new SpaceDocument.Access(List.of(" ")), null, List.of())));
    List<Airlock> airlocks = new ArrayList<>(airlocks());
    airlocks.add(
        airlock(
            "broken-assistant", new AirlockDocument.Spec("broken", null, null, null, null, null)));
    List<AirlockVersion> versions = new ArrayList<>(versions());
    Spec v1 = FIXTURES.airlockVersions().get(HR_V1).spec();
    versions.add(
        version(
            "missing-v1",
            new Spec(
                "missing",
                v1.version(),
                v1.description(),
                v1.preLLM(),
                v1.llm(),
                v1.prompt(),
                v1.tools(),
                v1.postLLM(),
                v1.llms(),
                v1.jar())));

    // Act
    Release mine = Release.of("hr-acme", GLOBAL, spaces, airlocks, versions, EGRESS, OTLP);
    Release broken = Release.of("broken", GLOBAL, spaces, airlocks, versions, EGRESS, OTLP);

    // Assert: each space is its own release; a version of no airlock is not an error
    assertThat(mine.valid()).isTrue();
    assertThat(mine.configuration().airlocks()).containsOnlyKeys(HR, FF);
    assertThat(mine.configuration().airlockVersions()).containsOnlyKeys(HR_V1, FF_V1);
    assertThat(broken.problemsOf("Space", "broken"))
        .singleElement()
        .satisfies(p -> assertThat(p).contains("spec.access.roles"));
    assertThat(broken.valid()).isFalse();
  }

  private static GlobalDocument withGlobalLlms(List<Llm> llms) {
    return new GlobalDocument(
        GLOBAL.metadata(), new GlobalDocument.Spec(llms, GLOBAL.spec().dataSources()));
  }

  private static List<Space> replaceSpace(UnaryOperator<SpaceDocument.Spec> change) {
    return List.of(space("hr-acme", change.apply(FIXTURES.spaces().get("hr-acme").spec())));
  }

  private static List<Airlock> replaceAirlock(
      String name, UnaryOperator<AirlockDocument.Spec> change) {
    return airlocks().stream()
        .map(a ->
            a.getMetadata().getName().equals(name) ? airlock(name, change.apply(a.getSpec())) : a)
        .toList();
  }

  private static List<AirlockVersion> replace(String name, UnaryOperator<Spec> change) {
    return versions().stream()
        .map(v ->
            v.getMetadata().getName().equals(name) ? version(name, change.apply(v.getSpec())) : v)
        .toList();
  }

  // Named apart: a local withDataSources would shadow TestResources' for the airlock's
  private static SpaceDocument.Spec withSpaceDataSources(
      SpaceDocument.Spec s, List<DataSource> dataSources) {
    return new SpaceDocument.Spec(
        s.displayName(), s.description(), s.access(), s.llms(), dataSources);
  }
}
