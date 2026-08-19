package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.identity.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Gate 2: may this validated token use this airlock at all? Runs before anything is written to the
 * response, so an unknown airlock or a user it does not list still gets a status code and a problem
 * document. The admission itself is {@link Principal#admissionFailure}: its space's users and its
 * own, its callers and its machine clients, no role among them; the tool server repeats it on its
 * own reading of the token. Discovery shows each token the airlocks that would admit it.
 */
@Service
@RequiredArgsConstructor
public class AirlockAdmissionService {

  private final AirlockCatalog airlocks;
  private final IdentityProperties properties;
  private final AuditLog auditLog;
  private final ExchangeTracing tracing;

  /**
   * @param userToken the validated bearer token of the end user, as Spring Security decoded it
   * @param question what the caller asked, for the audit line of a refusal; never read otherwise
   * @throws AuditUnavailableException the pod cannot keep another audit line (503): failing closed,
   *     nothing is admitted, a refusal included
   * @throws AirlockNotFoundException no such airlock in this space (404)
   * @throws ForbiddenException the airlock does not list the user or the calling client (403); one
   *     {@code refusal} line is written to the audit trail first
   */
  public AirlockDefinition admit(String spaceId, String airlockId, Jwt userToken, String question) {
    return tracing.admission(span -> admit(spaceId, airlockId, userToken, question, span));
  }

  private AirlockDefinition admit(
      String spaceId,
      String airlockId,
      Jwt userToken,
      String question,
      ExchangeTracing.Admission span) {
    if (!auditLog.accepting()) {
      throw new AuditUnavailableException();
    }
    Instant began = Instant.now();
    AirlockDefinition airlock =
        airlocks
            .findAirlock(airlockId)
            .filter(found -> found.spaceId().equals(spaceId))
            .orElseThrow(() -> new AirlockNotFoundException(spaceId + "/" + airlockId));
    Principal user = Principal.of(userToken.getClaims(), properties.rolesClaim());
    span.asked(airlock, user.azp());
    Optional<Principal.AdmissionFailure> failure =
        user.admissionFailure(
            airlock.access().users(), airlock.access().callers(), airlock.access().clients());
    if (failure.isPresent()) {
      String reason = failure.get().name().toLowerCase(Locale.ROOT);
      span.refused(reason);
      auditLog.record(
          AuditEvent.refusal(
              began,
              tracing.traceId(),
              airlock.spaceId(),
              airlock.id(),
              airlock.revision(),
              user.email(),
              user.azp(),
              question,
              reason,
              Duration.between(began, Instant.now()).toMillis()));
      // a machine token has no e-mail: name the client alone
      throw new ForbiddenException(
          airlock.spaceId()
              + "/"
              + airlock.id()
              + " does not admit "
              + (user.email() == null
                  ? "client " + user.azp()
                  : "user " + user.email() + " from client " + user.azp()));
    }
    return airlock;
  }

  /**
   * The airlocks this token may use, each with the endpoint a chat client posts to; an airlock that
   * refuses it stays hidden, like an unknown one, and one before its first deploy is listed without
   * tools.
   */
  public List<AirlockSummaryDTO> listAdmittedAirlocks(Map<String, Object> claims) {
    Principal user = Principal.of(claims, properties.rolesClaim());
    return airlocks.listAirlocks().stream()
        // Discovery holds keys, not e-mails: the one listed is the user's own, or none.
        .filter(
            airlock ->
                user.admissionFailure(
                        airlock.lists(user.email()) ? List.of(user.email()) : List.of(),
                        airlock.callers(),
                        airlock.clients())
                    .isEmpty())
        .map(
            airlock ->
                new AirlockSummaryDTO(
                    airlock.id(),
                    airlock.space(),
                    airlock.name(),
                    airlock.description(),
                    properties.chatEndpoint(airlock.space(), airlock.id()),
                    airlock.tools()))
        .toList();
  }
}
