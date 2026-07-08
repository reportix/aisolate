package com.reportix.aisolate.backend.infrastructure.cluster;

import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.SecretsReplaced;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The Secrets that hold a data source's credentials or a model's API key, one per row, owned by the
 * Global, Space or Airlock resource that defines it, so a reset garbage-collects them. The backend
 * may create and delete Secrets, nothing else: a read would carry their data back. A save that
 * rolls back takes the Secrets it created along.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Credentials {

  private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ClusterProperties properties;
  private final ObjectProvider<KubernetesClient> client;
  private final AirlockRepository airlocks;
  private final ApplicationEventPublisher events;

  /**
   * A new Secret {@code <name>-<6 random>} holding {@code data}: a data source's {@code username}
   * and {@code password}, a model's {@code apiKey}.
   *
   * @param owner the resource that defines the row, as the cluster returned it (its uid set)
   * @return its name, once the cluster took it
   */
  public String create(HasMetadata owner, String name, Map<String, String> data) {
    String secretName = name + "-" + randomSuffix();
    Secret secret =
        new SecretBuilder()
            .withNewMetadata()
            .withName(secretName)
            .withNamespace(properties.namespace())
            // No blockOwnerDeletion: it would need update rights on the owner's finalizers.
            .addNewOwnerReference()
            .withApiVersion(owner.getApiVersion())
            .withKind(owner.getKind())
            .withName(owner.getMetadata().getName())
            .withUid(owner.getMetadata().getUid())
            .endOwnerReference()
            .endMetadata()
            .withType("Opaque")
            .withData(
                data.entrySet().stream()
                    .collect(
                        Collectors.toMap(Map.Entry::getKey, entry -> encode(entry.getValue()))))
            .build();
    client.getObject().secrets().inNamespace(properties.namespace()).resource(secret).create();
    events.publishEvent(new SecretCreated(secretName));
    return secretName;
  }

  public void delete(String name) {
    client.getObject().secrets().inNamespace(properties.namespace()).withName(name).delete();
  }

  /** A Secret a row still names stays: ids such as acme_hr and acme-hr share names. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleSecretsReplaced(SecretsReplaced event) {
    for (String name : event.names()) {
      if (airlocks.isSecretReferenced(name)) {
        continue;
      }
      try {
        delete(name);
      } catch (RuntimeException failed) {
        // Its owner's reset takes it along.
        log.warn("secret {} could not be deleted: {}", name, failed.getMessage());
      }
    }
  }

  /**
   * No row names a Secret a rolled-back save created. Only after a rollback: when the outcome is
   * unknown, the save may have committed and name it.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
  public void handleSecretCreated(SecretCreated event) {
    try {
      delete(event.name());
    } catch (RuntimeException failed) {
      // Its owner's reset takes it along.
      log.warn("secret {} could not be deleted: {}", event.name(), failed.getMessage());
    }
  }

  private static String randomSuffix() {
    StringBuilder suffix = new StringBuilder(6);
    for (int i = 0; i < 6; i++) {
      suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
    }
    return suffix.toString();
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
