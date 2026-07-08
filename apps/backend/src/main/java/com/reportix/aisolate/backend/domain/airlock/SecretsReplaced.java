package com.reportix.aisolate.backend.domain.airlock;

import java.util.List;

/** Secrets a saved space or airlock no longer names: deleted once the save committed. */
public record SecretsReplaced(List<String> names) {}
