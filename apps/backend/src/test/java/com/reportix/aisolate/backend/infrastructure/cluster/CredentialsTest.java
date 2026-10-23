package com.reportix.aisolate.backend.infrastructure.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.SecretsReplaced;
import com.reportix.aisolate.backend.domain.credential.CredentialRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@EnableKubernetesMockClient(crud = true)
class CredentialsTest {

  static KubernetesClient client; // injected by the mock server extension

  private final AirlockRepository airlocks = mock(AirlockRepository.class);
  private final CredentialRepository rows = mock(CredentialRepository.class);
  private final List<Object> published = new ArrayList<>();

  private final Credentials credentials =
      new Credentials(
          new ClusterProperties("aisolate"),
          new StaticListableBeanFactory(Map.of("client", client))
              .getBeanProvider(KubernetesClient.class),
          airlocks,
          published::add,
          rows);

  /** The resource as the API server returns it: with its uid. */
  private static GenericKubernetesResource owner(ResourceDefinitionContext kind, String name) {
    GenericKubernetesResource resource = new GenericKubernetesResource();
    resource.setApiVersion("aisolate.dev/v1");
    resource.setKind(kind.getKind());
    resource.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("aisolate").build());
    return client
        .genericKubernetesResources(kind)
        .inNamespace("aisolate")
        .resource(resource)
        .createOr(op -> op.update());
  }

  @Test
  void givenOwnerAndLogin_whenCreated_thenSecretNamedAfterItAndOwnedByIt() {
    // Arrange: the Airlock owner; the cluster sync tests see the Space's and the Global's
    ResourceDefinitionContext kind = ClusterSyncService.AIRLOCKS;
    String ownerName = "hr-assistant";
    String name = "hr-acme-hr-assistant-ds-acme-hr";
    GenericKubernetesResource owner = owner(kind, ownerName);

    // Act
    String created =
        credentials.create(owner, name, Map.of("username", "reader", "password", "p#ss: 'x'"));

    // Assert
    assertThat(created).matches(name + "-[a-z0-9]{6}");
    Secret secret = client.secrets().inNamespace("aisolate").withName(created).get();
    assertThat(secret.getType()).isEqualTo("Opaque");
    assertThat(decode(secret.getData().get("username"))).isEqualTo("reader");
    assertThat(decode(secret.getData().get("password"))).isEqualTo("p#ss: 'x'");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.getApiVersion()).isEqualTo("aisolate.dev/v1");
              assertThat(reference.getKind()).isEqualTo(kind.getKind());
              assertThat(reference.getName()).isEqualTo(ownerName);
              assertThat(reference.getUid()).isEqualTo(owner.getMetadata().getUid()).isNotBlank();
              assertThat(reference.getBlockOwnerDeletion()).isNull();
            });
  }

  @Test
  void givenReplacedSecrets_whenTheSaveCommitted_thenOnlyTheOnesNoRowNamesAreDeleted() {
    // Arrange: a row still names the first one
    GenericKubernetesResource owner = owner(ClusterSyncService.AIRLOCKS, "hr-assistant");
    Map<String, String> login = Map.of("username", "a", "password", "b");
    String shared = credentials.create(owner, "hr-acme-hr-assistant-ds-hr", login);
    String gone = credentials.create(owner, "hr-acme-hr-assistant-ds-hr", login);
    when(airlocks.isSecretReferenced(shared)).thenReturn(true);

    // Act
    credentials.handleSecretsReplaced(new SecretsReplaced(List.of(shared, gone, "never-there")));

    // Assert
    assertThat(client.secrets().inNamespace("aisolate").withName(shared).get()).isNotNull();
    assertThat(client.secrets().inNamespace("aisolate").withName(gone).get()).isNull();
  }

  @Test
  void givenAnOrphanSecret_whenSecretsReplaced_thenItsRowGoesToo() {
    // Arrange
    when(airlocks.isSecretReferenced("s")).thenReturn(false);

    // Act
    credentials.handleSecretsReplaced(new SecretsReplaced(List.of("s")));

    // Assert
    verify(rows).deleteOrphan(eq("s"), anyString());
  }

  @Test
  void givenAStillReferencedSecret_whenSecretsReplaced_thenItsRowStays() {
    // Arrange
    when(airlocks.isSecretReferenced("s")).thenReturn(true);

    // Act
    credentials.handleSecretsReplaced(new SecretsReplaced(List.of("s")));

    // Assert
    verify(rows, never()).deleteOrphan(any(), any());
  }

  @Test
  void givenSaveRolledBack_whenSecretCreatedHandled_thenGone() {
    // Arrange: a Secret the save created
    GenericKubernetesResource owner = owner(ClusterSyncService.AIRLOCKS, "hr-assistant");
    String created =
        credentials.create(
            owner, "hr-acme-hr-assistant-ds-hr", Map.of("username", "a", "password", "b"));
    assertThat(client.secrets().inNamespace("aisolate").withName(created).get()).isNotNull();

    // Act: as Spring calls it once the save rolled back
    credentials.handleSecretCreated(new SecretCreated(created));

    // Assert: its creation told Spring, the rollback took it
    assertThat(published).containsExactly(new SecretCreated(created));
    assertThat(client.secrets().inNamespace("aisolate").withName(created).get()).isNull();
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }
}
