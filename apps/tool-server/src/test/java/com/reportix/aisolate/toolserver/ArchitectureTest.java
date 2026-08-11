package com.reportix.aisolate.toolserver;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.reportix.aisolate",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule givenCodebase_whenAnalyzed_thenNoKeycloakImport =
      noClasses().should().dependOnClassesThat().resideInAPackage("org.keycloak..");
}
