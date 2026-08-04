package com.reportix.aisolate.toolserver;

import com.reportix.aisolate.identity.TracingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TracingConfig.class)
public class ToolServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ToolServerApplication.class, args);
  }
}
