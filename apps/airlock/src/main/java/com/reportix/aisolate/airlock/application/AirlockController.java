package com.reportix.aisolate.airlock.application;

import com.reportix.aisolate.airlock.domain.airlock.AirlockAdmissionService;
import com.reportix.aisolate.airlock.domain.airlock.AirlockSummaryDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Airlocks as end users' chat clients discover them under {@code /v1/airlocks}, across spaces, next
 * to the chat endpoints themselves ({@link ChatController}): the airlocks their token admits.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AirlockController {

  private final AirlockAdmissionService airlockAdmission;

  // Chat clients: any valid token; each airlock's own admission (gate 2) decides what it sees.

  /**
   * The airlocks this token may use, each with the endpoint it may post to: the chat's picker. An
   * airlock before its first deploy is listed without tools.
   */
  @GetMapping("/airlocks")
  public List<AirlockSummaryDTO> list(@AuthenticationPrincipal Jwt jwt) {
    return airlockAdmission.listAdmittedAirlocks(jwt.getClaims());
  }
}
