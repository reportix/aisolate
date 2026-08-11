package com.reportix.aisolate.toolserver.application;

import static com.reportix.aisolate.toolserver.support.ToolServerTestConfig.REVISION;
import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.toolserver.ToolServerApplication;
import com.reportix.aisolate.toolserver.domain.GovernedQueryService;
import com.reportix.aisolate.toolserver.domain.UserToken;
import com.reportix.aisolate.toolserver.support.ToolServerTestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/**
 * An organisation whose identity provider puts the roles elsewhere names the path in {@code
 * AISOLATE_ROLES_CLAIM}, and the rules read them there. Keycloak writes no such claim, so the
 * verified claims are handed to the service as the MCP layer would.
 */
@SpringBootTest(
    classes = ToolServerApplication.class,
    properties = "aisolate.roles-claim=org.roles")
@ContextConfiguration(initializers = ToolServerTestConfig.class)
class RolesClaimIntegrationTest {

  @Autowired GovernedQueryService governedQuery;

  @ParameterizedTest
  @CsvSource({
    // where this install reads roles: her ceo role selects leadership, every row
    "org, 5, leadership",
    // Keycloak's default path is not read: no rule, nothing to see
    "realm_access, 0, ''"
  })
  void givenCeoRoleUnderAClaim_whenReadRecords_thenTheConfiguredClaimSelectsTheRule(
      String claim, int rows, String rule) {
    // Arrange: nora, a user of the space, through the Acme chat
    UserToken nora =
        new UserToken(
            Map.of(
                "sub",
                "nora",
                "email",
                "nora@example.com",
                "azp",
                "acme-chat",
                claim,
                Map.of("roles", List.of("ceo"))));

    // Act
    Result result = governedQuery.call(nora, "hr-assistant", REVISION, "read_records", Map.of());

    // Assert
    assertThat(result.rowCount()).isEqualTo(rows);
    assertThat(result.rules()).isEqualTo(rule.isEmpty() ? List.of() : List.of(rule));
  }
}
