package com.reportix.aisolate.backend;

import com.reportix.aisolate.identity.SecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The backend, installed with the AIsolate UI: its API, deploy, the apply. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(SecurityConfig.class)
@EnableScheduling
public class BackendApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackendApplication.class, args);
  }
}
