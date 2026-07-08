package com.reportix.aisolate.backend;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.reportix.aisolate.backend.application.AuditIngestController;
import com.reportix.aisolate.backend.application.CodeController;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;

/** Rules of the backend that must hold in code, not only in prose. */
@AnalyzeClasses(
    packages = "com.reportix.aisolate",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoKeycloakImport =
      noClasses().should().dependOnClassesThat().resideInAPackage("org.keycloak..");

  /** The control plane never talks to a model or a tool server: that is the airlock pod's job. */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoModelOrToolClient =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.ai..", "io.modelcontextprotocol..");

  /** No chat code in the backend: the exchange, the agent loop and the handlers live in the pod. */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoChatClass =
      noClasses().that().resideInAPackage("..backend..").should().haveSimpleNameContaining("Chat");

  /**
   * Controllers get the Jwt injected; nothing outside infrastructure reads the security context.
   */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenOnlyInfrastructureReadsTheSecurityContext =
      noClasses()
          .that()
          .resideOutsideOfPackage("..backend.infrastructure..")
          .should()
          .accessClassesThat()
          .belongToAnyOf(SecurityContextHolder.class);

  /**
   * The SDK is what a developer's jar compiles against: nothing but the JDK may leak into it, its
   * {@code javax.sql} included (a handler's data source).
   */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenSdkDependsOnTheJdkOnly =
      classes()
          .that()
          .resideInAPackage("com.reportix.aisolate.sdk..")
          .should()
          .onlyDependOnClassesThat(
              resideInAnyPackage("java..", "javax.sql..", "com.reportix.aisolate.sdk..")
                  .or(
                      DescribedPredicate.describe(
                          "a primitive or an array", c -> c.isPrimitive() || c.isArray())));

  /**
   * The filter chain only authenticates; each endpoint names who may call it, one of {@link
   * Access}'s expressions: the admin, a developer of the space, a developer of any space, the
   * audit's readers, or anyone signed in for a list filtered to what they may see. The audit ingest
   * and the code fetch are no user's ({@link
   * #givenPodEndpoints_whenAnalyzed_thenTheyAreTheOnlyEndpointsWithoutARole}).
   */
  @ArchTest
  static final ArchRule givenControllers_whenAnalyzed_thenEveryEndpointNamesItsUiRoles =
      methods()
          .that()
          .areMetaAnnotatedWith(RequestMapping.class)
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..backend..")
          .and()
          .areNotDeclaredIn(AuditIngestController.class)
          .and()
          .areNotDeclaredIn(CodeController.class)
          .should()
          .beAnnotatedWith(
              DescribedPredicate.describe(
                  "@PreAuthorize with one of Access's expressions",
                  a ->
                      a.getRawType().isEquivalentTo(PreAuthorize.class)
                          && List.of(
                                  Access.ADMIN,
                                  Access.DEVELOPER,
                                  Access.DEVELOPER_ANYWHERE,
                                  Access.AUDIT_READER,
                                  Access.SIGNED_IN)
                              .contains(a.get("value").orElse(null))));

  /**
   * An airlock pod hands its audit lines over with its airlock's ingest token, and both pods fetch
   * its code with its code token, not a user's: the only endpoints without a role, {@code ingest}
   * and {@code fetch}, so a second endpoint in those classes cannot slip past the rule above.
   * AuditIntegrationTest and CodeIntegrationTest pin what they check instead.
   */
  @ArchTest
  static final ArchRule givenPodEndpoints_whenAnalyzed_thenTheyAreTheOnlyEndpointsWithoutARole =
      methods()
          .that()
          .areMetaAnnotatedWith(RequestMapping.class)
          .and()
          .areDeclaredInClassesThat()
          .belongToAnyOf(AuditIngestController.class, CodeController.class)
          .should(
              ArchConditions.have(
                  DescribedPredicate.<JavaMethod>describe(
                      "the name ingest or fetch",
                      m -> List.of("ingest", "fetch").contains(m.getName()))))
          .andShould()
          .notBeAnnotatedWith(PreAuthorize.class);
}
