package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names the configuration that answered on every response, so a client, a log line and an audit row
 * can be matched to one release: its revision.
 */
@Component
@RequiredArgsConstructor
public class RevisionHeaderFilter extends OncePerRequestFilter {

  public static final String HEADER = "x-aisolate-revision";

  private final AirlockCatalog airlocks;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (airlocks.revision() != null) {
      response.setHeader(HEADER, airlocks.revision());
    }
    chain.doFilter(request, response);
  }
}
