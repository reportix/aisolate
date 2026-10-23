package com.reportix.aisolate.backend.infrastructure.crypto;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The stored credentials' settings ({@code aisolate.credentials.*}).
 *
 * @param key the secret every typed API key and password is sealed with before it is stored; 32+
 *     characters, required with a cluster namespace; blank: nothing is stored
 * @param revealMaxAge how fresh an admin's sign-in must be to reveal a credential
 * @param revealClients the OIDC clients ({@code azp}) whose tokens may reveal: the AIsolate UI's
 */
@ConfigurationProperties("aisolate.credentials")
public record CredentialProperties(
    String key,
    @DefaultValue("PT5M") Duration revealMaxAge,
    @DefaultValue("aisolate-ui") List<String> revealClients) {}
