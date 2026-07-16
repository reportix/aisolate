package com.reportix.aisolate.controller.render;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resource each rendered object belongs to, by {@code Kind/name}: Kubernetes garbage-collects.
 */
public record Owners(Map<String, OwnerReference> byKindName) {

  public static Owners of(List<? extends HasMetadata> resources) {
    Map<String, OwnerReference> owners = new HashMap<>();
    for (HasMetadata resource : resources) {
      owners.put(
          resource.getKind() + "/" + resource.getMetadata().getName(),
          new OwnerReferenceBuilder()
              .withApiVersion(resource.getApiVersion())
              .withKind(resource.getKind())
              .withName(resource.getMetadata().getName())
              .withUid(resource.getMetadata().getUid())
              .withController(true)
              .build());
    }
    return new Owners(owners);
  }

  public Optional<OwnerReference> of(String kind, String name) {
    return Optional.ofNullable(byKindName.get(kind + "/" + name));
  }
}
