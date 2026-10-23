package com.reportix.aisolate.backend;

import com.reportix.aisolate.identity.SecurityConfig;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The backend, installed with the AIsolate UI: its API, deploy, the apply. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(SecurityConfig.class)
@EnableScheduling
public class BackendApplication {

  /** The reveal window is measured against it; a test fixes its own. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  public static void main(String[] args) {
    SpringApplication.run(BackendApplication.class, args);
  }
}
