package com.reportix.aisolate.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What every AIsolate process says about itself to callers ({@code aisolate.*}). The identity
 * provider itself is configured through Spring Security's resource-server properties.
 *
 * @param publicUrl builds endpoint links and the 401 challenge
 * @param rolesClaim dot path of the roles claim in the customer's tokens: the AIsolate UI roles and
 *     the roles the rules and handlers see
 * @param cors browser origins allowed to call the API; the token decides, not the origin
 */
@ConfigurationProperties("aisolate")
public record IdentityProperties(
    String publicUrl,
    @DefaultValue(Principal.DEFAULT_ROLES_CLAIM) String rolesClaim,
    @DefaultValue Cors cors) {

  public record Cors(@DefaultValue("*") List<String> allowedOrigins) {}

  /** The endpoint of one airlock: the URL a chat client posts its messages to. */
  public String chatEndpoint(String spaceId, String airlockId) {
    return publicUrl + "/v1/spaces/" + spaceId + "/airlocks/" + airlockId + "/chat";
  }
}
