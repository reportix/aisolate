package com.reportix.aisolate.sdk;

/** Which airlock an exchange runs on, and at which revision of its configuration. */
public record AirlockRef(String space, String airlock, String revision) {}
