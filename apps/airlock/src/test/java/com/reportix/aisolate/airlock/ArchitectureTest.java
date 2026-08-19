package com.reportix.aisolate.airlock;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;

/** Rules of the airlock pod that must hold in code. */
@AnalyzeClasses(
    packages = "com.reportix.aisolate",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoKeycloakImport =
      noClasses().should().dependOnClassesThat().resideInAPackage("org.keycloak..");

  /**
   * The pod keeps no database of its own; the data sources it opens are the airlock's, handed to
   * the handlers through the SDK.
   */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoDatabase =
      noClasses()
          .that()
          .resideInAPackage("..airlock..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "org.flywaydb..");

  /**
   * Keeps the chat package away from the MCP client, the one class that reads the user's token from
   * the request's security context.
   */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenChatPackageDoesNotDependOnInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..airlock.domain.chat..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..airlock.infrastructure..");

  @ArchTest
  static final ArchRule
      givenCodebase_whenAnalyzed_thenChatPackageDoesNotDependOnIdentityOrSecurity =
          noClasses()
              .that()
              .resideInAPackage("..airlock.domain.chat..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "org.springframework.security..", "com.reportix.aisolate.identity..");

  /** The security context is read in exactly one package; controllers get the Jwt injected. */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenOnlyInfrastructureReadsTheSecurityContext =
      noClasses()
          .that()
          .resideOutsideOfPackage("..airlock.infrastructure..")
          .and()
          .resideInAPackage("..airlock..")
          .should()
          .accessClassesThat()
          .belongToAnyOf(SecurityContextHolder.class);

  /** The raw token value is read in exactly one class, the MCP client that forwards it. */
  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenOnlyTheMcpClientReadsTheTokenValue =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(
              "com.reportix.aisolate.airlock.infrastructure.mcp.McpSyncToolClient")
          .should()
          .callMethodWhere(
              target(name("getTokenValue"))
                  .and(target(owner(assignableTo(AbstractOAuth2Token.class)))));
}
