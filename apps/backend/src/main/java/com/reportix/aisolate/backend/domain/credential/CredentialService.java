package com.reportix.aisolate.backend.domain.credential;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialCipher;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialProperties;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a sealed credential back for an admin who signed in within the reveal window, with a token
 * of the AIsolate UI's client. Every attempt is recorded, refusals before they are thrown; the
 * event never carries a value.
 */
@Service
@Slf4j
public class CredentialService {

  private static final long SKEW_SECONDS = 60;

  private final CredentialRepository rows;
  private final CredentialCipher cipher;
  private final CredentialProperties properties;
  private final AirlockRepository airlocks;
  private final JsonMapper json;
  private final Clock clock;

  public CredentialService(
      CredentialRepository rows,
      CredentialCipher cipher,
      CredentialProperties properties,
      AirlockRepository airlocks,
      JsonMapper json,
      Clock clock) {
    this.rows = rows;
    this.cipher = cipher;
    this.properties = properties;
    this.airlocks = airlocks;
    this.json = json;
    this.clock = clock;
  }

  /** The typed values by key; {@link #toString} prints the keys only. */
  public record Revealed(Map<String, String> values) {
    @Override
    public String toString() {
      return "Revealed" + values.keySet();
    }
  }

  public Revealed reveal(String secretRef, Map<String, Object> claims) {
    String actor =
        claims.get("email") instanceof String s && !s.isBlank() ? s : Creator.of(claims).username();

    if (!airlocks.isSecretReferenced(secretRef)) {
      throw refuse(null, secretRef, actor, HttpStatus.NOT_FOUND, "unknown credential", "unknown");
    }
    // Fail closed: the CLI's and a chat client's tokens are no AIsolate UI sign-in.
    if (!(claims.get("azp") instanceof String azp) || !properties.revealClients().contains(azp)) {
      throw refuse(
          null, secretRef, actor, HttpStatus.FORBIDDEN, "not from the AIsolate UI", "wrong_client");
    }
    // Epoch seconds as longs: an absurd value must be a refusal, not a DateTimeException.
    Long authTime =
        claims.get("auth_time") instanceof Number n
                && !(n instanceof Double d && !Double.isFinite(d))
                && !(n instanceof Float f && !Float.isFinite(f))
            ? n.longValue()
            : null;
    if (authTime == null) {
      throw refuse(
          null,
          secretRef,
          actor,
          HttpStatus.FORBIDDEN,
          "the identity provider sends no auth_time claim",
          "no_auth_time");
    }
    long now = clock.instant().getEpochSecond();
    // A login from the future is no fresh login; the minute is clock skew.
    if (authTime < now - properties.revealMaxAge().getSeconds() || authTime > now + SKEW_SECONDS) {
      throw refuse(
          null, secretRef, actor, HttpStatus.FORBIDDEN, "sign in again to reveal", "stale_login");
    }
    CredentialRepository.Row row = rows.find(secretRef).orElse(null);
    if (row == null) {
      throw refuse(
          null,
          secretRef,
          actor,
          HttpStatus.CONFLICT,
          "entered before encryption — enter it again",
          "no_ciphertext");
    }
    if (!row.keyId().equals(cipher.keyId())) {
      log.warn(
          "credential {} was sealed under key {}, the backend holds {}",
          secretRef,
          row.keyId(),
          cipher.keyId());
      throw refuse(
          row, secretRef, actor, HttpStatus.CONFLICT, "stored under another key", "key_changed");
    }
    byte[] plain = null;
    try {
      plain =
          cipher.open(
              secretRef, new CredentialCipher.Sealed(row.keyId(), row.nonce(), row.ciphertext()));
      Map<String, String> values =
          json.readValue(plain, new TypeReference<Map<String, String>>() {});
      rows.record(CredentialRepository.Event.of(row, actor, "revealed", null));
      return new Revealed(values);
    } catch (CredentialCipher.Unreadable | JacksonException e) {
      // not chained: Jackson's message can quote the plaintext
      throw refuse(row, secretRef, actor, HttpStatus.CONFLICT, "cannot be read", "unreadable");
    } finally {
      if (plain != null) {
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  private CredentialException refuse(
      CredentialRepository.Row row,
      String secretRef,
      String actor,
      HttpStatus status,
      String detail,
      String reason) {
    rows.record(
        row != null
            ? CredentialRepository.Event.of(row, actor, "reveal_refused", reason)
            : new CredentialRepository.Event(
                actor, "reveal_refused", secretRef, "", "", "", "", reason));
    return new CredentialException(status, detail, reason);
  }
}
