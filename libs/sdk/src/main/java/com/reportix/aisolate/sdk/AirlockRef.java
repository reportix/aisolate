package com.reportix.aisolate.sdk;

/**
 * Which airlock version an exchange runs on, and at which revision of its configuration.
 *
 * @param version the version's label within its airlock, for example {@code v1}
 */
public record AirlockRef(String space, String airlock, String version, String revision) {}
