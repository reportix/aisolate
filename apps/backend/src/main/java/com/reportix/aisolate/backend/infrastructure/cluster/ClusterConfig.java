package com.reportix.aisolate.backend.infrastructure.cluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfig {

  /** In-cluster configuration: the pod's ServiceAccount, the Role the chart binds to it. */
  @Bean
  @ConditionalOnProperty(name = "aisolate.cluster.namespace")
  @ConditionalOnMissingBean
  KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }
}
