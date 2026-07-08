package com.reportix.aisolate.backend.infrastructure.cluster;

import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

/**
 * A Space or Airlock resource a save created: deleted if the save rolls back, as no row defines it.
 *
 * @param uid the created resource's: one deleted and created again since under its name has
 *     another, and stays
 */
public record ResourceCreated(ResourceDefinitionContext kind, String name, String uid) {}
