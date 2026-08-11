package com.reportix.aisolate.toolserver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.config.DataSourceLogin;
import com.reportix.aisolate.toolserver.domain.CustomerDataSources;
import com.reportix.aisolate.toolserver.support.Containers;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;

class DataSourceConfigTest {

  private final DataSourceConfig config = new DataSourceConfig();

  @AfterEach
  void closePools() {
    config.closePools();
  }

  @Test
  void givenARoleThatMayWrite_whenAStatementWrites_thenThePoolRefusesIt() {
    // Arrange: the database's owner, not a read-only role
    PostgreSQLContainer db = Containers.acme();
    CustomerDataSources sources =
        config.customerDataSources(
            new CustomerDataSourceProperties(
                List.of(
                    new DataSourceLogin(
                        "owner", db.getJdbcUrl(), db.getUsername(), db.getPassword()))));

    // Act
    Throwable refused =
        catchThrowable(
            () -> sources.forId("owner").sql("CREATE TEMP TABLE probe (i int)").update());

    // Assert
    assertThat(refused).isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
  }
}
