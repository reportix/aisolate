package com.reportix.aisolate.airlock;

import com.reportix.aisolate.identity.SecurityConfig;
import com.reportix.aisolate.identity.TracingConfig;
import java.io.PrintWriter;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** The airlock core: one airlock per pod, in every install alike. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({SecurityConfig.class, TracingConfig.class})
public class AirlockApplication {

  public static void main(String[] args) {
    // `validate` runs before Spring: no identity provider, no model, only the documents.
    if (args.length > 0 && "validate".equalsIgnoreCase(args[0])) {
      Path dir =
          Path.of(
              args.length > 1 ? args[1] : System.getenv().getOrDefault("AISOLATE_CONFIG_DIR", "."));
      System.exit(ValidateCommand.run(dir, new PrintWriter(System.out, true)));
    }
    SpringApplication.run(AirlockApplication.class, args);
  }
}
