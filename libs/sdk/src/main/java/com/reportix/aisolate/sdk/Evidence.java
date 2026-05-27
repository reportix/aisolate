package com.reportix.aisolate.sdk;

/**
 * One evidence of the exchange: passages a handler added or the JSON a tool returned, verbatim.
 *
 * @param source the handler that added the passages, or the tool's name
 */
public record Evidence(String id, String source, String contentJson) {}
