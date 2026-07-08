package com.reportix.aisolate.backend.infrastructure.directory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The company's directory, Keycloak's admin API read through a service account ({@code
 * aisolate.directory.*}). {@code url} empty means none: users are typed e-mails.
 *
 * @param url Keycloak's base URL, without {@code /realms}
 * @param clientId the confidential client whose service account has realm-management's {@code
 *     view-users} and {@code query-users} only
 * @param clientSecret its secret, from a Secret; never logged or returned
 * @param timeout how long one call to Keycloak may take
 */
@ConfigurationProperties("aisolate.directory")
public record DirectoryProperties(
    String url,
    String realm,
    String clientId,
    String clientSecret,
    @DefaultValue("3s") Duration timeout) {

  /** Without the secret: a bound record is printed by failure analysis and debug logs. */
  @Override
  public String toString() {
    return "DirectoryProperties[url="
        + url
        + ", realm="
        + realm
        + ", clientId="
        + clientId
        + ", timeout="
        + timeout
        + "]";
  }
}
