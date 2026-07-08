package com.reportix.aisolate.backend.infrastructure.cluster;

/** A Secret a save created: deleted if the save rolls back, as no row will ever name it. */
public record SecretCreated(String name) {}
