package com.reportix.aisolate.controller;

import com.reportix.aisolate.controller.reconciler.Releases;
import com.reportix.aisolate.controller.reconciler.airlock.AirlockReconciler;
import com.reportix.aisolate.controller.reconciler.global.GlobalReconciler;
import com.reportix.aisolate.controller.reconciler.space.SpaceReconciler;
import com.reportix.aisolate.controller.render.ControllerProperties;
import com.reportix.aisolate.controller.render.Renderer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * The AIsolate controller: one per installation, in the data-plane namespace, namespace-scoped,
 * leader-elected, no cluster rights. Three reconcilers, one per kind, on the Java Operator SDK.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ControllerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ControllerApplication.class, args);
  }

  @Bean(destroyMethod = "close")
  KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  Operator operator(KubernetesClient client, ControllerProperties settings) {
    String namespace = settings.namespace();
    Operator operator =
        new Operator(
            o ->
                o.withKubernetesClient(client)
                    .withLeaderElectionConfiguration(
                        new LeaderElectionConfiguration("aisolate-controller", namespace)));
    Releases releases =
        new Releases(
            client, new Renderer(settings), namespace, Releases.BY_SERVER_SIDE_APPLY, settings);
    operator.register(new AirlockReconciler(releases), o -> o.settingNamespace(namespace));
    operator.register(new SpaceReconciler(releases), o -> o.settingNamespace(namespace));
    operator.register(new GlobalReconciler(settings), o -> o.settingNamespace(namespace));
    return operator;
  }
}
