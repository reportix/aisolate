package com.reportix.aisolate.toolserver.infrastructure.policy;

import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.toolserver.domain.Policy;
import com.reportix.aisolate.toolserver.domain.PolicySource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the policies come from: the mounted documents, the same ConfigMap as the airlock pods, and
 * only their revision answers.
 */
@Configuration
@Slf4j
public class PolicyConfig {

  @Bean
  PolicySource policySource(
      @Value("${aisolate.config.dir:}") String configDir,
      @Value("${aisolate.config.revision:}") String configRevision) {
    var configuration = ConfigurationLoader.load(configDir, configRevision);
    var source = new PolicySource(configuration);
    log.info(
        "config_loaded revision={} airlocks={}",
        configuration.revision(),
        source.listPolicies().stream().map(Policy::id).toList());
    return source;
  }
}
