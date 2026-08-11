package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.config.DataSourceLogin;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The airlock's data sources as the controller renders them, one entry per id from 0. */
@ConfigurationProperties("aisolate")
public record CustomerDataSourceProperties(List<DataSourceLogin> datasource) {

  public CustomerDataSourceProperties {
    datasource = datasource == null ? List.of() : List.copyOf(datasource);
  }
}
