package com.reportix.aisolate.toolserver.domain;

import java.util.Map;

/**
 * The validated token of the user this request runs for: its claims decide the rules. Never logged,
 * never part of any tool result.
 */
public record UserToken(Map<String, Object> claims) {}
